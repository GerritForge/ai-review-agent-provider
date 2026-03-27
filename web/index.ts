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

import {css, html, LitElement} from 'lit';
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
import {
  HELP_ME_REVIEW_PROMPT,
  IMPROVE_COMMIT_MESSAGE,
} from './prompts';

const DEFAULT_MODEL = 'gemini-2.5-flash';
const TOKEN_ENDPOINT = '/accounts/self/ai-review-agent-provider~apiToken';

type TokenFetchResult =
  | { token: string }
  | { error: string };

type TokenResponse = {token: string};

function isTokenResponse(r: unknown): r is TokenResponse {
  return (
    typeof r === 'object' &&
    r !== null &&
    'token' in r &&
    typeof (r as any).token === 'string'
  );
}
async function fetchApiKeyFromBackend(
  plugin: PluginApi
): Promise<TokenFetchResult> {
  try {
    const res = await plugin.restApi().get(TOKEN_ENDPOINT);

    if (!isTokenResponse(res)) {
      return {error: 'Unexpected response from token endpoint'};
    }

    return {token: res.token.trim()};
  } catch (e) {
    return {
      error: `Cannot retrieve Gemini API key. ${e}`,
    };
  }
}

async function requireApiKey(
  plugin: PluginApi,
  listener: ChatResponseListener
): Promise<string | null> {
  const r = await fetchApiKeyFromBackend(plugin);
  if ('token' in r) return r.token;

  listener.emitError(r.error);
  listener.done();
  return null;
}

function buildChatResponse(text: string): ChatResponse {
  return {
    response_parts: [{id: 0, text}],
    references: [], // TODO: populate references
    citations: [], // TODO: populate citations
    timestamp_millis: Date.now(),
  };
}

