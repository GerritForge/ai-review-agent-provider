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
import com.google.gerrit.common.Nullable;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Checks the action files that differ between two {@code refs/meta/config} trees. Files that did
 * not change are not checked, so deleting or keeping an invalid file is always possible.
 */
public class ActionFilesCheck {
  private static final String FOLDER = "ai-review";

  /** Problems found; any error makes the new tree unacceptable. */
  public record Result(ImmutableList<String> errors, ImmutableList<String> warnings) {}

  /**
   * @param oldTree the tree before the update, or {@code null} if there was none
   */
  public static Result check(ObjectReader reader, @Nullable ObjectId oldTree, ObjectId newTree)
      throws IOException {
    ImmutableList.Builder<String> errors = ImmutableList.builder();
    ImmutableList.Builder<String> warnings = ImmutableList.builder();
    try (TreeWalk walk = new TreeWalk(reader)) {
      if (oldTree == null) {
        walk.addTree(new EmptyTreeIterator());
      } else {
        walk.addTree(oldTree);
      }
      walk.addTree(newTree);
      walk.setFilter(AndTreeFilter.create(PathFilter.create(FOLDER), TreeFilter.ANY_DIFF));
      while (walk.next()) {
        if (walk.getDepth() == 0) {
          if (walk.isSubtree()) {
            walk.enterSubtree();
          }
          continue;
        }
        String path = walk.getPathString();
        Optional<String> id = ActionFileParser.fileName(path);
        if (id.isEmpty() || (walk.getRawMode(1) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
          continue;
        }
        ObjectId blob = walk.getObjectId(1);
        ActionFile file =
            reader.getObjectSize(blob, OBJ_BLOB) > ActionFileParser.MAX_FILE_BYTES
                ? ActionFile.tooLarge(path, id.get())
                : ActionFile.parse(path, id.get(), reader.open(blob, OBJ_BLOB).getCachedBytes());
        if (file.error() != null) {
          errors.add(path + ": " + file.error());
        } else if (!file.definition().unknownFields().isEmpty()) {
          warnings.add(
              String.format(
                  "%s: unknown fields are ignored: %s",
                  path, String.join(", ", file.definition().unknownFields())));
        }
      }
    }

    int newCount = countActionFiles(reader, newTree);
    if (newCount > ActionFiles.MAX_FILES
        && (oldTree == null || newCount > countActionFiles(reader, oldTree))) {
      errors.add(
          String.format(
              "%s holds %d action files, more than the limit of %d",
              ActionFileParser.FOLDER, newCount, ActionFiles.MAX_FILES));
    }
    return new Result(errors.build(), warnings.build());
  }

  /** Counts the action files of {@code tree} without reading them. */
  private static int countActionFiles(ObjectReader reader, ObjectId tree) throws IOException {
    int count = 0;
    try (TreeWalk walk = new TreeWalk(reader)) {
      walk.addTree(tree);
      walk.setFilter(PathFilter.create(FOLDER));
      while (walk.next()) {
        if (walk.getDepth() == 0) {
          if (walk.isSubtree()) {
            walk.enterSubtree();
          }
          continue;
        }
        if (ActionFileParser.fileName(walk.getPathString()).isPresent()
            && (walk.getRawMode(0) & FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
          count++;
        }
      }
    }
    return count;
  }

  private ActionFilesCheck() {}
}
