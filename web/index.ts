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

import {css, html, LitElement, nothing, PropertyValues} from 'lit';
import {customElement, property, state} from 'lit/decorators.js';
import '@gerritcodereview/typescript-api/gerrit';
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
import {ModelsLoadingErrorName} from '@gerritcodereview/typescript-api/ai-code-review';
import {HttpMethod} from '@gerritcodereview/typescript-api/rest';
import type {FixId} from '@gerritcodereview/typescript-api/rest-api';
import type {
  SuggestCodeRequest,
  SuggestedFixResponse,
  SuggestionsProvider,
} from '@gerritcodereview/typescript-api/suggestions';
import {ResponseCode} from '@gerritcodereview/typescript-api/suggestions';
import {
  buildFixPrompt,
  InvalidFixError,
  splitLines,
  toFixReplacements,
} from './fix-suggestion';
import {HELP_ME_REVIEW_PROMPT, IMPROVE_COMMIT_MESSAGE} from './prompts';

const TOKEN_ENDPOINT = '/accounts/self/ai-review-agent-provider~apiToken';
const AI_REVIEW_PROVIDERS_ENDPOINT =
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

declare interface ProviderInfo {
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

declare interface GetAiProvidersOutput {
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

async function fetchEnabledProviders(
  plugin: PluginApi,
): Promise<ProviderInfo[]> {
  // A provider is "enabled" only once a working API token is configured.
  const aiReviewProvidersOutput: GetAiProvidersOutput = await plugin
    .restApi()
    .get(AI_REVIEW_PROVIDERS_ENDPOINT);
  return aiReviewProvidersOutput.providers.filter(p => p.enabled);
}

async function callAiModel(args: {
  pluginApi: PluginApi;
  changeId: string;
  model: string;
  prompt: string;
}): Promise<AiCodeReviewOutput> {
  const {pluginApi, changeId, model, prompt} = args;
  const url = `/changes/${changeId}/ai-review-agent-provider~aiReview`;
  const separatorIdx = model.indexOf('/');
  return pluginApi.restApi().post(url, {
    plugin: model.slice(0, separatorIdx),
    model: model.slice(separatorIdx + 1),
    prompt,
  });
}

async function callAiModelAndGenerateContent(args: {
  pluginApi: PluginApi;
  changeId: string;
  model: string;
  prompt: string;
}): Promise<string> {
  const res = await callAiModel(args);
  if (res.text) return res.text;
  if (!res.error) return '(No text returned by AI)';
  return formatAiError(res.error, args.model);
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

class AiCodeReviewProviderImpl implements AiCodeReviewProvider {
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

  async getModels(): Promise<Models> {
    let aiEnabledProviders: ProviderInfo[];
    try {
      aiEnabledProviders = await fetchEnabledProviders(this.plugin);
    } catch (e) {
      // The raw REST error (e.g. "Error 500 (Internal Server Error): ...")
      // means nothing to the user, so keep it in the console only.
      console.error('Failed to fetch the AI providers', e);
      const error = new Error(
        'Failed to load the AI providers. Please reload the page.',
      );
      error.name = ModelsLoadingErrorName.AI_PROVIDERS_LOADING;
      throw error;
    }

    // Reject instead of returning empty models to avoid an endless spinner.
    if (aiEnabledProviders.length === 0) {
      const error = new Error('No API token configured. Add one in Settings.');
      error.name = ModelsLoadingErrorName.MISSING_API_TOKEN;
      throw error;
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
    const aiEnabledProviders = await fetchEnabledProviders(this.plugin);

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

function noFix(responseCode: ResponseCode): SuggestedFixResponse {
  return {responseCode, fix_suggestions: []};
}

function randomFixId(): FixId {
  // crypto.randomUUID() is unavailable on non-HTTPS Gerrit sites.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  // FixId is a branded string type: any unique string is a valid id.
  const fixId = hex as FixId;
  return fixId;
}

function decodeBase64Utf8(base64: string): string {
  const binary = atob(base64.trim());
  const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
  // fatal: binary files must not be turned into garbage text.
  return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
}

/**
 * Backs Gerrit's "Get AI Fix" comment action: asks the AI model to address
 * the comment and returns the edit as a fix suggestion, which the user can
 * preview and apply as a change edit.
 */
class AiSuggestionsProviderImpl implements SuggestionsProvider {
  constructor(private readonly plugin: PluginApi) {}

  getFixSuggestionTitle(): string {
    return 'Fix with AI';
  }

  async suggestFix(req: SuggestCodeRequest): Promise<SuggestedFixResponse> {
    try {
      return await this.suggestFixOrThrow(req);
    } catch (e) {
      console.error('Failed to generate the AI fix', e);
      return noFix(
        e instanceof InvalidFixError
          ? ResponseCode.INVALID_RANGE
          : ResponseCode.ERROR,
      );
    }
  }

  private async suggestFixOrThrow(
    req: SuggestCodeRequest,
  ): Promise<SuggestedFixResponse> {
    const providers = await fetchEnabledProviders(this.plugin);
    const provider = providers.find(p => p.models.length > 0);
    if (!provider) {
      console.warn('AI fix unavailable: no API token configured');
      return noFix(ResponseCode.ERROR);
    }
    const modelName = provider.models[0];
    const model = `${provider.plugin}/${modelName}`;

    const changeId = `${encodeURIComponent(req.changeInfo.project)}~${
      req.changeInfo._number
    }`;
    const contentUrl =
      `/changes/${changeId}/revisions/${req.patchsetNumber}` +
      `/files/${encodeURIComponent(req.filePath)}/content`;
    const res = await this.plugin.restApi().fetch(HttpMethod.GET, contentUrl);
    if (!res.ok) {
      throw new Error(`Failed to fetch ${req.filePath} (HTTP ${res.status})`);
    }
    let content: string;
    try {
      content = decodeBase64Utf8(await res.text());
    } catch (e) {
      console.warn(`AI fix unavailable: ${req.filePath} is not text`, e);
      return noFix(ResponseCode.UNSUPPORTED_FILE_TYPE);
    }
    if (content.length > MAX_PATCH_CHARS) {
      return noFix(ResponseCode.FILE_TOO_LARGE);
    }

    const file = splitLines(content);
    const out = await callAiModel({
      pluginApi: this.plugin,
      changeId,
      model,
      prompt: buildFixPrompt({
        filePath: req.filePath,
        file,
        comment: req.prompt,
        range: req.range,
        lineNumber: req.lineNumber,
      }),
    });
    if (out.error) {
      throw new Error(formatAiError(out.error, model));
    }

    const replacements = toFixReplacements(out.text ?? '', req.filePath, file);
    if (replacements.length === 0) return noFix(ResponseCode.NO_SUGGESTION);
    return {
      responseCode: ResponseCode.OK,
      fix_suggestions: [
        {
          fix_id: randomFixId(),
          description: `${provider.display_name} (${modelName})`,
          replacements,
        },
      ],
    };
  }
}

@customElement('gr-ai-api-token')
class GrAiApiToken extends LitElement {
  @property({type: Object}) plugin!: PluginApi;

  @state() private providersOutput: GetAiProvidersOutput = {providers: []};

  @state() private provider = '';

  @state() private activeProviders: ProviderInfo[] = [];

  @state() private inactiveProviders: ProviderInfo[] = [];

  @state() private token = '';

  @state() private saving = false;

  override willUpdate(changedProperties: PropertyValues) {
    if (changedProperties.has('plugin')) {
      void this.loadProviders();
    }
  }

  async loadProviders() {
    this.providersOutput = await this.plugin
      .restApi()
      .get(AI_REVIEW_PROVIDERS_ENDPOINT);

    const providers = this.providersOutput.providers;
    this.activeProviders = providers.filter(p => p.enabled);
    this.inactiveProviders = providers.filter(p => !p.enabled);

    if (this.inactiveProviders.length > 0) {
      this.provider = this.inactiveProviders[0].plugin;
    }
  }

  async saveToken() {
    this.saving = true;
    try {
      await this.plugin.restApi().put(TOKEN_ENDPOINT, {
        plugin: this.provider,
        token: this.token.trim(),
      });
      this.token = '';
      await this.loadProviders();
    } finally {
      this.saving = false;
    }
  }

  static override get styles() {
    return css`
      th {
        text-align: left;
      }
      td.modelColumn,
      td.valueColumn {
        width: 15em;
      }
      h2 {
        font-family: var(--header-font-family);
        font-size: var(--font-size-h2);
        font-weight: var(--font-weight-h2);
        line-height: var(--line-height-h2);
      }
      fieldset {
        border: 0px;
        margin: 0px;
        padding: 0px;
      }
      md-outlined-text-field,
      gr-search-autocomplete,
      md-outlined-select {
        --md-outlined-field-top-space: 4px;
        --md-outlined-field-bottom-space: 4px;
      }
      md-outlined-text-field {
        width: 15em;
        background-color: var(--view-background-color);
        color: var(--primary-text-color);
        --md-sys-color-primary: var(--primary-text-color);
        --md-sys-color-on-surface: var(--primary-text-color);
        --md-sys-color-on-surface-variant: var(--deemphasized-text-color);
        --md-outlined-text-field-label-text-color: var(
          --deemphasized-text-color
        );
        --md-outlined-text-field-focus-label-text-color: var(
          --deemphasized-text-color
        );
        --md-outlined-text-field-hover-label-text-color: var(
          --deemphasized-text-color
        );
        --md-outlined-text-field-container-shape: var(--border-radius);
        --md-outlined-text-field-focus-outline-color: var(
          --prominent-border-color,
          var(--border-color)
        );
        --md-outlined-text-field-outline-color: var(
          --prominent-border-color,
          var(--border-color)
        );
        --md-outlined-text-field-hover-outline-color: var(
          --prominent-border-color,
          var(--border-color)
        );
        --md-sys-color-outline: var(
          --prominent-border-color,
          var(--border-color)
        );
        --_top-space: 4px;
        --_bottom-space: 4px;
      }
    `;
  }

  override render() {
    return html`
      <div class="gr-form-styles">
        <h2>AI Models and API Keys</h2>
        <fieldset id="ai-tokens">
          <table>
            <thead>
              <tr>
                <th>Model</th>
                <th>Key</th>
                <th aria-label="Actions"></th>
              </tr>
            </thead>
            <tbody>
              ${this.activeProviders.map(p => this.renderActiveProvider(p))}
              ${this.renderInactiveProviders()}
            </tbody>
          </table>
        </fieldset>
      </div>
    `;
  }

  private renderActiveProvider(provider: ProviderInfo) {
    return html`
      <tr>
        <td class="modelColumn">${provider.display_name}</td>
        <td class="valueColumn">
          <span>**********************************</span>
        </td>
        <td class="actionColumn">
          <gr-button
            link
            @click=${() => this.deleteToken(provider.plugin)}
            ?disabled=${this.saving}
            >Delete</gr-button
          >
        </td>
      </tr>
    `;
  }

  private renderInactiveProviders() {
    if (this.inactiveProviders.length === 0) return nothing;
    return html`
      <tr>
        <td>
          <md-outlined-select
            .value=${this.provider}
            @input=${(e: Event) =>
              (this.provider = (e.target as HTMLInputElement).value)}
          >
            ${this.inactiveProviders.map(p => this.renderProvider(p))}
          </md-outlined-select>
        </td>
        <td>
          <md-outlined-text-field
            type="password"
            id="aiToken"
            .value=${this.token}
            ?disabled=${this.saving}
            @input=${(e: Event) =>
              (this.token = (e.target as HTMLInputElement).value)}
          ></md-outlined-text-field>
        </td>
        <td>
          <gr-button link @click=${this.saveToken} ?disabled=${this.saving}
            >Add</gr-button
          >
        </td>
      </tr>
    `;
  }

  private renderProvider(provider: ProviderInfo) {
    return html`
      <md-select-option value=${provider.plugin}>
        <div slot="headline" class="providerName">${provider.display_name}</div>
      </md-select-option>
    `;
  }

  private async deleteToken(plugin: string) {
    this.saving = true;
    try {
      await this.plugin
        .restApi()
        .delete(`${AI_REVIEW_PROVIDERS_ENDPOINT}/${plugin}/apiToken`);
      await this.loadProviders();
    } finally {
      this.saving = false;
    }
  }
}

// TypeScript's strict build used by Gerrit enables `noUnusedLocals`, which
// triggers TS6196 if a symbol is declared but not referenced in the module.
// The custom element is actually used through the `@customElement` decorator
// and by `plugin.registerCustomComponent()`, but the TypeScript compiler
// cannot detect that usage statically.
//
// We reference the symbol with `void GrAiApiToken;`, which
// marks it as "used".
void GrAiApiToken;

function install(plugin: PluginApi) {
  const provider = new AiCodeReviewProviderImpl(plugin);

  // Override the chat method to pass the plugin instance
  provider.chat = (req, listener) => {
    // @ts-ignore - TODO: reaching into private, there mught might be better way of doing it
    void provider.chatAsync(req, listener);
  };

  plugin.aiCodeReview().register(provider);
  plugin.suggestions().register(new AiSuggestionsProviderImpl(plugin));
  plugin.registerCustomComponent('profile', 'gr-ai-api-token');
}

window.Gerrit.install(install);
