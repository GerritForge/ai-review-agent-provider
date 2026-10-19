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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;

/**
 * Actions bundled with the plugin. They apply wherever no project in the hierarchy has a file with
 * the same id.
 *
 * <p>Their prompts are Copyright 2025 Google LLC, licensed under Apache-2.0, as stated in the
 * comment at the top of each file.
 */
public class BuiltInActions {
  public static final String REVIEW_CHANGE = "review-change";
  public static final ImmutableList<String> IDS =
      ImmutableList.of(REVIEW_CHANGE, "review-change-full", "review-commit");

  private static final ImmutableMap<String, ActionDefinition> ACTIONS = load();

  public static ImmutableMap<String, ActionDefinition> get() {
    return ACTIONS;
  }

  private static ImmutableMap<String, ActionDefinition> load() {
    ImmutableMap.Builder<String, ActionDefinition> actions = ImmutableMap.builder();
    for (String id : IDS) {
      String path = ActionFileParser.FOLDER + id + ActionFileParser.EXTENSION;
      try (InputStream in = BuiltInActions.class.getClassLoader().getResourceAsStream(path)) {
        if (in == null) {
          throw new IllegalStateException("Missing bundled AI review action " + path);
        }
        actions.put(id, ActionFileParser.parse(id, in.readAllBytes()));
      } catch (IOException | InvalidActionFileException e) {
        throw new IllegalStateException("Invalid bundled AI review action " + path, e);
      }
    }
    return actions.buildOrThrow();
  }

  private BuiltInActions() {}
}
