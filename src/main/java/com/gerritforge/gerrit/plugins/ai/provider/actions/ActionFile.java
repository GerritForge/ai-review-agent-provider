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

import com.google.gerrit.common.Nullable;

/** An action file and either its definition or the reason it could not be parsed. */
public record ActionFile(
    String path, String id, @Nullable ActionDefinition definition, @Nullable String error) {

  static ActionFile parse(String path, String id, byte[] content) {
    try {
      return new ActionFile(path, id, ActionFileParser.parse(id, content), null);
    } catch (InvalidActionFileException e) {
      return new ActionFile(path, id, null, e.getMessage());
    }
  }

  static ActionFile tooLarge(String path, String id) {
    return new ActionFile(
        path,
        id,
        null,
        String.format("file is larger than %d bytes", ActionFileParser.MAX_FILE_BYTES));
  }

  /** Approximate memory, in characters, for the cache weigher. */
  int weight() {
    int weight = ActionFiles.ENTRY_WEIGHT + path.length();
    if (definition != null) {
      weight += definition.prompt().length();
      if (definition.displayText() != null) {
        weight += definition.displayText().length();
      }
    }
    return weight;
  }
}
