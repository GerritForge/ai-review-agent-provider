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

import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;

record ActionFiles(ImmutableSortedMap<String, ActionFile> byPath, Optional<String> error) {
  static final ActionFiles EMPTY = new ActionFiles(ImmutableSortedMap.of(), Optional.empty());

  /** Approximate memory of a cache entry or file, besides its text, in the cache's weight unit. */
  private static final int ENTRY_WEIGHT = 256;

  /**
   * Reads the {@code ai-review/*.md} files of {@code tree}. Stops after one file more than the
   * limit, which is enough to report it. The project's own enabled actions always win in the
   * project, so their labels and prompts alone must stay within the limit.
   */
  static ActionFiles read(ObjectReader reader, ObjectId tree, ActionLimits limits)
      throws IOException {
    // Names that are not valid UTF-8 can decode to the same path; they are invalid ids anyway.
    TreeMap<String, ActionFile> files = new TreeMap<>();
    int count = 0;
    try (TreeWalk walk = new TreeWalk(reader)) {
      walk.addTree(folder(reader, tree));
      walk.setFilter(PathSuffixFilter.create(ActionFileParser.EXTENSION));
      while (count <= limits.maxFiles() && walk.next()) {
        if (!isFile(walk, 0)) {
          continue;
        }
        count++;
        String path = ActionFileParser.FOLDER + walk.getPathString();
        files.putIfAbsent(
            path, readFile(reader, path, id(walk), walk.getObjectId(0), limits.maxFileBytes()));
      }
    }
    Optional<String> error = Optional.empty();
    if (count > limits.maxFiles()) {
      error =
          Optional.of(
              String.format(
                  "%s holds more action files than the limit of %d",
                  ActionFileParser.FOLDER, limits.maxFiles()));
    } else {
      long chars = 0;
      for (ActionFile file : files.values()) {
        if (file instanceof ActionFile.Valid valid
            && valid.definition() instanceof ActionDefinition.Enabled action) {
          chars += action.textLength();
        }
      }
      if (chars > limits.maxTotalChars()) {
        error = Optional.of(tooLong(chars, limits.maxTotalChars()));
      }
    }
    return new ActionFiles(ImmutableSortedMap.copyOfSorted(files), error);
  }

  static String tooLarge(int maxFileBytes) {
    return String.format("file is larger than %d bytes", maxFileBytes);
  }

  static String tooLong(long chars, int maxTotalChars) {
    return String.format(
        "the labels and prompts of the enabled actions in %s are %d characters long, more than"
            + " the limit of %d",
        ActionFileParser.FOLDER, chars, maxTotalChars);
  }

  /** Iterates over the entries directly in the {@code ai-review} folder of {@code tree}, if any. */
  static AbstractTreeIterator folder(ObjectReader reader, ObjectId tree) throws IOException {
    try (TreeWalk walk = TreeWalk.forPath(reader, ActionFileParser.FOLDER_NAME, tree)) {
      if (walk != null && walk.getFileMode(0) == FileMode.TREE) {
        return new CanonicalTreeParser(null, reader, walk.getObjectId(0));
      }
    }
    return new EmptyTreeIterator();
  }

  /** Whether the entry in tree {@code nth} is a file, not a folder, link or submodule. */
  static boolean isFile(TreeWalk walk, int nth) {
    return (walk.getRawMode(nth) & FileMode.TYPE_MASK) == FileMode.TYPE_FILE;
  }

  static String id(TreeWalk walk) {
    String name = walk.getNameString();
    return name.substring(0, name.length() - ActionFileParser.EXTENSION.length());
  }

  private static ActionFile readFile(
      ObjectReader reader, String path, String id, ObjectId blob, int maxFileBytes)
      throws IOException {
    if (reader.getObjectSize(blob, OBJ_BLOB) > maxFileBytes) {
      return new ActionFile.Invalid(path, id, tooLarge(maxFileBytes));
    }
    try {
      return new ActionFile.Valid(
          path, id, ActionFileParser.parse(id, reader.open(blob, OBJ_BLOB).getCachedBytes()));
    } catch (InvalidActionFileException e) {
      return new ActionFile.Invalid(path, id, e.getMessage());
    }
  }

  /** Approximate memory, in characters, for the cache weigher. */
  int weight() {
    int weight = ENTRY_WEIGHT;
    for (ActionFile file : byPath.values()) {
      weight += ENTRY_WEIGHT + file.path().length();
      if (file instanceof ActionFile.Valid valid
          && valid.definition() instanceof ActionDefinition.Enabled action) {
        weight += action.textLength();
      } else if (file instanceof ActionFile.Invalid invalid) {
        weight += invalid.error().length();
      }
    }
    return weight;
  }
}
