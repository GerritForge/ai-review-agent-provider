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

import static java.util.stream.Collectors.joining;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;

/**
 * Checks the action files an update of {@code refs/meta/config} adds or modifies. Files it leaves
 * as they were are not checked, so deleting or keeping an invalid file is always possible.
 */
public class ActionFilesCheck {
  private static final int MAX_LISTED = 20;
  private static final int MAX_LISTED_FIELDS = 10;

  public record Result(ImmutableList<String> errors, ImmutableList<String> warnings) {
    public static final Result OK = new Result(ImmutableList.of(), ImmutableList.of());
  }

  private record ActionFileBlob(String path, String id, ObjectId blob) {}

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
    List<ActionFileBlob> changed = new ArrayList<>();
    List<ActionFileBlob> unchanged = new ArrayList<>();
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
        List<ActionFileBlob> files = fromParent ? unchanged : changed;
        if (files.size() <= limits.maxFiles()) {
          files.add(
              new ActionFileBlob(
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
    List<String> warnings = new ArrayList<>();
    long changedBytes = 0;
    long changedChars = 0;
    for (ActionFileBlob file : changed) {
      long size = reader.getObjectSize(file.blob(), OBJ_BLOB);
      changedBytes += size;
      if (size > limits.maxFileBytes()) {
        errors.add(file.path() + ": " + ActionFiles.tooLarge(limits.maxFileBytes()));
        continue;
      }
      try {
        ActionFileParser.Parsed parsed =
            ActionFileParser.parseWithUnknownFields(
                file.id(), reader.open(file.blob(), OBJ_BLOB).getCachedBytes());
        changedChars += textLength(parsed.definition());
        if (!parsed.unknownFields().isEmpty()) {
          warnings.add(
              String.format(
                  "%s: unknown fields are ignored: %s",
                  file.path(), fieldNames(parsed.unknownFields())));
        }
      } catch (InvalidActionFileException e) {
        errors.add(file.path() + ": " + e.getMessage());
      }
    }
    if (errors.isEmpty() && !changed.isEmpty() && newCount <= limits.maxFiles()) {
      ownTextLengthError(reader, unchanged, changedBytes, changedChars, limits)
          .ifPresent(errors::add);
    }
    return new Result(listed(errors), listed(warnings));
  }

  /**
   * The project's own enabled actions always win in the project, so their labels and prompts alone
   * must stay within the limit. Text is never longer in UTF-16 than in UTF-8, so the unchanged
   * files are parsed only when all files together have more bytes than the limit allows characters.
   */
  private static Optional<String> ownTextLengthError(
      ObjectReader reader,
      List<ActionFileBlob> unchanged,
      long changedBytes,
      long changedChars,
      ActionLimits limits)
      throws IOException {
    long bytes = changedBytes;
    for (ActionFileBlob file : unchanged) {
      bytes += reader.getObjectSize(file.blob(), OBJ_BLOB);
    }
    if (bytes <= limits.maxTotalChars()) {
      return Optional.empty();
    }
    long chars = changedChars;
    for (ActionFileBlob file : unchanged) {
      if (reader.getObjectSize(file.blob(), OBJ_BLOB) > limits.maxFileBytes()) {
        continue;
      }
      try {
        chars +=
            textLength(
                ActionFileParser.parse(
                    file.id(), reader.open(file.blob(), OBJ_BLOB).getCachedBytes()));
      } catch (InvalidActionFileException e) {
        // An invalid file defines no action.
      }
    }
    if (chars <= limits.maxTotalChars()) {
      return Optional.empty();
    }
    return Optional.of(ActionFiles.tooLong(chars, limits.maxTotalChars()));
  }

  private static long textLength(ActionDefinition definition) {
    return definition instanceof ActionDefinition.Enabled action ? action.textLength() : 0;
  }

  private static ImmutableList<String> listed(List<String> messages) {
    if (messages.size() <= MAX_LISTED) {
      return ImmutableList.copyOf(messages);
    }
    return ImmutableList.<String>builder()
        .addAll(messages.subList(0, MAX_LISTED))
        .add(String.format("... and %d more", messages.size() - MAX_LISTED))
        .build();
  }

  private static String fieldNames(Set<String> fields) {
    String names = fields.stream().limit(MAX_LISTED_FIELDS).collect(joining(", "));
    return fields.size() > MAX_LISTED_FIELDS
        ? String.format("%s and %d more", names, fields.size() - MAX_LISTED_FIELDS)
        : names;
  }

  private static Result error(String error) {
    return new Result(ImmutableList.of(error), ImmutableList.of());
  }

  private ActionFilesCheck() {}
}
