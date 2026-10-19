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
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Project;
import java.util.Optional;

/**
 * The enabled actions of a project, in display order, and the action selected by default.
 *
 * @param sources the projects whose files define the returned actions; bundled actions have none
 */
public record EffectiveActions(
    ImmutableList<ActionDefinition> actions,
    Optional<String> defaultActionId,
    ImmutableSet<Project.NameKey> sources) {}
