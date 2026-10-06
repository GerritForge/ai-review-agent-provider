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

import type {PluginApi} from '@gerritcodereview/typescript-api/plugin';
import type {
  Action,
  AiCodeReviewProvider,
  Actions,
  ChatRequest,
  ChatResponse,
  ChatResponseListener,
  Models,
} from '@gerritcodereview/typescript-api/ai-code-review';
import {HttpMethod} from '@gerritcodereview/typescript-api/rest';
import {HELP_ME_REVIEW_PROMPT, IMPROVE_COMMIT_MESSAGE} from './prompts';

export const AI_REVIEW_PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';

// Lines of unchanged context around each hunk sent to the AI model.
const DIFF_CONTEXT_LINES = 10;
// Context large enough to include every unchanged line of each file.
const FULL_FILE_CONTEXT_LINES = 1_000_000;
// Upper bound on the patch size to stay within model token limits.
const MAX_PATCH_CHARS = 200_000;

const REVIEW_CHANGE_ACTION_ID = 'review-change';
const REVIEW_CHANGE_FULL_ACTION_ID = 'review-change-full';
const REVIEW_COMMIT_ACTION_ID = 'review-commit';

const ACTIONS: Action[] = [
  {
    id: REVIEW_CHANGE_ACTION_ID,
    display_text: 'Help me with review',
    enable_send_without_input: true,
    initial_user_prompt: HELP_ME_REVIEW_PROMPT,
  },
  {
    id: REVIEW_CHANGE_FULL_ACTION_ID,
    display_text: 'Review with full files',
    enable_send_without_input: true,
    initial_user_prompt: HELP_ME_REVIEW_PROMPT,
  },
  {
    id: REVIEW_COMMIT_ACTION_ID,
    display_text: 'Improve commit message',
    enable_send_without_input: true,
    initial_user_prompt: IMPROVE_COMMIT_MESSAGE,
  },
];

// Unchanged context lines around each hunk, per action. Other actions
// fall back to DIFF_CONTEXT_LINES.
const CONTEXT_BY_ACTION = new Map<string, number>([
  [REVIEW_CHANGE_ACTION_ID, DIFF_CONTEXT_LINES],
  [REVIEW_CHANGE_FULL_ACTION_ID, FULL_FILE_CONTEXT_LINES],
  [REVIEW_COMMIT_ACTION_ID, 3],
]);

export declare interface ProviderInfo {
  plugin: string;
  display_name: string;
  models: string[];
  enabled: boolean;
}

declare interface AiCodeReviewOutput {
  text?: string;
  error?: ErrorInfo;
}

declare interface ErrorInfo {
  status_code: number;
  message: string;
}

export declare interface GetAiProvidersOutput {
  providers: ProviderInfo[];
}

function truncatePatch(patch: string): string {
  if (patch.length <= MAX_PATCH_CHARS) return patch;
  const omitted = patch.length - MAX_PATCH_CHARS;
  return (
    patch.slice(0, MAX_PATCH_CHARS) +
    `\n[... patch truncated: ${omitted} more characters omitted ...]\n`
  );
}

function buildChatResponse(text: string): ChatResponse {
  return {
    response_parts: [{id: 0, text}],
    references: [], // TODO: populate references
    citations: [], // TODO: populate citations
    timestamp_millis: Date.now(),
  };
}

async function callAiModelAndGenerateContent(args: {
  pluginApi: PluginApi;
  changeId: string;
  model: string;
  prompt: string;
}): Promise<string> {
  const {pluginApi, changeId, model, prompt} = args;
  const url = `/changes/${changeId}/ai-review-agent-provider~aiReview`;
  const separatorIdx = model.indexOf('/');
  const pluginName = model.slice(0, separatorIdx);
  const modelName = model.slice(separatorIdx + 1);

  const res: AiCodeReviewOutput = await pluginApi.restApi().post(url, {
    plugin: pluginName,
    model: modelName,
    prompt,
  });

  if (res.text) return res.text;
  if (!res.error) return '(No text returned by AI)';
  return formatAiError(res.error, model);
}

function formatAiError(error: ErrorInfo, model: string): string {
  if (error.status_code === 429) {
    const detail = error.message ? `\n\n> ${error.message}` : '';
    return (
      '⚠\uFE0F **Rate limit** ⚠\uFE0F\n\n' +
      `Model \`${model}\` is temporarily rate-limited by the upstream ` +
      'provider. Try again shortly, or pick a different model from the ' +
      `list.${detail}`
    );
  }
  return (
    `⚠\uFE0F **AI Model ERROR (http status=${error.status_code})** ` +
    `⚠\uFE0F\n\n${error.message}`
  );
}

