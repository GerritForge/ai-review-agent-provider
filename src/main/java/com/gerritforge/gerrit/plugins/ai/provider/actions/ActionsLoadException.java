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

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Project;

/** The effective AI review actions of a project cannot be computed. */
public class ActionsLoadException extends Exception {
  private static final long serialVersionUID = 1L;

  private final ImmutableSet<Project.NameKey> projects;

  /**
   * @param projects the projects the message names, so that callers can hide it from users who
   *     cannot see them
   */
  public ActionsLoadException(String message, Iterable<Project.NameKey> projects) {
    super(message);
    this.projects = ImmutableSet.copyOf(projects);
  }

  public ImmutableSet<Project.NameKey> projects() {
    return projects;
  }
}
