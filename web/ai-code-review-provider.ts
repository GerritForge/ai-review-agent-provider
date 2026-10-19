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
import {HELP_ME_REVIEW_PROMPT, IMPROVE_COMMIT_MESSAGE} from './prompts';

export const AI_REVIEW_PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';

const ACTIONS_ENDPOINT = 'ai-review-agent-provider~actions';

// Lines of unchanged context for prompts that are not one of the actions.
const DEFAULT_CONTEXT_LINES = 10;
// Upper bound on the patch size to stay within model token limits.
const MAX_PATCH_CHARS = 200_000;
// Upper bound on the prompt once the patch, project and branch are in.
const MAX_PROMPT_CHARS = 300_000;
// Projects whose actions are kept, so that opening another change of the
// same project only checks that they are still current.
const MAX_CACHED_PROJECTS = 20;

// Core sends every prompt with an action, typed ones with the default
// action. This one is the default, so that typed prompts get the default
// context and do not depend on any action. Without a splash page card it
// shows no button, and no action file can have its id.
const FREE_FORM_ACTION: Action = {
  id: 'free_form',
  display_text: 'Ask about this change',
};

// Offered unless the project replaces or disables them, and whenever the
// project's actions cannot be loaded.
export const BUILT_IN_ACTIONS: ActionInfo[] = [
  {
    id: 'review-change',
    display_text: 'Help me with review',
    context_lines: 10,
    prompt: HELP_ME_REVIEW_PROMPT,
  },
  {
    id: 'review-change-full',
    display_text: 'Review with full files',
    // Large enough to include every unchanged line of each file.
    context_lines: 1_000_000,
    prompt: HELP_ME_REVIEW_PROMPT,
  },
  {
    id: 'review-commit',
    display_text: 'Improve commit message',
    context_lines: 3,
    prompt: IMPROVE_COMMIT_MESSAGE,
  },
];

const PLACEHOLDERS = /\{\{(patch|project|branch)\}\}/g;

// An AI review action, built in or as returned by the project actions REST
// endpoint. The prompt still contains its placeholders.
export declare interface ActionInfo {
  id: string;
  display_text: string;
  context_lines: number;
  prompt: string;
}

// Without actions when the version the client passed is still current.
export declare interface GetActionsOutput {
  version: string;
  actions?: ActionInfo[];
  disabled_action_ids?: string[];
}

declare interface ProjectActions {
  version: string;
  actions: ActionInfo[];
  disabled_action_ids: string[];
}

// The actions offered for a change, and why the project's actions could
// not be loaded when the built-in ones are offered instead.
declare interface OfferedActions {
  actions: ActionInfo[];
  error?: string;
}

// The enabled providers, and the actions offered with them; the actions
// are loaded only if a provider is enabled.
declare interface ChatSetup {
  providers: ProviderInfo[];
  offered: OfferedActions;
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

// The built-in actions, each replaced or hidden by the project's action of
// the same id, followed by the project's other actions.
function offer(project: ProjectActions): ActionInfo[] {
  const byId = new Map(project.actions.map(a => [a.id, a]));
  const disabled = new Set(project.disabled_action_ids);
  const builtIns = BUILT_IN_ACTIONS.filter(a => !disabled.has(a.id)).map(
    a => byId.get(a.id) ?? a,
  );
  const builtInIds = new Set(BUILT_IN_ACTIONS.map(a => a.id));
  return [...builtIns, ...project.actions.filter(a => !builtInIds.has(a.id))];
}

function fallbackNotice(error: string): string {
  return (
    "\n⚠\uFE0F The project's AI review actions could not be loaded, so " +
    `the built-in action was used: ${error}\n`
  );
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

  // The actions of recently used projects, least recently used first.
  private readonly projectActions = new Map<string, ProjectActions>();

  // The setup request of a change while it runs, shared by getModels()
  // and getActions(), which core calls together, so that both offer the
  // same actions. Calls after it ended check the actions again.
  private setupRequest?: {key: string; output: Promise<ChatSetup>};

  constructor(plugin: PluginApi) {
    this.plugin = plugin;
  }

  // Loads the actions of a project, or only checks that the kept ones are
  // still current.
  private async fetchActions(project: string): Promise<ProjectActions> {
    const kept = this.projectActions.get(project);
    const query = kept ? `?version=${encodeURIComponent(kept.version)}` : '';
    const res: GetActionsOutput = await this.plugin
      .restApi()
      .get(
        `/projects/${encodeURIComponent(project)}/${ACTIONS_ENDPOINT}${query}`,
      );
    let current: ProjectActions;
    if (res.actions) {
      current = {
        version: res.version,
        actions: res.actions,
        disabled_action_ids: res.disabled_action_ids ?? [],
      };
    } else if (kept && kept.version === res.version) {
      current = kept;
    } else {
      throw new Error('The AI review actions could not be loaded.');
    }
    this.projectActions.delete(project);
    this.projectActions.set(project, current);
    if (this.projectActions.size > MAX_CACHED_PROJECTS) {
      const oldest = this.projectActions.keys().next().value;
      if (oldest !== undefined) this.projectActions.delete(oldest);
    }
    return current;
  }

  // Offers the built-in actions when the project's cannot be loaded.
  private async offerActions(project: string): Promise<OfferedActions> {
    try {
      return {actions: offer(await this.fetchActions(project))};
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e);
      return {actions: BUILT_IN_ACTIONS, error};
    }
  }

