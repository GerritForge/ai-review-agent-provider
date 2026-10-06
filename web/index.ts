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
import {
  AI_REVIEW_PROVIDERS_ENDPOINT,
  AiCodeReviewProviderImpl,
  GetAiProvidersOutput,
  ProviderInfo,
} from './ai-code-review-provider';

const TOKEN_ENDPOINT = '/accounts/self/ai-review-agent-provider~apiToken';

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
