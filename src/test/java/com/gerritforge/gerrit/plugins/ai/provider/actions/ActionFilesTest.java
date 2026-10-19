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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.Before;
import org.junit.Test;

public class ActionFilesTest {
  private static final ActionLimits LIMITS = new ActionLimits(100, 3, 4, 50);
  private static final String ACTION = "---\n{\"displayText\": \"S\"}\n---\nCheck";

  private TestRepository<InMemoryRepository> repo;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("actions")));
  }

  @Test
  public void readsOnlyMarkdownFilesDirectlyInFolder() throws Exception {
    ActionFiles files =
        read(
            repo.tree(
                file("ai-review/security.md"),
                file("ai-review/agents/reviewer.md"),
                file("ai-review/skills/java/SKILL.md"),
                file("ai-review/folder.md/action.md"),
                file("ai-review/notes.txt"),
                file("ai-review/securitymd"),
                file("security.md")));

    assertThat(files.byPath().keySet()).containsExactly("ai-review/security.md");
    assertThat(files.error()).isEmpty();
  }

  @Test
  public void treeWithoutFolderHasNoFiles() throws Exception {
    assertThat(read(repo.tree(file("project.config"))).byPath()).isEmpty();
    assertThat(read(repo.tree(file("ai-review"))).byPath()).isEmpty();
  }

  @Test
  public void namesDecodingToTheSamePathAreReadOnce() throws Exception {
    // "\u00e9.md" in UTF-8 and in ISO-8859-1, which JGit decodes alike.
    try (ObjectInserter inserter = repo.getRepository().newObjectInserter()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, ACTION.getBytes(UTF_8));
      TreeFormatter folder = new TreeFormatter();
      folder.append(
          new byte[] {(byte) 0xC3, (byte) 0xA9, '.', 'm', 'd'}, FileMode.REGULAR_FILE, blob);
      folder.append(new byte[] {(byte) 0xE9, '.', 'm', 'd'}, FileMode.REGULAR_FILE, blob);
      TreeFormatter root = new TreeFormatter();
      root.append(ActionFileParser.FOLDER_NAME, FileMode.TREE, inserter.insert(folder));
      ObjectId tree = inserter.insert(root);
      inserter.flush();

      assertThat(read(tree).byPath()).hasSize(1);
    }
  }

  @Test
  public void oversizedFileIsInvalid() throws Exception {
    ActionFiles files =
        read(repo.tree(repo.file("ai-review/security.md", repo.blob(ACTION + "x".repeat(100)))));

    assertThat(files.byPath().values())
        .containsExactly(
            new ActionFile.Invalid(
                "ai-review/security.md", "security", "file is larger than 100 bytes"));
  }

  @Test
  public void acceptsFileLimitExactly() throws Exception {
    ActionFiles files =
        read(repo.tree(file("ai-review/a.md"), file("ai-review/b.md"), file("ai-review/c.md")));

    assertThat(files.byPath()).hasSize(LIMITS.maxFiles());
    assertThat(files.error()).isEmpty();
  }

  @Test
  public void stopsReadingOneFileAfterTheLimit() throws Exception {
    ActionFiles files =
        read(
            repo.tree(
                file("ai-review/a.md"),
                file("ai-review/b.md"),
                file("ai-review/c.md"),
                file("ai-review/d.md"),
                file("ai-review/e.md")));

    assertThat(files.byPath()).hasSize(LIMITS.maxFiles() + 1);
    assertThat(files.error()).hasValue("ai-review/ holds more action files than the limit of 3");
  }

  private DirCacheEntry file(String path) throws Exception {
    return repo.file(path, repo.blob(ACTION));
  }

  private ActionFiles read(ObjectId tree) throws Exception {
    return ActionFiles.read(repo.getRevWalk().getObjectReader(), tree, LIMITS);
  }
}