  private async fetchSetup(project: string): Promise<ChatSetup> {
    const providers = await this.fetchEnabledProviders();
    if (providers.length === 0) return {providers, offered: {actions: []}};
    return {providers, offered: await this.offerActions(project)};
  }

  private loadSetup(change: ChangeInfo): Promise<ChatSetup> {
    const key = changeIdOf(change);
    if (this.setupRequest?.key === key) return this.setupRequest.output;
    const output = this.fetchSetup(change.project).finally(() => {
      if (this.setupRequest?.output === output) this.setupRequest = undefined;
    });
    this.setupRequest = {key, output};
    return output;
  }

  private async fetchEnabledProviders(): Promise<ProviderInfo[]> {
    // A provider is "enabled" only once a working API token is configured.
    const aiReviewProvidersOutput: GetAiProvidersOutput = await this.plugin
      .restApi()
      .get(AI_REVIEW_PROVIDERS_ENDPOINT);
    return aiReviewProvidersOutput.providers.filter(p => p.enabled);
  }

  async getModels(change: ChangeInfo): Promise<Models> {
    const {providers, offered} = await this.loadSetup(change);

    // Reject instead of returning empty models to avoid an endless spinner.
    if (providers.length === 0) {
      throw new Error('No API token configured. Add one in Settings.');
    }

    const providerModels = providers.flatMap(providerInfo =>
      providerInfo.models.map(modelName => {
        return {
          model_id: `${providerInfo.plugin}/${modelName}`,
          short_text: providerInfo.display_name,
          full_display_text: `${providerInfo.display_name} (${modelName})`,
        };
      }),
    );

    this.defaultModel = providerModels[0].model_id;

    const {actions} = offered;

    return {
      models: providerModels,
      default_model_id: this.defaultModel,
      documentation_url: 'https://ai.google.dev/api/generate-content',
      custom_actions: actions.map(toAction),
    };
  }

  async getActions(change: ChangeInfo): Promise<Actions> {
    const {providers, offered} = await this.loadSetup(change);

    // No enabled provider means getModels() rejects, so offer no actions.
    if (providers.length === 0) {
      return {actions: [], default_action_id: ''};
    }

    const {actions} = offered;
    return {
      actions: [...actions.map(toAction), FREE_FORM_ACTION],
      default_action_id: FREE_FORM_ACTION.id,
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

      const revision = change.current_revision ?? 'current';
      let context = DEFAULT_CONTEXT_LINES;
      let notice = '';
      if (sent && sent.id !== FREE_FORM_ACTION.id) {
        // Use the current definition, but only if it is still the one the
        // user started from.
        const current = await this.offerActions(change.project);
        const action = current.actions.find(a => a.id === sent.id);
        if (!action || !isUnchanged(sent, action)) {
          listener.emitError(
            current.error === undefined
              ? `The "${sent.display_text}" action was changed or removed ` +
                  'since this page was loaded. Copy your prompt if you ' +
                  'edited it, then reload the page.'
              : "The project's AI review actions could not be loaded, so " +
                  `"${sent.display_text}" was not sent: ${current.error}`,
          );
          listener.done();
          return;
        }
        context = action.context_lines;
        if (current.error) notice = fallbackNotice(current.error);
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
      listener.emitResponse(buildChatResponse(notice + normalizedText));
      listener.done();
    } catch (e) {
      listener.emitError(
        e instanceof Error ? e.message : 'Error fetching patch content',
      );
      listener.done();
    }
  }
}
