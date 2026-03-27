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

const {defineConfig} = require('eslint/config');

// eslint-disable-next-line no-undef
__plugindir = 'ai-review-agent-gemini/web';

const gerritEslint = require('../../eslint.config.js');

module.exports = defineConfig([
  {
    extends: [gerritEslint],
  },
]);
