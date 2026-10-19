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

package com.gerritforge.gerrit.plugins.ai.provider;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.fetch;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class ActionFilesValidatorIT extends LightweightPluginDaemonTest {
  private static final String UPLOAD = "refs/for/" + RefNames.REFS_CONFIG;
  private static final String VALID = "---\n{\"displayText\": \"Security\"}\n---\nCheck {{patch}}";
  private static final String INVALID = "---\n{\"displayText\": 1}\n---\nCheck";

  @Test
  public void acceptsValidFiles() throws Exception {
    push(
            ImmutableMap.of(
                "ai-review/security.md",
                VALID,
                "ai-review/review-change.md",
                "---\n{\"enabled\": false}\n---\n"),
            RefNames.REFS_CONFIG)
        .assertOkStatus();
  }

  @Test
  public void rejectsInvalidFileOnUpload() throws Exception {
    assertRejected(
        push(ImmutableMap.of("ai-review/security.md", INVALID), UPLOAD),
        "ai-review/security.md: \"displayText\" must be a string");
  }

  @Test
  public void rejectsInvalidFileOnDirectPush() throws Exception {
    assertRejected(
        push(ImmutableMap.of("ai-review/security.md", INVALID), RefNames.REFS_CONFIG),
        "ai-review/security.md: \"displayText\" must be a string");
  }

  @Test
  public void rejectsEveryFormatRule() throws Exception {
    Map<String, String> files = new HashMap<>();
    files.put("ai-review/no-header.md", "Check {{patch}}");
    files.put("ai-review/unclosed.md", "---\n{\"displayText\": \"S\"}\nCheck");
    files.put("ai-review/bad-json.md", "---\n{displayText: \"S\"}\n---\nCheck");
    files.put(
        "ai-review/duplicate.md", "---\n{\"displayText\": \"S\", \"displayText\": \"T\"}\n---\nC");
    files.put("ai-review/no-text.md", "---\n{}\n---\nCheck");
    files.put(
        "ai-review/long-text.md", "---\n{\"displayText\": \"" + "x".repeat(201) + "\"}\n---\nC");
    files.put("ai-review/no-prompt.md", "---\n{\"displayText\": \"S\"}\n---\n");
    files.put("ai-review/bad-enabled.md", "---\n{\"enabled\": 0}\n---\n");
    files.put(
        "ai-review/bad-context.md", "---\n{\"displayText\": \"S\", \"contextLines\": -1}\n---\nC");
    files.put("ai-review/Bad_Name.md", VALID);
    files.put("ai-review/too-large.md", VALID + "x".repeat(64 * 1024));

    PushOneCommit.Result result = push(files, UPLOAD);

    result.assertErrorStatus();
    for (String path : files.keySet()) {
      assertThat(result.getMessage()).contains(path + ": ");
    }
  }

  @Test
  public void acceptsUnknownFieldsWithWarning() throws Exception {
    PushOneCommit.Result result =
        push(
            ImmutableMap.of(
                "ai-review/java.md",
                "---\n{\"displayText\": \"Java\", \"paths\": [\"java/**\"]}\n---\nCheck"),
            UPLOAD);

    result.assertOkStatus();
    assertThat(result.getMessage())
        .contains("ai-review/java.md: unknown fields are ignored: paths");
  }

  @Test
  public void ignoresOtherFiles() throws Exception {
    push(
            ImmutableMap.of(
                "ai-review/agents/reviewer.md", "anything",
                "ai-review/notes.txt", "anything"),
            UPLOAD)
        .assertOkStatus();
  }

  @Test
  public void rejectsTooManyFiles() throws Exception {
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i <= 100; i++) {
      files.put("ai-review/action-" + i + ".md", VALID);
    }

    assertRejected(push(files, UPLOAD), "limit of 100");
  }

  @Test
  public void allowsDeletingInvalidFile() throws Exception {
    writeDirectly("ai-review/security.md", INVALID);
    fetchConfig();

    pushFactory
        .create(admin.newIdent(), testRepo, "Delete action", "ai-review/security.md", "")
        .rm(RefNames.REFS_CONFIG)
        .assertOkStatus();
  }

  @Test
  public void allowsUnrelatedChangesNextToInvalidFile() throws Exception {
    writeDirectly("ai-review/security.md", INVALID);

    push(ImmutableMap.of("ai-review/other.md", VALID), UPLOAD).assertOkStatus();
  }

  @Test
  public void allowsFixingInvalidFile() throws Exception {
    writeDirectly("ai-review/security.md", INVALID);

    push(ImmutableMap.of("ai-review/security.md", VALID), RefNames.REFS_CONFIG).assertOkStatus();
  }

  @Test
  public void rejectsEditThatKeepsFileInvalid() throws Exception {
    writeDirectly("ai-review/security.md", INVALID);

    assertRejected(
        push(
            ImmutableMap.of("ai-review/security.md", "---\n{\"displayText\": 2}\n---\nOther"),
            RefNames.REFS_CONFIG),
        "ai-review/security.md: \"displayText\" must be a string");
  }

  @Test
  public void rejectsGrowingPastLimit() throws Exception {
    writeDirectly(numberedFiles(100));

    assertRejected(
        push(ImmutableMap.of("ai-review/one-more.md", VALID), UPLOAD),
        "ai-review/ holds 101 action files, more than the limit of 100");
  }

  @Test
  public void allowsShrinkingProjectOverLimit() throws Exception {
    Map<String, String> files = numberedFiles(104);
    files.put("ai-review/zz-invalid.md", INVALID);
    writeDirectly(files);
    fetchConfig();

    pushFactory
        .create(admin.newIdent(), testRepo, "Delete action", "ai-review/action-0.md", "")
        .rm(RefNames.REFS_CONFIG)
        .assertOkStatus();
  }

  @Test
  public void doesNotWarnAboutUntouchedFiles() throws Exception {
    writeDirectly("ai-review/java.md", "---\n{\"displayText\": \"J\", \"paths\": []}\n---\nCheck");

    PushOneCommit.Result result = push(ImmutableMap.of("ai-review/other.md", VALID), UPLOAD);

    result.assertOkStatus();
    assertThat(result.getMessage()).doesNotContain("unknown fields");
  }

  @Test
  public void rejectsInvalidIntermediateCommit() throws Exception {
    fetchConfig();
    RevCommit invalid =
        newCommit().parent(configTip()).add("ai-review/security.md", INVALID).create();
    RevCommit fixed = newCommit().parent(invalid).add("ai-review/security.md", VALID).create();
    testRepo.reset(fixed);

    PushResult result = pushHead(testRepo, RefNames.REFS_CONFIG);

    assertThat(result.getRemoteUpdate(RefNames.REFS_CONFIG).getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(result.getMessages()).contains("ai-review/security.md");
  }

  @Test
  public void rejectsPushOfExistingCommit() throws Exception {
    fetchConfig();
    RevCommit invalid =
        newCommit().parent(configTip()).add("ai-review/security.md", INVALID).create();
    testRepo.reset(invalid);
    assertThat(
            pushHead(testRepo, "refs/heads/elsewhere")
                .getRemoteUpdate("refs/heads/elsewhere")
                .getStatus())
        .isEqualTo(RemoteRefUpdate.Status.OK);

    PushResult result = pushHead(testRepo, RefNames.REFS_CONFIG);

    assertThat(result.getRemoteUpdate(RefNames.REFS_CONFIG).getStatus())
        .isNotEqualTo(RemoteRefUpdate.Status.OK);
    assertThat(result.getMessages()).contains("ai-review/security.md");
  }

  @Test
  public void rejectsSubmitThatCreatesTooManyFiles() throws Exception {
    fetchConfig();
    RevCommit base = configTip();
    PushOneCommit.Result first =
        pushFactory
            .create(admin.newIdent(), testRepo, "First", numberedFiles("first", 60))
            .to(UPLOAD);
    first.assertOkStatus();
    testRepo.reset(base);
    PushOneCommit.Result second =
        pushFactory
            .create(admin.newIdent(), testRepo, "Second", numberedFiles("second", 60))
            .to(UPLOAD);
    second.assertOkStatus();

    gApi.changes().id(first.getChangeId()).current().review(ReviewInput.approve());
    gApi.changes().id(first.getChangeId()).current().submit();
    gApi.changes().id(second.getChangeId()).current().review(ReviewInput.approve());

    RestApiException e =
        assertThrows(
            RestApiException.class,
            () -> gApi.changes().id(second.getChangeId()).current().submit());
    assertThat(e).hasMessageThat().contains("ai-review/ holds 120 action files");
  }

  @Test
  public void ignoresOtherBranches() throws Exception {
    pushFactory
        .create(admin.newIdent(), testRepo, "Add file", "ai-review/security.md", INVALID)
        .to("refs/for/master")
        .assertOkStatus();
  }

  private PushOneCommit.Result push(Map<String, String> files, String ref) throws Exception {
    fetchConfig();
    return pushFactory.create(admin.newIdent(), testRepo, "Update actions", files).to(ref);
  }

  private TestRepository<?>.CommitBuilder newCommit() throws Exception {
    return testRepo.commit().author(admin.newIdent()).committer(admin.newIdent());
  }

  private RevCommit configTip() throws Exception {
    return testRepo.getRevWalk().parseCommit(testRepo.getRepository().resolve("HEAD"));
  }

  private static Map<String, String> numberedFiles(int count) {
    return numberedFiles("action", count);
  }

  private static Map<String, String> numberedFiles(String prefix, int count) {
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i < count; i++) {
      files.put("ai-review/" + prefix + "-" + i + ".md", VALID);
    }
    return files;
  }

  private void writeDirectly(String path, String content) throws Exception {
    writeDirectly(Map.of(path, content));
  }

  private void fetchConfig() throws Exception {
    fetch(testRepo, RefNames.REFS_CONFIG + ":" + RefNames.REFS_CONFIG);
    testRepo.reset(RefNames.REFS_CONFIG);
  }

  private static void assertRejected(PushOneCommit.Result result, String message) {
    result.assertErrorStatus("invalid AI review action files");
    assertThat(result.getMessage()).contains(message);
  }

  /** Writes directly to the repository, bypassing push validation. */
  private void writeDirectly(Map<String, String> files) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> repository = new TestRepository<>(repo)) {
      TestRepository<Repository>.CommitBuilder commit =
          repository.branch(RefNames.REFS_CONFIG).commit();
      for (Map.Entry<String, String> file : files.entrySet()) {
        commit.add(file.getKey(), file.getValue());
      }
      commit.create();
    }
    projectCache.evictAndReindex(project);
  }
}
