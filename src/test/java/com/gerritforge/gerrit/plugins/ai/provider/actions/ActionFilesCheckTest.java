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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;

public class ActionFilesCheckTest {
  private TestRepository<InMemoryRepository> repo;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("actions")));
  }

  @Test
  public void listsAtMost20Warnings() throws Exception {
    List<DirCacheEntry> files = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      files.add(file("ai-review/action-" + i + ".md", "{\"displayText\": \"S\", \"paths\": []}"));
    }

    ImmutableList<String> warnings = check(repo.tree(files.toArray(new DirCacheEntry[0])));

    assertThat(warnings).hasSize(21);
    assertThat(warnings.get(20)).isEqualTo("... and 1 more");
  }

  @Test
  public void namesAtMost10UnknownFieldsPerWarning() throws Exception {
    StringBuilder header = new StringBuilder("{\"displayText\": \"S\"");
    for (int i = 0; i < 11; i++) {
      header.append(", \"f").append(i).append("\": 1");
    }

    ImmutableList<String> warnings =
        check(repo.tree(file("ai-review/security.md", header.append("}").toString())));

    assertThat(warnings)
        .containsExactly(
            "ai-review/security.md: unknown fields are ignored:"
                + " f0, f1, f2, f3, f4, f5, f6, f7, f8, f9 and 1 more");
  }

  private DirCacheEntry file(String path, String header) throws Exception {
    return repo.file(path, repo.blob("---\n" + header + "\n---\nCheck"));
  }

  private ImmutableList<String> check(ObjectId tree) throws Exception {
    ActionFilesCheck.Result result =
        ActionFilesCheck.check(
            repo.getRevWalk().getObjectReader(), List.of(), tree, ActionLimits.DEFAULT);
    assertThat(result.errors()).isEmpty();
    return result.warnings();
  }
}
