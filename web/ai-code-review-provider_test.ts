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

import {AiCodeReviewProviderImpl} from './ai-code-review-provider';
import type {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import type {
  Action,
  ChatRequest,
  ChatResponseListener,
} from '@gerritcodereview/typescript-api/ai-code-review';
import {HttpMethod} from '@gerritcodereview/typescript-api/rest';
import {assert} from '@open-wc/testing';
import sinon from 'sinon';

const CHANGE_ID = 'my%2Fproject~123';
const PATCH_URL = `/changes/${CHANGE_ID}/revisions/current/patch`;
const PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';

suite('ai-code-review-provider tests', () => {
  let fetchStub: sinon.SinonStub;
  let getStub: sinon.SinonStub;
  let postStub: sinon.SinonStub;
  let provider: AiCodeReviewProviderImpl;

  setup(() => {
    fetchStub = sinon.stub().resolves(new Response('PATCH'));
    getStub = sinon.stub().resolves({
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
    });
    postStub = sinon.stub().resolves({text: 'AI says'});
    const pluginApi = {
      restApi: () => {
        return {fetch: fetchStub, get: getStub, post: postStub};
      },
    } as unknown as PluginApi;
    provider = new AiCodeReviewProviderImpl(pluginApi);
  });

  function createRequest(
    actionId: string | undefined,
    prompt = 'Review:\n{{patch}}',
  ): ChatRequest {
    return {
      action: actionId === undefined ? undefined : {id: actionId},
      prompt,
      change: {project: 'my/project', _number: 123},
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

  test('fetches the patch once with 10 lines of context', async () => {
    const listener = await chat(createRequest('review-change'));

    assert.isTrue(fetchStub.calledOnce);
    assert.deepEqual(fetchStub.firstCall.args, [
      HttpMethod.GET,
      `${PATCH_URL}?raw&context=10`,
    ]);
    assert.isFalse(getStub.called);
    assert.isTrue(postStub.calledOnce);
    assert.deepEqual(postStub.firstCall.args, [
      `/changes/${CHANGE_ID}/ai-review-agent-provider~aiReview`,
      {plugin: 'gemini', model: 'flash', prompt: 'Review:\nPATCH'},
    ]);
    assert.isFalse(listener.emitError.called);
    assert.isTrue(listener.done.calledOnce);
    assert.equal(responseText(listener), '\nAI says');
  });

  test('uses context per action', async () => {
    const expectedContext: [string | undefined, number][] = [
      ['review-change-full', 1000000],
      ['review-commit', 3],
      ['unknown-action', 10],
      [undefined, 10],
    ];
    for (const [actionId, context] of expectedContext) {
      fetchStub.resetHistory();
      fetchStub.resolves(new Response('PATCH'));

      await chat(createRequest(actionId));

      assert.isTrue(fetchStub.calledOnce, `action ${actionId}`);
      assert.equal(
        fetchStub.firstCall.args[1],
        `${PATCH_URL}?raw&context=${context}`,
        `action ${actionId}`,
      );
    }
  });

  test('inserts patch verbatim into placeholder', async () => {
    const patch = "-a = '$&'\n+a = \"$'\" + `$$`\n";
    fetchStub.resolves(new Response(patch));

    await chat(createRequest('review-change', 'Before {{patch}} after'));

    assert.equal(sentPrompt(), `Before ${patch} after`);
  });

  test('appends patch section when prompt has no placeholder', async () => {
    await chat(createRequest('review-change', 'Explain this change'));

    assert.equal(
      sentPrompt(),
      'Explain this change\n\n' +
        `Context: This is a code review for change ${CHANGE_ID}.\n` +
        'Patch:\nPATCH',
    );
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

  test('exposes the same actions in models and actions', async () => {
    const models = await provider.getModels();
    const actions = await provider.getActions();

    const actionIds = (list?: Action[]) => (list ?? []).map(a => a.id);
    const expectedIds = [
      'review-change',
      'review-change-full',
      'review-commit',
    ];
    assert.deepEqual(actionIds(models.custom_actions), expectedIds);
    assert.deepEqual(actionIds(actions.actions), expectedIds);
    assert.equal(actions.default_action_id, 'review-change');
    assert.deepEqual(
      models.models.map(m => m.model_id),
      ['gemini/flash'],
    );
    assert.equal(models.default_model_id, 'gemini/flash');
    assert.isTrue(getStub.alwaysCalledWithExactly(PROVIDERS_ENDPOINT));
  });

  test('offers nothing when no provider is enabled', async () => {
    getStub.resolves({
      providers: [
        {
          plugin: 'gemini',
          display_name: 'Gemini',
          models: ['flash'],
          enabled: false,
        },
      ],
    });

    let error: unknown;
    try {
      await provider.getModels();
    } catch (e) {
      error = e;
    }
    assert.instanceOf(error, Error);
    assert.equal(
      (error as Error).message,
      'No API token configured. Add one in Settings.',
    );
    assert.deepEqual(await provider.getActions(), {
      actions: [],
      default_action_id: '',
    });
  });
});
