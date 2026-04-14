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

const fs = require('fs');
const path = require('path');
const {defineConfig} = require('eslint/config');

global.__plugindir = 'ai-review-agent-provider/web';

const candidates = [
  // Direct eslint from Gerrit root
  path.join(process.cwd(), 'plugins', 'eslint.config.js'),
  // Bazel lint_test / lint_bin from plugin web cwd
  path.resolve(process.cwd(), '../../eslint.config.js'),
  // Normal non-symlink checkout inside Gerrit tree
  path.resolve(__dirname, '../../eslint.config.js'),
];

const gerritEslintPath = candidates.find(p => fs.existsSync(p));

if (!gerritEslintPath) {
  throw new Error(
      `Cannot locate eslint.config.js. Tried:\n${candidates.join('\n')}`
  );
}

const gerritEslint = require(gerritEslintPath);

module.exports = defineConfig([
  {
    extends: [gerritEslint],
  },
]);
