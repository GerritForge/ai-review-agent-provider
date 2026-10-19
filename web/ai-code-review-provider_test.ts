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
  AiCodeReviewProviderImpl,
  GetActionsOutput,
} from './ai-code-review-provider';
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
const ACTIONS_URL =
  `/changes/${CHANGE_ID}/revisions/${REVISION}/` +
  'ai-review-agent-provider~actions';

function patchUrl(revision: string, context: number) {
  return `/changes/${CHANGE_ID}/revisions/${revision}/patch?raw&context=${context}`;
}

suite('ai-code-review-provider tests', () => {
  let fetchStub: sinon.SinonStub;
  let getStub: sinon.SinonStub;
  let postStub: sinon.SinonStub;
  let provider: AiCodeReviewProviderImpl;
  let providers: unknown;
  let actionsOutput: GetActionsOutput;
  let change: ChangeInfo;

  setup(() => {
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
      revision: REVISION,
      actions: [
        {
          id: 'review-change',
          display_text: 'Help me with review',
          context_lines: 10,
          prompt: 'Review:\n{{patch}}',
        },
        {
          id: 'review-change-full',
          display_text: 'Review with full files',
          context_lines: 1000000,
          prompt: 'Review:\n{{patch}}',
        },
        {
          id: 'review-commit',
          display_text: 'Improve commit message',
          context_lines: 3,
          prompt: 'Improve:\n{{patch}}',
        },
        {
          id: 'security',
          display_text: 'Security review',
          context_lines: 20,
          prompt: 'Check {{project}}:\n{{patch}}',
        },
      ],
      default_action_id: 'review-change',
      fingerprint: 'f1',
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
      if (url.endsWith('ai-review-agent-provider~actions')) {
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
  });

  // A request as core sends it: with the action as it was offered, and the
  // action's prompt unless the user typed or edited one.
  function createRequest(
    action: string | Action | undefined,
    prompt?: string,
  ): ChatRequest {
    const info = actionsOutput.actions.find(a => a.id === action);
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
      .filter(url => url.endsWith('ai-review-agent-provider~actions'));
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

  test('loads actions of the current revision once', async () => {
    const [models, actions] = await Promise.all([
      provider.getModels(change),
      provider.getActions(change),
    ]);

    const expectedIds = [
      'review-change',
      'review-change-full',
      'review-commit',
      'security',
    ];
    assert.deepEqual(actionIds(models.custom_actions), expectedIds);
    assert.deepEqual(actionIds(actions.actions), expectedIds);
    assert.equal(actions.default_action_id, 'review-change');
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
    assert.deepEqual(actionsRequests(), [ACTIONS_URL]);
  });

  test('loads actions again for another change', async () => {
    await provider.getActions(change);
    await provider.getActions({...change, _number: 456} as ChangeInfo);

    assert.equal(actionsRequests().length, 2);
  });

  test('loads actions of current revision when SHA unknown', async () => {
    delete (change as {current_revision?: string}).current_revision;

    await provider.getActions(change);

    assert.deepEqual(actionsRequests(), [
      `/changes/${CHANGE_ID}/revisions/current/` +
        'ai-review-agent-provider~actions',
    ]);
  });

  test('offers custom actions only', async () => {
    actionsOutput.actions = actionsOutput.actions.slice(3);
    actionsOutput.default_action_id = 'security';

    const actions = await provider.getActions(change);

    assert.deepEqual(actionIds(actions.actions), ['security']);
    assert.equal(actions.default_action_id, 'security');
  });

  test('keeps typed prompts working when all actions are disabled', async () => {
    actionsOutput.actions = [];
    delete actionsOutput.default_action_id;

    const actions = await provider.getActions(change);
    const models = await provider.getModels(change);

    assert.deepEqual(models.custom_actions, []);
    assert.equal(actions.actions.length, 1);
    const fallback = actions.actions[0];
    assert.equal(actions.default_action_id, fallback.id);
    assert.isUndefined(fallback.enable_splash_page_card);

    getStub.resetHistory();
    const listener = await chat(createRequest(fallback, 'What changed?'));

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

  test('reports actions that cannot be loaded', async () => {
    getStub.callsFake((url: string) =>
      url === PROVIDERS_ENDPOINT
        ? Promise.resolve(providers)
        : Promise.reject(new Error('Invalid AI review action file')),
    );

    const actionsError = await rejection(provider.getActions(change));
    const modelsError = await rejection(provider.getModels(change));

    assert.equal(actionsError.message, 'Invalid AI review action file');
    assert.equal(modelsError.message, 'Invalid AI review action file');
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
  });

  test('sends action with its context lines', async () => {
    const listener = await chat(createRequest('review-change'));

    assert.deepEqual(actionsRequests(), [ACTIONS_URL]);
    assert.isTrue(fetchStub.calledOnce);
    assert.deepEqual(fetchStub.firstCall.args, [
      HttpMethod.GET,
      patchUrl(REVISION, 10),
    ]);
    assert.deepEqual(postStub.firstCall.args, [
      `/changes/${CHANGE_ID}/ai-review-agent-provider~aiReview`,
      {plugin: 'gemini', model: 'flash', prompt: 'Review:\nPATCH'},
    ]);
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
    assert.equal(responseText(listener), '\nAI says');
  });

  test('uses context lines of each action', async () => {
    const expectedContext: [string | undefined, number][] = [
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

  test('fetches actions and patch of current revision', async () => {
    delete (change as {current_revision?: string}).current_revision;

    await chat(createRequest('review-commit'));

    assert.deepEqual(actionsRequests(), [
      `/changes/${CHANGE_ID}/revisions/current/` +
        'ai-review-agent-provider~actions',
    ]);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 3));
  });

  test('uses the revision of a patch set uploaded later', async () => {
    const req = createRequest('review-commit');
    req.change = {...change, current_revision: 'def456'} as ChangeInfo;
    actionsOutput.revision = 'def456';

    await chat(req);

    assert.deepEqual(actionsRequests(), [
      `/changes/${CHANGE_ID}/revisions/def456/` +
        'ai-review-agent-provider~actions',
    ]);
    assert.equal(fetchStub.firstCall.args[1], patchUrl('def456', 3));
  });

  test('uses changed context lines of an unchanged action', async () => {
    const req = createRequest('security');
    actionsOutput.actions[3].context_lines = 50;

    await chat(req);

    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 50));
  });

  test('stops when the selected action changed', async () => {
    const req = createRequest('security', 'My edited prompt');
    actionsOutput.actions[3].prompt = 'Changed {{patch}}';

    const listener = await chat(req);

    assert.isTrue(
      listener.emitError.calledOnceWith(
        'The "Security review" action was changed or removed since this ' +
          'page was loaded. Copy your prompt if you edited it, then ' +
          'reload the page.',
      ),
    );
    assert.isTrue(listener.done.calledOnce);
    assert.isFalse(fetchStub.called);
    assert.isFalse(postStub.called);
  });

  test('stops when the label of the selected action changed', async () => {
    const req = createRequest('security');
    actionsOutput.actions[3].display_text = 'Security audit';

    const listener = await chat(req);

    assert.isTrue(listener.emitError.calledOnce);
    assert.isFalse(postStub.called);
  });

  test('stops when the selected action was removed', async () => {
    const req = createRequest('security');
    actionsOutput.actions = actionsOutput.actions.slice(0, 3);

    const listener = await chat(req);

    assert.isTrue(listener.emitError.calledOnce);
    assert.isFalse(postStub.called);
  });

  test('checks the action against the change it is sent for', async () => {
    await provider.getActions(change);
    const req = createRequest('security');
    req.change = {...change, _number: 456} as ChangeInfo;
    actionsOutput.actions = actionsOutput.actions.slice(0, 3);
    getStub.resetHistory();

    const listener = await chat(req);

    assert.deepEqual(actionsRequests(), [
      `/changes/my%2Fproject~456/revisions/${REVISION}/` +
        'ai-review-agent-provider~actions',
    ]);
    assert.isTrue(listener.emitError.calledOnce);
    assert.isFalse(postStub.called);
  });

  test('sends when only other actions changed', async () => {
    const req = createRequest('security');
    actionsOutput.actions[0].prompt = 'Changed {{patch}}';

    const listener = await chat(req);

    assert.isFalse(listener.emitError.called);
    assert.equal(fetchStub.firstCall.args[1], patchUrl(REVISION, 20));
  });

  test('reports actions that cannot be refreshed on send', async () => {
    getStub.rejects(new Error('Error 409 (Conflict): broken'));

    const listener = await chat(createRequest('security'));

    assert.isTrue(
      listener.emitError.calledOnceWith('Error 409 (Conflict): broken'),
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
        '{{project}} {{branch}} {{unknown}}\n{{patch}}\nagain {{patch}}',
      ),
    );

    assert.equal(
      sentPrompt(),
      `my/project main {{unknown}}\n${patch}\nagain {{patch}}`,
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
    const listener = await chat(
      createRequest('review-change', '{{patch}}' + 'x'.repeat(300_000)),
    );

    assert.isTrue(
      listener.emitError.calledOnceWith(
        'The prompt is longer than 300000 characters once the patch, ' +
          'project and branch are inserted.',
      ),
    );
    assert.isFalse(postStub.called);
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
