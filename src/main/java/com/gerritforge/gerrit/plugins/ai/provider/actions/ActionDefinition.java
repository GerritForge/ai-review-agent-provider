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
import com.google.gerrit.common.Nullable;

/**
 * One AI review action, parsed from an {@code ai-review/<id>.md} file.
 *
 * <p>A disabled action hides any action with the same id inherited from a parent project; its
 * {@code displayText} may then be {@code null} and its {@code prompt} empty.
 */
public record ActionDefinition(
    String id,
    boolean enabled,
    @Nullable String displayText,
    int contextLines,
    String prompt,
    ImmutableSet<String> unknownFields) {}