async function callGeminiGenerateContent(args: {
  apiKey: string;
  model: string;
  prompt: string;
}): Promise<string> {
  const {apiKey, model, prompt} = args;

  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/` +
    `${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(
      apiKey
    )}`;

  const body = {
    contents: [
      {
        role: 'user',
        parts: [{text: prompt}],
      },
    ],
  };

  const res = await fetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    throw new Error(
      `Gemini error ${res.status}: ${errText || res.statusText}`
    );
  }

  const json: any = await res.json();

  // The response from the API looks like:
  //  {
  //   "candidates": [
  //     {
  //       "content": {
  //         "parts": [
  //           { "text": "Hello " },
  //           { "text": "world" }
  //         ]
  //       }
  //     }
  //   ]
  // }
  //
  // The following code take the first candidate, extract all text parts, ignore anything weird or missing,
  // and glue the text together into a single string.
  const parts: any[] =
     json?.candidates?.[0]?.content?.parts ?? [];

  const text = parts
    .map(p => (typeof p?.text === 'string' ? p.text : ''))
    .filter(Boolean)
    .join('');

  return text || '(No text returned by Gemini)';
}

class GeminiAiProvider implements AiCodeReviewProvider {
  // The provider does not support the "add extra context" feature (e.g., attaching additional files or notes beyond what Gerrit already sends)
  supports_add_context = false;
  // The provider does not maintain or accept chat history. Each request is treated as a fresh, stateless call
  supports_history = false;
  // The provider doesn’t expose extra "more" actions beyond the ones defined in getActions()
  supports_more_menu = false;
  //  The provider can operate on the current change (e.g., review/explain the active Gerrit change)
  supports_this_change = true;

  plugin: PluginApi;

  constructor(plugin: PluginApi) {
      this.plugin = plugin;
  }

  async getModels(): Promise<Models> {
    return {
      models: [
        {
          model_id: DEFAULT_MODEL,
          short_text: 'Gemini',
          full_display_text: `Gemini (${DEFAULT_MODEL})`,
        },
      ],
      default_model_id: DEFAULT_MODEL,
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
    console.log('Gemini Plugin: getActions called');
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
    listener: ChatResponseListener
  ): Promise<void> {
    const apiKey = await requireApiKey(this.plugin, listener);
    if (!apiKey) return;

    listener.emitResponse(buildChatResponse('_Gathering file contents and calling Gemini...'));

    try {
      const changeId = req.change?._number;
      // We'll take the first 10 files to avoid hitting token limits or browser timeouts
      const filesToReview = (req.files || []).slice(0, 10);
      const patchPlaceholder = "{{patch}}";

      let diffContext = '';

      for (const file of filesToReview) {
        if (file.path === '/COMMIT_MSG') continue;

        // Fetch the diff from Gerrit REST API
        // Endpoint: /changes/{change-id}/revisions/current/files/{file-id}/diff
        const diff: DiffInfo = await this.plugin.restApi().get(
          `/changes/${changeId}/revisions/current/files/${encodeURIComponent(file.path)}/diff?context=ALL`
        );

        // Extract the 'after' lines (the new code)
        const content = diff.content
          .map((c: any) => c.ab || c.b) // 'ab' is unchanged, 'b' is new content
          .flat()
          .join('\n');

        diffContext += `\n--- File: ${file.path} ---\n${content}\n`;
      }

      const prompt = req.prompt.includes(patchPlaceholder) ?
        req.prompt.replace(patchPlaceholder, diffContext) :
        `${req.prompt}\n\n` +
        `Context: This is a code review for change ${changeId}.\n` +
        `Code Content:\n${diffContext}`;
      const model = req.model_name || DEFAULT_MODEL;
      const text = await callGeminiGenerateContent({apiKey, model, prompt});

      listener.emitResponse(buildChatResponse(text));
      listener.done();
    } catch (e) {
      listener.emitError(e instanceof Error ? e.message : 'Error fetching patch content');
      listener.done();
    }
  }
}

@customElement('gr-gemini-api-token')
class GrGeminiApiToken extends LitElement {
  @property({ type: Object }) plugin!: PluginApi;

  @state() private token = '';
  @state() private saving = false;

  override firstUpdated() {
    this.loadToken();
  }

  async loadToken() {
    try {
      const res = await fetchApiKeyFromBackend(this.plugin);
      if ('token' in res) {
        this.token = res.token;
      }
    } catch (e) {
      console.warn('Gemini token not set yet');
    }
  }

  async saveToken() {
    this.saving = true;
    try {
      await this.plugin.restApi().put(TOKEN_ENDPOINT, {
        token: this.token.trim()
      });
    } finally {
      this.saving = false;
    }
  }

  static override get styles() {
    return css`
      section {
        display: flex;
        margin-bottom: var(--spacing-m);
      }
      .value {
        display: flex;
        align-items: center;
        gap: var(--spacing-m);
        color: var(--primary-text-color);
      }
      fieldset {
        border: 0px;
        margin: 0px;
        padding: 0px;
      }
      .gr-form-styles .title {
          color: var(--deemphasized-text-color);
          font-weight: var(--font-weight-medium);
          padding-right: var(--spacing-m);
          width: 15em;
      }
      md-outlined-text-field {
        width: 15em;
        background-color: var(--view-background-color);
        color: var(--primary-text-color);
        --md-sys-color-primary: var(--primary-text-color);
        --md-sys-color-on-surface: var(--primary-text-color);
        --md-sys-color-on-surface-variant: var(--deemphasized-text-color);
        --md-outlined-text-field-label-text-color: var(--deemphasized-text-color);
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
      md-outlined-text-field.showBlueFocusBorder {
        --md-outlined-text-field-focus-outline-width: 2px;
        --md-outlined-text-field-focus-outline-color: var(
          --input-focus-border-color
        );
      }
    `;
  }

  override render() {
    return html`
        <fieldset id="gemini-preferences">
          <div id="gemini-preferences" class="gr-form-styles">
            <section>
              <label class="title" for="geminiToken">Gemini API Token</label>
              <span class="value">
                <md-outlined-text-field
                  type="password"
                  id="geminiToken"
                  class="showBlueFocusBorder"
                  .value=${this.token}
                  ?disabled=${this.saving}
                  @input=${(e: Event) => (this.token = (e.target as HTMLInputElement).value)}
                ></md-outlined-text-field>
                <gr-button @click=${this.saveToken} ?disabled=${this.saving}>
                  Save Token
                </gr-button>
              </span>
            </section>
          </div>
        </fieldset>
    `;
  }
}

// TypeScript's strict build used by Gerrit enables `noUnusedLocals`, which
// triggers TS6196 if a symbol is declared but not referenced in the module.
// The custom element is actually used through the `@customElement` decorator
// and by `plugin.registerCustomComponent()`, but the TypeScript compiler
// cannot detect that usage statically.
//
// We reference the symbol with `void GrGeminiApiToken;`, which
// marks it as "used".
void GrGeminiApiToken;

function install(plugin: PluginApi) {
  const provider = new GeminiAiProvider(plugin);

  // Override the chat method to pass the plugin instance
  provider.chat = (req, listener) => {
    // @ts-ignore - TODO: reaching into private, there mught might be better way of doing it
    provider.chatAsync(req, listener);
  };

  plugin.aiCodeReview().register(provider);
  plugin.registerCustomComponent('profile', 'gr-gemini-api-token');
}

window.Gerrit.install(install);
