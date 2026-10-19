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
import type {ChangeInfo} from '@gerritcodereview/typescript-api/rest-api';
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

export const AI_REVIEW_PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';

const ACTIONS_ENDPOINT = 'ai-review-agent-provider~actions';

// Lines of unchanged context for prompts that are not one of the actions.
const DEFAULT_CONTEXT_LINES = 10;
// Upper bound on the patch size to stay within model token limits.
const MAX_PATCH_CHARS = 200_000;
// Upper bound on the prompt once the patch, project and branch are in.
const MAX_PROMPT_CHARS = 300_000;

// Core sends every prompt with an action, typed ones with the default
// action. This one is offered when the project enables no action, so that
// typed prompts still work. Without a splash page card it shows no button.
const FREE_FORM_ACTION: Action = {
  id: 'ai-review-agent-provider-free-form',
  display_text: 'Ask about this change',
};

const PLACEHOLDERS = /\{\{(patch|project|branch)\}\}/g;

// An AI review action defined in refs/meta/config, as returned by the
// actions REST endpoint. The prompt still contains its placeholders.
export declare interface ActionInfo {
  id: string;
  display_text: string;
  context_lines: number;
  prompt: string;
}

export declare interface GetActionsOutput {
  revision: string;
  actions: ActionInfo[];
  default_action_id?: string;
  fingerprint: string;
}

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

function changeIdOf(change: ChangeInfo): string {
  return `${encodeURIComponent(change.project)}~${change._number}`;
}

function toAction(info: ActionInfo): Action {
  return {
    id: info.id,
    display_text: info.display_text,
    enable_send_without_input: true,
    initial_user_prompt: info.prompt,
  };
}

// Whether the server still defines the action as the user saw it.
function isUnchanged(sent: Action, current: ActionInfo): boolean {
  return (
    sent.display_text === current.display_text &&
    sent.initial_user_prompt === current.prompt
  );
}

// Expands all placeholders in one pass, so placeholders occurring in the
// inserted patch, project or branch are left as they are. Only the first
// {{patch}} gets the patch, which keeps the prompt within the patch cap.
function expandPrompt(
  prompt: string,
  values: {patch: string; project: string; branch: string},
  changeId: string,
): string {
  let patchInserted = false;
  const expanded = prompt.replace(
    PLACEHOLDERS,
    (match, name: 'patch' | 'project' | 'branch') => {
      if (name !== 'patch') return values[name];
      if (patchInserted) return match;
      patchInserted = true;
      return values.patch;
    },
  );
  const withPatch = patchInserted
    ? expanded
    : `${expanded}\n\n` +
      `Context: This is a code review for change ${changeId}.\n` +
      `Patch:\n${values.patch}`;
  if (withPatch.length > MAX_PROMPT_CHARS) {
    throw new Error(
      `The prompt is longer than ${MAX_PROMPT_CHARS} characters once ` +
        'the patch, project and branch are inserted.',
    );
  }
  return withPatch;
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

  // The actions request of the revision loaded last, shared by getModels()
  // and getActions() so that both offer the same actions.
  private actionsRequest?: {key: string; output: Promise<GetActionsOutput>};

  constructor(plugin: PluginApi) {
    this.plugin = plugin;
  }

  private fetchActions(
    change: ChangeInfo,
    revision = change.current_revision ?? 'current',
  ): Promise<GetActionsOutput> {
    return this.plugin
      .restApi()
      .get(
        `/changes/${changeIdOf(change)}/revisions/${revision}/` +
          ACTIONS_ENDPOINT,
      );
  }

  private loadActions(change: ChangeInfo): Promise<GetActionsOutput> {
    const revision = change.current_revision ?? 'current';
    const key = `${changeIdOf(change)}@${revision}`;
    if (this.actionsRequest?.key !== key) {
      this.actionsRequest = {key, output: this.fetchActions(change, revision)};
    }
    return this.actionsRequest.output;
  }

  private async fetchEnabledProviders(): Promise<ProviderInfo[]> {
    // A provider is "enabled" only once a working API token is configured.
    const aiReviewProvidersOutput: GetAiProvidersOutput = await this.plugin
      .restApi()
      .get(AI_REVIEW_PROVIDERS_ENDPOINT);
    return aiReviewProvidersOutput.providers.filter(p => p.enabled);
  }

  async getModels(change: ChangeInfo): Promise<Models> {
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

    // Rejecting shows why the actions cannot be loaded in place of the
    // prompt input; core would not send typed prompts without them anyway.
    const output = await this.loadActions(change);

    return {
      models: providerModels,
      default_model_id: this.defaultModel,
      documentation_url: 'https://ai.google.dev/api/generate-content',
      custom_actions: output.actions.map(toAction),
    };
  }

  async getActions(change: ChangeInfo): Promise<Actions> {
    const aiEnabledProviders = await this.fetchEnabledProviders();

    // No enabled provider means getModels() rejects, so offer no actions.
    if (aiEnabledProviders.length === 0) {
      return {actions: [], default_action_id: ''};
    }

    const output = await this.loadActions(change);
    if (output.actions.length === 0) {
      return {
        actions: [FREE_FORM_ACTION],
        default_action_id: FREE_FORM_ACTION.id,
      };
    }
    return {
      actions: output.actions.map(toAction),
      default_action_id: output.default_action_id ?? '',
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
      const change = req.change;
      const changeId = changeIdOf(change);
      const sent = req.action;

      let revision = change.current_revision ?? 'current';
      let context = DEFAULT_CONTEXT_LINES;
      if (sent && sent.id !== FREE_FORM_ACTION.id) {
        // Use the current definition, and the patch of the revision it was
        // loaded for, but only if it is still the one the user started from.
        const current = await this.fetchActions(change, revision);
        const action = current.actions.find(a => a.id === sent.id);
        if (!action || !isUnchanged(sent, action)) {
          listener.emitError(
            `The "${sent.display_text}" action was changed or removed ` +
              'since this page was loaded. Copy your prompt if you ' +
              'edited it, then reload the page.',
          );
          listener.done();
          return;
        }
        revision = current.revision;
        context = action.context_lines;
      }

      const patchUrl =
        `/changes/${changeId}/revisions/${revision}/patch` +
        `?raw&context=${context}`;
      const res = await this.plugin.restApi().fetch(HttpMethod.GET, patchUrl);
      if (!res.ok) {
        throw new Error(`Failed to fetch patch (HTTP ${res.status})`);
      }
      const patch = truncatePatch(await res.text());

      const prompt = expandPrompt(
        req.prompt,
        {patch, project: change.project, branch: change.branch},
        changeId,
      );
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
