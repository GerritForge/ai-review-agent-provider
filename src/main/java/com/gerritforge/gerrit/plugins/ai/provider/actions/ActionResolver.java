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

import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the actions of a project: for each action id, the first file found in the project, its
 * parents, All-Projects and then the bundled actions is used as it is. A disabled file hides the
 * action.
 */
@Singleton
public class ActionResolver {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final int MAX_ACTIONS = 200;

  private final ProjectCache projectCache;
  private final AllProjectsName allProjects;
  private final ActionFilesCache filesCache;

  @Inject
  ActionResolver(
      ProjectCache projectCache, AllProjectsName allProjects, ActionFilesCache filesCache) {
    this.projectCache = projectCache;
    this.allProjects = allProjects;
    this.filesCache = filesCache;
  }

  /** The action used for an id, and the project whose file defines it, if any. */
  private record Winner(ActionDefinition action, Optional<Project.NameKey> source) {}

  public EffectiveActions resolve(ProjectState project) throws ActionsLoadException, IOException {
    Map<String, Winner> winners = new HashMap<>();
    for (ProjectState state : hierarchy(project)) {
      ActionFiles files = filesCache.get(state);
      if (!files.errors().isEmpty()) {
        throw new ActionsLoadException(
            String.format("Project %s: %s", state.getName(), files.errors().get(0)),
            ImmutableSet.of(state.getNameKey()));
      }
      for (ActionFile file : files.byPath().values()) {
        if (!ActionFileParser.isValidId(file.id())) {
          logger.atWarning().atMostEvery(1, MINUTES).log(
              "Ignoring AI review action file %s in project %s: invalid file name",
              file.path(), state.getName());
          continue;
        }
        if (winners.containsKey(file.id())) {
          continue;
        }
        if (file.definition() == null) {
          throw new ActionsLoadException(
              String.format(
                  "Invalid AI review action file %s in project %s: %s",
                  file.path(), state.getName(), file.error()),
              ImmutableSet.of(state.getNameKey()));
        }
        winners.put(file.id(), new Winner(file.definition(), Optional.of(state.getNameKey())));
      }
    }
    BuiltInActions.get()
        .forEach((id, action) -> winners.putIfAbsent(id, new Winner(action, Optional.empty())));
    return effective(project, winners);
  }

  private static EffectiveActions effective(ProjectState project, Map<String, Winner> winners)
      throws ActionsLoadException {
    Set<String> order = new LinkedHashSet<>(BuiltInActions.IDS);
    winners.keySet().stream().sorted().forEach(order::add);

    List<ActionDefinition> actions = new ArrayList<>();
    Set<Project.NameKey> sources = new LinkedHashSet<>();
    for (String id : order) {
      Winner winner = winners.get(id);
      if (winner.action().enabled()) {
        actions.add(winner.action());
        winner.source().ifPresent(sources::add);
      }
    }
    if (actions.size() > MAX_ACTIONS) {
      throw new ActionsLoadException(
          String.format(
              "Project %s: %d AI review actions are enabled, more than the limit of %d",
              project.getName(), actions.size(), MAX_ACTIONS),
          ImmutableSet.of(project.getNameKey()));
    }

    Optional<String> defaultActionId =
        actions.stream()
            .map(ActionDefinition::id)
            .filter(BuiltInActions.REVIEW_CHANGE::equals)
            .findFirst()
            .or(() -> actions.stream().map(ActionDefinition::id).findFirst());
    return new EffectiveActions(
        ImmutableList.copyOf(actions), defaultActionId, ImmutableSet.copyOf(sources));
  }

  /**
   * Returns the project followed by its parents up to All-Projects, where the walk stops even if
   * All-Projects names a parent. Unlike {@link ProjectState#tree()}, a missing parent or a cycle is
   * an error instead of a silent fallback to All-Projects, which would apply actions the project
   * never inherited.
   */
  private List<ProjectState> hierarchy(ProjectState project) throws ActionsLoadException {
    List<ProjectState> chain = new ArrayList<>();
    Set<Project.NameKey> seen = new LinkedHashSet<>();
    ProjectState current = project;
    while (true) {
      if (!seen.add(current.getNameKey())) {
        throw new ActionsLoadException(
            "Cycle in the parents of project " + project.getName() + ": " + seen, seen);
      }
      chain.add(current);
      if (current.getNameKey().equals(allProjects)) {
        return chain;
      }
      Project.NameKey parent = current.getProject().getParent(allProjects);
      if (parent == null) {
        return chain;
      }
      Optional<ProjectState> parentState = projectCache.get(parent);
      if (parentState.isEmpty()) {
        throw new ActionsLoadException(
            String.format(
                "Parent project %s of project %s does not exist", parent, current.getName()),
            ImmutableSet.of(current.getNameKey()));
      }
      current = parentState.get();
    }
  }
}
