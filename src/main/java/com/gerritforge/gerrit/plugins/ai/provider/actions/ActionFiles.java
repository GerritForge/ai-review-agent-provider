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
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

/** The action files of one {@code refs/meta/config} tree, keyed by path. */
public record ActionFiles(ImmutableSortedMap<String, ActionFile> byPath) {
  public static final int MAX_FILES = 100;

  /** Approximate memory of a cache entry or file, besides its text, in the cache's weight unit. */
  static final int ENTRY_WEIGHT = 256;

  public static final ActionFiles EMPTY = new ActionFiles(ImmutableSortedMap.of());

  /**
   * Reads the {@code ai-review/*.md} files of {@code tree}; other files are ignored. Stops after
   * one file more than {@link #MAX_FILES}, which is enough to report the limit.
   */
  public static ActionFiles read(ObjectReader reader, ObjectId tree) throws IOException {
    ImmutableSortedMap.Builder<String, ActionFile> files = ImmutableSortedMap.naturalOrder();
    try (TreeWalk walk = new TreeWalk(reader)) {
      walk.addTree(tree);
      walk.setFilter(PathFilter.create("ai-review"));
      int count = 0;
      while (count <= MAX_FILES && walk.next()) {
        if (walk.getDepth() == 0) {
          // Only the folder itself is entered; its subfolders are never read.
          if (walk.isSubtree()) {
            walk.enterSubtree();
          }
          continue;
        }
        String path = walk.getPathString();
        Optional<String> id = ActionFileParser.fileName(path);
        if (id.isEmpty() || (walk.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
          continue;
        }
        count++;
        ObjectId blob = walk.getObjectId(0);
        if (reader.getObjectSize(blob, OBJ_BLOB) > ActionFileParser.MAX_FILE_BYTES) {
          files.put(path, ActionFile.tooLarge(path, id.get()));
        } else {
          byte[] content = reader.open(blob, OBJ_BLOB).getCachedBytes();
          files.put(path, ActionFile.parse(path, id.get(), content));
        }
      }
    }
    return new ActionFiles(files.build());
  }

  /** Errors that make the whole set of files unusable. */
  public ImmutableList<String> errors() {
    if (byPath.size() > MAX_FILES) {
      return ImmutableList.of(
          String.format(
              "%s holds more action files than the limit of %d",
              ActionFileParser.FOLDER, MAX_FILES));
    }
    return ImmutableList.of();
  }

  int weight() {
    return byPath.values().stream().mapToInt(ActionFile::weight).sum() + ENTRY_WEIGHT;
  }
}
