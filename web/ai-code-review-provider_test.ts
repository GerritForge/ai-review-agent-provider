/**
 * @license
 * Copyright (C) 2026 GerritForge, Inc.
 *
 * Licensed under the BSL 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  ActionInfo,
  AiCodeReviewProviderImpl,
  BUILT_IN_ACTIONS,
  GetActionsOutput,
} from './ai-code-review-provider';
import {HELP_ME_REVIEW_PROMPT} from './prompts';
import type {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import type {
  Action,
  ChatRequest,
  ChatResponseListener,
} from '@gerritcodereview/typescript-api/ai-code-review';
import type {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';
import {HttpMethod} from '@gerritcodereview/typescript-api/rest';
import {assert} from '@open-wc/testing';
import sinon from 'sinon';

const CHANGE_ID = 'my%2Fproject~123';
const REVISION = 'abc123';
const PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';
const ACTIONS_URL = '/projects/my%2Fproject/ai-review-agent-provider~actions';
const BUILT_IN_IDS = ['review-change', 'review-change-full', 'review-commit'];
const FREE_FORM = 'free_form';
const NOTICE =
  "\n⚠\uFE0F The project's AI review actions could not be loaded, so the " +
  'built-in action was used: Error 409 (Conflict): broken\n';

function patchUrl(revision: string, context: number) {
  const url = `/changes/${CHANGE_ID}/revisions/${revision}/patch`;
  return `${url}?raw&context=${context}`;
}

function reloadMessage(label: string) {
  return (
    `The "${label}" action was changed or removed since this page was ` +
    'loaded. Copy your prompt if you edited it, then reload the page.'
  );
}

suite('ai-code-review-provider tests', () => {
  let fetchStub: sinon.SinonStub;
  let getStub: sinon.SinonStub;
  let postStub: sinon.SinonStub;
  let provider: AiCodeReviewProviderImpl;
  let providers: unknown;
  let actionsOutput: GetActionsOutput & {
    actions: ActionInfo[];
    disabled_action_ids: string[];
  };
  let change: ChangeInfo;

  // Fresh fixtures and provider, also for each case of a table test.
  function reset() {
    providers = {
      providers: [
        {
          plugin: 'gemini',
          display_name: 'Gemini',
          models: ['flash'],
          enabled: true,
        },
        {
          plugin: 'claude',
          display_name: 'Claude',
          models: ['opus'],
          enabled: false,
        },
      ],
    };
    actionsOutput = {
      version: 'v1',
      actions: [
        {
          id: 'security',
          display_text: 'Security review',
          context_lines: 20,
          prompt: 'Check {{project}}:\n{{patch}}',
        },
      ],
      disabled_action_ids: [],
    };
    change = {
      project: 'my/project',
      _number: 123,
      branch: 'main',
      current_revision: REVISION,
    } as unknown as ChangeInfo;

    fetchStub = sinon.stub().resolves(new Response('PATCH'));
    getStub = sinon.stub().callsFake((url: string) => {
      if (url === PROVIDERS_ENDPOINT) return Promise.resolve(providers);
      if (url.includes('ai-review-agent-provider~actions')) {
        if (url.endsWith(`?version=${actionsOutput.version}`)) {
          return Promise.resolve({version: actionsOutput.version});
        }
        // A copy, so that later changes to actionsOutput are not seen
        // through objects returned earlier.
        return Promise.resolve(structuredClone(actionsOutput));
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    postStub = sinon.stub().resolves({text: 'AI says'});
    const pluginApi = {
      restApi: () => {
        return {fetch: fetchStub, get: getStub, post: postStub};
      },
    } as unknown as PluginApi;
    provider = new AiCodeReviewProviderImpl(pluginApi);
  }

  setup(reset);

  // A request as core sends it: with the action as it was offered, and the
  // action's prompt unless the user typed or edited one.
  function createRequest(
    action: string | Action | undefined,
    prompt?: string,
  ): ChatRequest {
    const info =
      actionsOutput.actions.find(a => a.id === action) ??
      BUILT_IN_ACTIONS.find(a => a.id === action);
    const sent =
      typeof action === 'string'
        ? {
            id: action,
            display_text: info?.display_text ?? action,
            initial_user_prompt: info?.prompt,
          }
        : action;
    return {
      action: sent,
      prompt: prompt ?? info?.prompt ?? 'Review:\n{{patch}}',
      change,
      model_name: 'gemini/flash',
    } as unknown as ChatRequest;
  }

  function createListener() {
    let resolveDone: () => void;
    const finished = new Promise<void>(resolve => (resolveDone = resolve));
    const listener = {
      emitResponse: sinon.stub(),
      emitError: sinon.stub(),
      done: sinon.stub().callsFake(() => resolveDone()),
    };
    return {listener, finished};
  }

  async function chat(req: ChatRequest) {
    const {listener, finished} = createListener();
    provider.chat(req, listener as unknown as ChatResponseListener);
    await finished;
    return listener;
  }

  function responseText(listener: {emitResponse: sinon.SinonStub}): string {
    return listener.emitResponse.lastCall.args[0].response_parts[0].text;
  }

  function sentPrompt(): string {
    assert.isTrue(postStub.calledOnce);
    return postStub.firstCall.args[1].prompt;
  }

  function actionsRequests(): string[] {
    return getStub.args
      .map(args => args[0] as string)
      .filter(url => url.includes('ai-review-agent-provider~actions'));
  }

  function providersRequests(): number {
    return getStub.args.filter(args => args[0] === PROVIDERS_ENDPOINT).length;
  }

  async function rejection(promise: Promise<unknown>): Promise<Error> {
    try {
      await promise;
    } catch (e) {
      return e as Error;
    }
    throw new Error('expected a rejection');
  }

  const actionIds = (list?: Action[]) => (list ?? []).map(a => a.id);

  test('offers built-ins, then project actions, from one request', async () => {
    const [models, actions] = await Promise.all([
      provider.getModels(change),
      provider.getActions(change),
    ]);

    const expectedIds = [...BUILT_IN_IDS, 'security'];
    assert.deepEqual(actionIds(models.custom_actions), expectedIds);
    assert.deepEqual(actionIds(actions.actions), [...expectedIds, FREE_FORM]);
    assert.equal(actions.default_action_id, FREE_FORM);
    assert.equal(actions.actions[0].initial_user_prompt, HELP_ME_REVIEW_PROMPT);
    assert.deepEqual(actions.actions[3], {
      id: 'security',
      display_text: 'Security review',
      enable_send_without_input: true,
      initial_user_prompt: 'Check {{project}}:\n{{patch}}',
    });
    assert.deepEqual(
      models.models.map(m => m.model_id),
      ['gemini/flash'],
    );
    assert.equal(models.default_model_id, 'gemini/flash');
    assert.equal(providersRequests(), 1);
    assert.deepEqual(actionsRequests(), [ACTIONS_URL]);
  });

  test('checks kept actions when the change is opened again', async () => {
    await provider.getActions(change);
    const actions = await provider.getActions(change);

    assert.deepEqual(actionsRequests(), [
      ACTIONS_URL,
      `${ACTIONS_URL}?version=v1`,
    ]);
    assert.deepEqual(actionIds(actions.actions), [
      ...BUILT_IN_IDS,
      'security',
      FREE_FORM,
    ]);
  });

  test('keeps sharing a request when another change loaded', async () => {
    const other = {...change, project: 'other', _number: 456} as ChangeInfo;
    const held: Array<() => void> = [];
    getStub.callsFake((url: string) =>
      url === PROVIDERS_ENDPOINT
        ? Promise.resolve(providers)
        : new Promise(resolve =>
            held.push(() => resolve(structuredClone(actionsOutput))),
          ),
    );
    const tick = () => new Promise(resolve => setTimeout(resolve));

    const otherModels = provider.getModels(other);
    const models = provider.getModels(change);
    await tick();
    held[0]();
    await otherModels;
    const actions = provider.getActions(change);
    await tick();
    held.forEach(release => release());
    await Promise.all([models, actions]);

    assert.deepEqual(actionsRequests(), [
      '/projects/other/ai-review-agent-provider~actions',
      ACTIONS_URL,
    ]);
  });

  test('only checks kept actions for another change', async () => {
    await provider.getActions(change);
    const models = await provider.getModels({
      ...change,
      _number: 456,
    } as ChangeInfo);

    assert.deepEqual(actionsRequests(), [
      ACTIONS_URL,
      `${ACTIONS_URL}?version=v1`,
    ]);
    assert.deepEqual(actionIds(models.custom_actions), [
      ...BUILT_IN_IDS,
      'security',
    ]);
  });

  test('loads actions again once they changed', async () => {
    await provider.getActions(change);
    actionsOutput.version = 'v2';
    actionsOutput.actions = [];
    actionsOutput.disabled_action_ids = ['review-change'];

    const models = await provider.getModels({
      ...change,
      _number: 456,
    } as ChangeInfo);

    assert.deepEqual(actionIds(models.custom_actions), [
      'review-change-full',
      'review-commit',
    ]);
  });

  test('offers built-ins when an unknown version comes back', async () => {
    await provider.getActions(change);
    getStub.callsFake((url: string) =>
      Promise.resolve(url === PROVIDERS_ENDPOINT ? providers : {version: 'v3'}),
    );

    const models = await provider.getModels(change);

    assert.deepEqual(actionIds(models.custom_actions), BUILT_IN_IDS);
  });

  test('forgets the least recently used project', async () => {
    const changeOf = (i: number, n = i) =>
      ({...change, project: `p${i}`, _number: n} as ChangeInfo);
    for (let i = 0; i < 20; i++) {
      await provider.getActions(changeOf(i));
    }
    await provider.getActions(changeOf(0, 100));
    await provider.getActions(changeOf(20));
    getStub.resetHistory();

    for (const i of [0, 2, 1]) {
      await provider.getActions(changeOf(i, 200 + i));
    }

    assert.deepEqual(actionsRequests(), [
      '/projects/p0/ai-review-agent-provider~actions?version=v1',
      '/projects/p2/ai-review-agent-provider~actions?version=v1',
      '/projects/p1/ai-review-agent-provider~actions',
    ]);
  });

  test('replaces built-ins in place and hides disabled ones', async () => {
    actionsOutput.actions = [
      {id: 'alpha', display_text: 'Alpha', context_lines: 10, prompt: 'A'},
      {
        id: 'review-change-full',
        display_text: 'Full review',
        context_lines: 100,
        prompt: 'Full {{patch}}',
      },
      ...actionsOutput.actions,
    ];
    actionsOutput.disabled_action_ids = ['review-change'];

    const models = await provider.getModels(change);

    assert.deepEqual(actionIds(models.custom_actions), [
      'review-change-full',
      'review-commit',
      'alpha',
      'security',
    ]);
    assert.equal(models.custom_actions?.[0].display_text, 'Full review');
  });

  test('sends the project replacement of a built-in action', async () => {
    actionsOutput.actions.push({
      id: 'review-commit',
      display_text: 'Commit message',
      context_lines: 5,
      prompt: 'Improve {{patch}}',
    });

    await chat(createRequest('review-commit'));

    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 5));
    assert.equal(sentPrompt(), 'Improve PATCH');
  });

  test('offers only the free-form action when all are disabled', async () => {
    actionsOutput.actions = [];
    actionsOutput.disabled_action_ids = [...BUILT_IN_IDS];

    const actions = await provider.getActions(change);
    const models = await provider.getModels(change);

    assert.deepEqual(models.custom_actions, []);
    assert.deepEqual(actionIds(actions.actions), [FREE_FORM]);
    assert.isUndefined(actions.actions[0].enable_splash_page_card);
  });

  test('sends typed prompts with default context, unchecked', async () => {
    const actions = await provider.getActions(change);
    const typed = actions.actions.find(a => a.id === actions.default_action_id);
    // No action file can have this id.
    assert.notMatch(typed!.id, /^[a-z][a-z0-9-]{0,63}$/);
    getStub.resetHistory();

    const listener = await chat(createRequest(typed, 'What changed?'));

    assert.isFalse(listener.emitError.called);
    assert.deepEqual(actionsRequests(), []);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 10));
    assert.equal(
      sentPrompt(),
      'What changed?\n\n' +
        `Context: This is a code review for change ${CHANGE_ID}.\n` +
        'Patch:\nPATCH',
    );
  });

  test('offers built-in actions when actions cannot be loaded', async () => {
    getStub.callsFake((url: string) =>
      url === PROVIDERS_ENDPOINT
        ? Promise.resolve(providers)
        : Promise.reject(new Error('Invalid AI review action file')),
    );

    const models = await provider.getModels(change);

    assert.deepEqual(actionIds(models.custom_actions), BUILT_IN_IDS);
  });

  test('offers nothing when no provider is enabled', async () => {
    providers = {
      providers: [
        {
          plugin: 'gemini',
          display_name: 'Gemini',
          models: ['flash'],
          enabled: false,
        },
      ],
    };

    const error = await rejection(provider.getModels(change));

    assert.equal(
      error.message,
      'No API token configured. Add one in Settings.',
    );
    assert.deepEqual(await provider.getActions(change), {
      actions: [],
      default_action_id: '',
    });
    assert.deepEqual(actionsRequests(), []);
  });

  test('loads no actions until providers can be loaded', async () => {
    let providersFail = true;
    getStub.callsFake((url: string) => {
      if (url !== PROVIDERS_ENDPOINT) {
        return Promise.resolve(structuredClone(actionsOutput));
      }
      return providersFail
        ? Promise.reject(new Error('providers unavailable'))
        : Promise.resolve(providers);
    });

    const [models, actions] = await Promise.all([
      rejection(provider.getModels(change)),
      rejection(provider.getActions(change)),
    ]);

    assert.equal(models.message, 'providers unavailable');
    assert.equal(actions.message, 'providers unavailable');
    assert.deepEqual(actionsRequests(), []);

    providersFail = false;
    const retried = await provider.getModels(change);

    assert.deepEqual(actionIds(retried.custom_actions), [
      ...BUILT_IN_IDS,
      'security',
    ]);
    assert.deepEqual(actionsRequests(), [ACTIONS_URL]);
  });

  test('sends the prompt with the patch to the selected model', async () => {
    const listener = await chat(createRequest('review-change'));

    assert.deepEqual(actionsRequests(), [ACTIONS_URL]);
    assert.isTrue(fetchStub.calledOnce);
    assert.deepEqual(fetchStub.firstCall.args, [
      HttpMethod.GET,
      patchUrl(REVISION, 10),
    ]);
    assert.deepEqual(postStub.firstCall.args, [
      `/changes/${CHANGE_ID}/ai-review-agent-provider~aiReview`,
      {
        plugin: 'gemini',
        model: 'flash',
        prompt: HELP_ME_REVIEW_PROMPT.replace('{{patch}}', 'PATCH'),
      },
    ]);
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
    assert.equal(responseText(listener), '\nAI says');
  });

  test('uses context lines of each action', async () => {
    const expectedContext: [string | undefined, number][] = [
      ['review-change', 10],
      ['review-change-full', 1000000],
      ['review-commit', 3],
      ['security', 20],
      [undefined, 10],
    ];
    for (const [actionId, context] of expectedContext) {
      fetchStub.resetHistory();
      fetchStub.resolves(new Response('PATCH'));

      await chat(createRequest(actionId));

      assert.isTrue(fetchStub.calledOnce, `action ${actionId}`);
      assert.equal(
        fetchStub.firstCall.args[1],
        patchUrl(REVISION, context),
        `action ${actionId}`,
      );
    }
  });

  test('sends edited prompt of an unchanged action', async () => {
    const listener = await chat(createRequest('security', 'Only {{patch}}'));

    assert.isFalse(listener.emitError.called);
    assert.equal(sentPrompt(), 'Only PATCH');
  });

  test('fetches patch of current revision when SHA unknown', async () => {
    delete (change as {current_revision?: string}).current_revision;

    await chat(createRequest('review-commit'));

    assert.equal(fetchStub.firstCall.args[1], patchUrl('current', 3));
  });

  test('uses the revision of a patch set uploaded later', async () => {
    await provider.getActions(change);
    const req = createRequest('review-commit');
    req.change = {...change, current_revision: 'def456'} as ChangeInfo;

    await chat(req);

    assert.equal(fetchStub.firstCall.args[1], patchUrl('def456', 3));
  });

  test('only checks kept actions before sending', async () => {
    await provider.getActions(change);

    const listener = await chat(createRequest('security'));

    assert.deepEqual(actionsRequests(), [
      ACTIONS_URL,
      `${ACTIONS_URL}?version=v1`,
    ]);
    assert.isFalse(listener.emitError.called);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 20));
  });

  test('uses changed context lines of an unchanged action', async () => {
    await provider.getActions(change);
    const req = createRequest('security');
    actionsOutput.version = 'v2';
    actionsOutput.actions[0].context_lines = 50;

    await chat(req);

    assert.include(actionsRequests(), `${ACTIONS_URL}?version=v1`);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 50));
  });

  test('stops when the selected action changed', async () => {
    const changes: [string, string, () => void][] = [
      [
        'security',
        'Security review',
        () => {
          actionsOutput.actions[0].prompt = 'Changed {{patch}}';
        },
      ],
      [
        'security',
        'Security review',
        () => {
          actionsOutput.actions[0].display_text = 'Security audit';
        },
      ],
      [
        'security',
        'Security review',
        () => {
          actionsOutput.actions = [];
        },
      ],
      [
        'review-change',
        'Help me with review',
        () => {
          actionsOutput.disabled_action_ids = ['review-change'];
        },
      ],
    ];
    for (const [id, label, changeAction] of changes) {
      reset();
      await provider.getActions(change);
      const req = createRequest(id, 'My edited prompt');
      actionsOutput.version = 'v2';
      changeAction();

      const listener = await chat(req);

      assert.include(actionsRequests(), `${ACTIONS_URL}?version=v1`);
      assert.isTrue(listener.emitError.calledOnceWith(reloadMessage(label)));
      assert.isTrue(listener.done.calledOnce);
      assert.isFalse(fetchStub.called);
      assert.isFalse(postStub.called);
    }
  });

  test('checks the action against the project it is sent for', async () => {
    await provider.getActions(change);
    const req = createRequest('security');
    req.change = {...change, project: 'other/project'} as ChangeInfo;
    actionsOutput.actions = [];
    getStub.resetHistory();

    const listener = await chat(req);

    assert.deepEqual(actionsRequests(), [
      '/projects/other%2Fproject/ai-review-agent-provider~actions',
    ]);
    assert.isTrue(
      listener.emitError.calledOnceWith(reloadMessage('Security review')),
    );
    assert.isFalse(postStub.called);
  });

  test('sends when only other actions changed', async () => {
    await provider.getActions(change);
    const req = createRequest('security');
    actionsOutput.version = 'v2';
    actionsOutput.actions.push({
      ...BUILT_IN_ACTIONS[0],
      prompt: 'Changed {{patch}}',
    });

    const listener = await chat(req);

    assert.include(actionsRequests(), `${ACTIONS_URL}?version=v1`);
    assert.isFalse(listener.emitError.called);
    assert.isTrue(postStub.calledOnce);
  });

  test('sends built-in action with a notice when actions fail', async () => {
    getStub.rejects(new Error('Error 409 (Conflict): broken'));

    const listener = await chat(createRequest('review-commit'));

    assert.isFalse(listener.emitError.called);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 3));
    assert.equal(responseText(listener), `${NOTICE}\nAI says`);
  });

  test('reports actions that cannot be refreshed on send', async () => {
    getStub.rejects(new Error('Error 409 (Conflict): broken'));

    const listener = await chat(createRequest('security'));

    assert.isTrue(
      listener.emitError.calledOnceWith(
        "The project's AI review actions could not be loaded, so " +
          '"Security review" was not sent: Error 409 (Conflict): broken',
      ),
    );
    assert.isTrue(listener.done.calledOnce);
    assert.isFalse(fetchStub.called);
    assert.isFalse(postStub.called);
  });

  test('expands placeholders once', async () => {
    const patch = "-a = '$&' {{project}}\n+b = {{branch}} {{patch}}\n";
    fetchStub.resolves(new Response(patch));

    await chat(
      createRequest(
        'review-change',
        '{{project}} {{unknown}}\n{{patch}}\n{{branch}} again {{patch}}',
      ),
    );

    assert.equal(
      sentPrompt(),
      `my/project {{unknown}}\n${patch}\nmain again {{patch}}`,
    );
  });

  test('appends patch section when prompt has no placeholder', async () => {
    await chat(createRequest('review-change', 'Explain {{project}}'));

    assert.equal(
      sentPrompt(),
      'Explain my/project\n\n' +
        `Context: This is a code review for change ${CHANGE_ID}.\n` +
        'Patch:\nPATCH',
    );
  });

  test('does not send prompts longer than 300000 characters', async () => {
    fetchStub.resolves(new Response('a'.repeat(200_000)));

    const listener = await chat(
      createRequest('review-change', '{{patch}}' + 'x'.repeat(100_001)),
    );

    assert.isTrue(
      listener.emitError.calledOnceWith(
        'The prompt is longer than 300000 characters once the patch, ' +
          'project and branch are inserted.',
      ),
    );
    assert.isFalse(postStub.called);
  });

  test('sends prompts of exactly 300000 characters', async () => {
    fetchStub.resolves(new Response('a'.repeat(200_000)));

    await chat(
      createRequest('review-change', '{{patch}}' + 'x'.repeat(100_000)),
    );

    assert.lengthOf(sentPrompt(), 300_000);
  });

  test('truncates patch longer than 200000 characters', async () => {
    fetchStub.resolves(new Response('a'.repeat(200_005)));

    await chat(createRequest('review-change', '{{patch}}'));

    assert.equal(
      sentPrompt(),
      'a'.repeat(200_000) +
        '\n[... patch truncated: 5 more characters omitted ...]\n',
    );
  });

  test('does not truncate patch of exactly 200000 characters', async () => {
    fetchStub.resolves(new Response('a'.repeat(200_000)));

    await chat(createRequest('review-change', '{{patch}}'));

    assert.equal(sentPrompt(), 'a'.repeat(200_000));
  });

  test('reports error when patch cannot be fetched', async () => {
    fetchStub.resolves(new Response('Not found', {status: 404}));

    const listener = await chat(createRequest('review-change'));

    assert.isTrue(
      listener.emitError.calledOnceWith('Failed to fetch patch (HTTP 404)'),
    );
    assert.isTrue(listener.done.calledOnce);
    assert.isFalse(postStub.called);
  });

  test('reports when the AI returns no text', async () => {
    postStub.resolves({});

    const listener = await chat(createRequest('review-change'));

    assert.equal(responseText(listener), '\n(No text returned by AI)');
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
  });

  test('reports rate limit with upstream detail', async () => {
    postStub.resolves({
      error: {status_code: 429, message: 'Quota exceeded'},
    });

    const listener = await chat(createRequest('review-change'));

    assert.equal(
      responseText(listener),
      '\n⚠\uFE0F **Rate limit** ⚠\uFE0F\n\n' +
        'Model `gemini/flash` is temporarily rate-limited by the upstream ' +
        'provider. Try again shortly, or pick a different model from the ' +
        'list.\n\n> Quota exceeded',
    );
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
  });

  test('reports rate limit without upstream detail', async () => {
    postStub.resolves({error: {status_code: 429, message: ''}});

    const listener = await chat(createRequest('review-change'));

    assert.isTrue(responseText(listener).endsWith('from the list.'));
    assert.isTrue(listener.done.calledOnce);
  });

  test('reports other AI model errors with status', async () => {
    postStub.resolves({
      error: {status_code: 500, message: 'Internal error'},
    });

    const listener = await chat(createRequest('review-change'));

    assert.equal(
      responseText(listener),
      '\n⚠\uFE0F **AI Model ERROR (http status=500)** ⚠\uFE0F\n\nInternal error',
    );
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
  });

  test('reports failed AI request', async () => {
    postStub.rejects(new Error('Network down'));

    const listener = await chat(createRequest('review-change'));

    assert.isTrue(listener.emitError.calledOnceWith('Network down'));
    assert.isTrue(listener.done.calledOnce);
  });
});
