// Copyright (C) 2026 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.gerrit.plugins.ai.provider.actions;

/** One AI review action, parsed from an {@code ai-review/<id>.md} file. */
public sealed interface ActionDefinition {
  String id();

  record Enabled(String id, String displayText, int contextLines, String prompt)
      implements ActionDefinition {
    /** Characters of the label and the prompt, as the limits count them. */
    public int textLength() {
      return displayText.length() + prompt.length();
    }
  }

  /** Hides an action with the same id inherited from a parent or built into a client. */
  record Disabled(String id) implements ActionDefinition {}
}
