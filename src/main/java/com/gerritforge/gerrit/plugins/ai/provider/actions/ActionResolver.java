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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Computes the actions of a project: for each action id, the first file found in the project, its
 * parents and All-Projects is used as it is. Parents are walked as {@link ProjectState#tree()}
 * walks them for permissions.
 */
@Singleton
public class ActionResolver {
  /** Part of every version, so that a plugin upgrade, which may read files differently, shows. */
  private static final String BUILD =
      Strings.nullToEmpty(ActionResolver.class.getPackage().getImplementationVersion());

  private final ActionFilesCache filesCache;
  private final ActionLimits limits;

  @Inject
  ActionResolver(ActionFilesCache filesCache, ActionLimits limits) {
    this.filesCache = filesCache;
    this.limits = limits;
  }

  /**
   * Changes whenever the actions of the hierarchy may have changed: a new config revision of any of
   * its projects, a different parent or a plugin upgrade. Computed without reading any action file.
   */
  public static String version(ImmutableList<ProjectState> hierarchy) {
    Hasher hasher = Hashing.sha256().newHasher();
    putString(hasher, BUILD);
    for (ProjectState state : hierarchy) {
      putString(hasher, state.getName());
      putString(hasher, state.getConfig().getRevision().map(ObjectId::name).orElse(""));
    }
    return hasher.hash().toString();
  }

  /** Resolves the actions of {@code hierarchy.get(0)}, which is followed by its parents. */
  public EffectiveActions resolve(ImmutableList<ProjectState> hierarchy)
      throws ActionsLoadException, IOException {
    ProjectState project = hierarchy.get(0);
    Map<String, ActionDefinition> winners = new TreeMap<>();
    for (ProjectState state : hierarchy) {
      ActionFiles files = filesCache.get(state);
      if (files.error().isPresent()) {
        throw new ActionsLoadException(
            String.format("Project %s: %s", state.getName(), files.error().get()));
      }
      for (ActionFile file : files.byPath().values()) {
        // Invalid names are logged when the files are loaded; they never define an action.
        if (!ActionFileParser.isValidId(file.id()) || winners.containsKey(file.id())) {
          continue;
        }
        switch (file) {
          case ActionFile.Valid valid -> winners.put(valid.id(), valid.definition());
          case ActionFile.Invalid invalid ->
              throw new ActionsLoadException(
                  String.format(
                      "Invalid AI review action file %s in project %s: %s",
                      invalid.path(), state.getName(), invalid.error()));
        }
      }
    }
    return effective(project, winners);
  }

  private EffectiveActions effective(ProjectState project, Map<String, ActionDefinition> winners)
      throws ActionsLoadException {
    List<ActionDefinition.Enabled> actions = new ArrayList<>();
    ImmutableSet.Builder<String> disabledActionIds = ImmutableSet.builder();
    long chars = 0;
    for (ActionDefinition definition : winners.values()) {
      switch (definition) {
        case ActionDefinition.Enabled action -> {
          actions.add(action);
          chars += action.textLength();
        }
        case ActionDefinition.Disabled disabled -> disabledActionIds.add(disabled.id());
      }
    }
    if (actions.size() > limits.maxEnabledActions()) {
      throw new ActionsLoadException(
          String.format(
              "Project %s: %d AI review actions are enabled, more than the limit of %d",
              project.getName(), actions.size(), limits.maxEnabledActions()));
    }
    if (chars > limits.maxTotalChars()) {
      throw new ActionsLoadException(
          String.format(
              "Project %s: the labels and prompts of the enabled AI review actions are %d"
                  + " characters long, more than the limit of %d",
              project.getName(), chars, limits.maxTotalChars()));
    }
    return new EffectiveActions(ImmutableList.copyOf(actions), disabledActionIds.build());
  }

  private static void putString(Hasher hasher, String value) {
    hasher.putInt(value.length()).putString(value, UTF_8);
  }
}