export class AiCodeReviewProviderImpl implements AiCodeReviewProvider {
  // The provider does not support the "add extra context" feature (e.g., attaching additional files or notes beyond what Gerrit already sends)
  supports_add_context = false;

  // The provider does not maintain or accept chat history. Each request is treated as a fresh, stateless call
  supports_history = false;

  // The provider doesn't expose extra "more" actions beyond the ones defined in getActions()
  supports_more_menu = false;

  //  The provider can operate on the current change (e.g., review/explain the active Gerrit change)
  supports_this_change = true;

  plugin: PluginApi;

  defaultModel: string = '';

  constructor(plugin: PluginApi) {
    this.plugin = plugin;
  }

  private async fetchEnabledProviders(): Promise<ProviderInfo[]> {
    // A provider is "enabled" only once a working API token is configured.
    const aiReviewProvidersOutput: GetAiProvidersOutput = await this.plugin
      .restApi()
      .get(AI_REVIEW_PROVIDERS_ENDPOINT);
    return aiReviewProvidersOutput.providers.filter(p => p.enabled);
  }

  async getModels(): Promise<Models> {
    const aiEnabledProviders = await this.fetchEnabledProviders();

    // Reject instead of returning empty models to avoid an endless spinner.
    if (aiEnabledProviders.length === 0) {
      throw new Error('No API token configured. Add one in Settings.');
    }

    const providerModels = aiEnabledProviders.flatMap(providerInfo =>
      providerInfo.models.map(modelName => {
        return {
          model_id: `${providerInfo.plugin}/${modelName}`,
          short_text: providerInfo.display_name,
          full_display_text: `${providerInfo.display_name} (${modelName})`,
        };
      }),
    );

    this.defaultModel = providerModels[0].model_id;

    return {
      models: providerModels,
      default_model_id: this.defaultModel,
      documentation_url: 'https://ai.google.dev/api/generate-content',
      custom_actions: ACTIONS,
    };
  }

  async getActions(): Promise<Actions> {
    const aiEnabledProviders = await this.fetchEnabledProviders();

    // No enabled provider means getModels() rejects, so offer no actions.
    if (aiEnabledProviders.length === 0) {
      return {actions: [], default_action_id: ''};
    }

    return {
      actions: ACTIONS,
      default_action_id: REVIEW_CHANGE_ACTION_ID,
    };
  }

  chat(req: ChatRequest, listener: ChatResponseListener): void {
    void this.chatAsync(req, listener);
  }

  private async chatAsync(
    req: ChatRequest,
    listener: ChatResponseListener,
  ): Promise<void> {
    listener.emitResponse(
      buildChatResponse('_Fetching patch and calling AI model..._'),
    );

    try {
      const changeId = `${encodeURIComponent(req.change.project)}~${
        req.change._number
      }`;
      const patchPlaceholder = '{{patch}}';

      const context =
        CONTEXT_BY_ACTION.get(req.action?.id) ?? DIFF_CONTEXT_LINES;
      const patchUrl =
        `/changes/${changeId}/revisions/current/patch` +
        `?raw&context=${context}`;
      const res = await this.plugin.restApi().fetch(HttpMethod.GET, patchUrl);
      if (!res.ok) {
        throw new Error(`Failed to fetch patch (HTTP ${res.status})`);
      }
      const patch = truncatePatch(await res.text());

      // Replacer function: a string replacement would expand `$&` etc.
      // occurring in the patch text.
      const prompt = req.prompt.includes(patchPlaceholder)
        ? req.prompt.replace(patchPlaceholder, () => patch)
        : `${req.prompt}\n\n` +
          `Context: This is a code review for change ${changeId}.\n` +
          `Patch:\n${patch}`;
      const model = req.model_name || this.defaultModel;
      const text = await callAiModelAndGenerateContent({
        pluginApi: this.plugin,
        changeId,
        model,
        prompt,
      });

      const normalizedText = text.startsWith('\n') ? text : `\n${text}`;
      listener.emitResponse(buildChatResponse(normalizedText));
      listener.done();
    } catch (e) {
      listener.emitError(
        e instanceof Error ? e.message : 'Error fetching patch content',
      );
      listener.done();
    }
  }
}
