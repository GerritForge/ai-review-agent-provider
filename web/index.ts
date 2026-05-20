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
  AiCodeReviewProvider,
  Actions,
  ChatRequest,
  ChatResponse,
  ChatResponseListener,
  Models,
} from '@gerritcodereview/typescript-api/ai-code-review';
import type {DiffInfo} from '@gerritcodereview/typescript-api/diff';
import {HELP_ME_REVIEW_PROMPT, IMPROVE_COMMIT_MESSAGE} from './prompts';

const TOKEN_ENDPOINT = '/accounts/self/ai-review-agent-provider~apiToken';
const AI_REVIEW_PROVIDERS_ENDPOINT =
  '/accounts/self/ai-review-agent-provider~apiProviders';

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
    return (
      `⚠\uFE0F **Rate limit** ⚠\uFE0F\n\n` +
      `Model \`${model}\` is temporarily rate-limited by the upstream ` +
      `provider. Try again shortly, or pick a different model from the list.`
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
    const aiReviewProvidersOutput: GetAiProvidersOutput = await this.plugin
      .restApi()
      .get(AI_REVIEW_PROVIDERS_ENDPOINT);
    const aiReviewProviders = aiReviewProvidersOutput.providers.filter(
      p => p.enabled,
    );

    if (aiReviewProviders.length === 0) {
      return {
        models: [],
        default_model_id: '',
        documentation_url: 'https://ai.google.dev/api/generate-content',
        custom_actions: [],
      };
    }

    const providerModels = aiReviewProviders.flatMap(providerInfo =>
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
      custom_actions: [
        {
          id: 'review-change',
          display_text: 'Help me with review',
          enable_send_without_input: true,
          initial_user_prompt: HELP_ME_REVIEW_PROMPT,
        },
        {
          id: 'review-commit',
          display_text: 'Improve commit message',
          enable_send_without_input: true,
          initial_user_prompt: IMPROVE_COMMIT_MESSAGE,
        },
      ],
    };
  }

  getActions(): Promise<Actions> {
    return Promise.resolve({
      actions: [
        {
          id: 'review-change',
          display_text: 'Help me with review',
          enable_send_without_input: true,
          initial_user_prompt: HELP_ME_REVIEW_PROMPT,
        },
        {
          id: 'review-commit',
          display_text: 'Improve commit message',
          enable_send_without_input: true,
          initial_user_prompt: IMPROVE_COMMIT_MESSAGE,
        },
      ],
      default_action_id: 'review',
    });
  }

  chat(req: ChatRequest, listener: ChatResponseListener): void {
    void this.chatAsync(req, listener);
  }

  private async chatAsync(
    req: ChatRequest,
    listener: ChatResponseListener,
  ): Promise<void> {
    listener.emitResponse(
      buildChatResponse('_Gathering file contents and calling AI model..._'),
    );

    try {
      const changeId = `${req.change?.project}~${req.change?._number}`;
      // We'll take the first 10 files to avoid hitting token limits or browser timeouts
      const filesToReview = (req.files || []).slice(0, 10);
      const patchPlaceholder = '{{patch}}';

      let diffContext = '';

      for (const file of filesToReview) {
        if (file.path === '/COMMIT_MSG') continue;

        // Fetch the diff from Gerrit REST API
        // Endpoint: /changes/{change-id}/revisions/current/files/{file-id}/diff
        const diff: DiffInfo = await this.plugin
          .restApi()
          .get(
            `/changes/${changeId}/revisions/current/files/${encodeURIComponent(
              file.path,
            )}/diff?context=ALL`,
          );

        // Extract the 'after' lines (the new code)
        const content = (diff.content ?? [])
          .map((c: {ab?: string[]; b?: string[]}) => c.ab ?? c.b ?? [])
          .flat()
          .join('\n');

        diffContext += `\n--- File: ${file.path} ---\n${content}\n`;
      }

      const prompt = req.prompt.includes(patchPlaceholder)
        ? req.prompt.replace(patchPlaceholder, diffContext)
        : `${req.prompt}\n\n` +
          `Context: This is a code review for change ${changeId}.\n` +
          `Code Content:\n${diffContext}`;
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
  plugin.registerCustomComponent('profile', 'gr-ai-api-token');
}

window.Gerrit.install(install);
