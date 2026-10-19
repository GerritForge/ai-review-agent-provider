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

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;

/**
 * Checks the action files an update of {@code refs/meta/config} adds or modifies. Files it leaves
 * as they were are not checked, so deleting or keeping an invalid file is always possible.
 */
public class ActionFilesCheck {
  private static final int MAX_LISTED_ERRORS = 20;

  public record Result(ImmutableList<String> errors, ImmutableList<String> warnings) {
    public static final Result OK = new Result(ImmutableList.of(), ImmutableList.of());
  }

  private record ChangedFile(String path, String id, ObjectId blob) {}

  /**
   * @param oldTrees the trees before the update: the parents of a new commit, or the old tip of the
   *     ref, if any
   */
  public static Result check(
      ObjectReader reader, List<ObjectId> oldTrees, ObjectId newTree, ActionLimits limits)
      throws IOException {
    int parents = oldTrees.size();
    int[] oldCounts = new int[parents];
    int newCount = 0;
    List<ChangedFile> changed = new ArrayList<>();
    // Counts first and reads no file yet, so that an update with too many files is cheap.
    try (TreeWalk walk = new TreeWalk(reader)) {
      for (ObjectId tree : oldTrees) {
        walk.addTree(ActionFiles.folder(reader, tree));
      }
      walk.addTree(ActionFiles.folder(reader, newTree));
      walk.setFilter(PathSuffixFilter.create(ActionFileParser.EXTENSION));
      while (walk.next()) {
        boolean isNew = ActionFiles.isFile(walk, parents);
        boolean fromParent = false;
        for (int i = 0; i < parents; i++) {
          if (ActionFiles.isFile(walk, i)) {
            oldCounts[i]++;
            fromParent |= isNew && walk.idEqual(i, parents);
          }
        }
        if (!isNew) {
          continue;
        }
        newCount++;
        // A merge changes only files that differ from every parent.
        if (!fromParent && changed.size() <= limits.maxFiles()) {
          changed.add(
              new ChangedFile(
                  ActionFileParser.FOLDER + walk.getPathString(),
                  ActionFiles.id(walk),
                  walk.getObjectId(parents)));
        }
      }
    }
    int oldCount = 0;
    for (int count : oldCounts) {
      oldCount = Math.max(oldCount, count);
    }
    if (newCount > limits.maxFiles() && newCount > oldCount) {
      return error(
          String.format(
              "%s holds %d action files, more than the limit of %d",
              ActionFileParser.FOLDER, newCount, limits.maxFiles()));
    }
    if (changed.size() > limits.maxFiles()) {
      return error(
          String.format(
              "an update must not add or modify more than %d action files", limits.maxFiles()));
    }

    List<String> errors = new ArrayList<>();
    ImmutableList.Builder<String> warnings = ImmutableList.builder();
    for (ChangedFile file : changed) {
      if (reader.getObjectSize(file.blob(), OBJ_BLOB) > limits.maxFileBytes()) {
        errors.add(file.path() + ": " + ActionFiles.tooLarge(limits.maxFileBytes()));
        continue;
      }
      try {
        ActionFileParser.Parsed parsed =
            ActionFileParser.parseWithUnknownFields(
                file.id(), reader.open(file.blob(), OBJ_BLOB).getCachedBytes());
        if (!parsed.unknownFields().isEmpty()) {
          warnings.add(
              String.format(
                  "%s: unknown fields are ignored: %s",
                  file.path(), String.join(", ", parsed.unknownFields())));
        }
      } catch (InvalidActionFileException e) {
        errors.add(file.path() + ": " + e.getMessage());
      }
    }
    if (errors.isEmpty() && !changed.isEmpty()) {
      ownTextLengthError(reader, newTree, limits).ifPresent(errors::add);
    }
    return new Result(listed(errors), warnings.build());
  }

  /**
   * The project's own enabled actions always win in the project, so their labels and prompts alone
   * must stay within the limit.
   */
  private static Optional<String> ownTextLengthError(
      ObjectReader reader, ObjectId newTree, ActionLimits limits) throws IOException {
    ActionFiles files = ActionFiles.read(reader, newTree, limits);
    long chars = 0;
    for (ActionFile file : files.byPath().values()) {
      if (file instanceof ActionFile.Valid valid
          && valid.definition() instanceof ActionDefinition.Enabled action) {
        chars += action.textLength();
      }
    }
    if (files.error().isPresent() || chars <= limits.maxTotalChars()) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "the labels and prompts of the enabled actions in %s are %d characters long, more"
                + " than the limit of %d",
            ActionFileParser.FOLDER, chars, limits.maxTotalChars()));
  }

  private static ImmutableList<String> listed(List<String> errors) {
    if (errors.size() <= MAX_LISTED_ERRORS) {
      return ImmutableList.copyOf(errors);
    }
    return ImmutableList.<String>builder()
        .addAll(errors.subList(0, MAX_LISTED_ERRORS))
        .add(String.format("... and %d more", errors.size() - MAX_LISTED_ERRORS))
        .build();
  }

  private static Result error(String error) {
    return new Result(ImmutableList.of(error), ImmutableList.of());
  }

  private ActionFilesCheck() {}
}
