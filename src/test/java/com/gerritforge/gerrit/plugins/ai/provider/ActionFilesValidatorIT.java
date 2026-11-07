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

import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionLimits;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;
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
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.ActionFilesValidatorIT$SmallLimits",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class ActionFilesValidatorIT extends LightweightPluginDaemonTest {
  private static final ActionLimits LIMITS = new ActionLimits(200, 25, 500);

  /** The plugin with limits that tests reach with a few short files. */
  public static class SmallLimits extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new AiReviewProviderModule())
              .with(binder -> binder.bind(ActionLimits.class).toInstance(LIMITS)));
    }
  }

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
  public void listsEveryInvalidFile() throws Exception {
    PushOneCommit.Result result =
        push(
            ImmutableMap.of(
                "ai-review/no-header.md",
                "Check {{patch}}",
                "ai-review/Bad_Name.md",
                VALID,
                "ai-review/too-large.md",
                VALID + "x".repeat(LIMITS.maxFileBytes())),
            UPLOAD);

    assertRejected(result, "ai-review/no-header.md: file must start with a '---' line");
    assertRejected(result, "ai-review/Bad_Name.md: file path must match");
    assertRejected(result, "ai-review/too-large.md: file is larger than 200 bytes");
  }

  @Test
  public void listsAtMost20Errors() throws Exception {
    assertRejected(push(invalidFiles(21), UPLOAD), "... and 1 more");
  }

  @Test
  public void acceptsUnknownFieldsWithWarning() throws Exception {
    PushOneCommit.Result result =
        push(
            ImmutableMap.of(
                "ai-review/java.md",
                "---\n{\"displayText\": \"Java\", \"model\": \"gemini\"}\n---\nCheck"),
            UPLOAD);

    result.assertOkStatus();
    assertThat(result.getMessage())
        .contains("ai-review/java.md: unknown fields are ignored: model");
  }

  @Test
  public void ignoresOtherFiles() throws Exception {
    push(
            ImmutableMap.of(
                "ai-review/agents/reviewer.md", "anything",
                "ai-review/folder.md/action.md", "anything",
                "ai-review/securitymd", "anything",
                "ai-review/notes.txt", "anything"),
            UPLOAD)
        .assertOkStatus();
  }

  @Test
  public void rejectsTooManyFilesBeforeReadingThem() throws Exception {
    PushOneCommit.Result result = push(invalidFiles(LIMITS.maxFiles() + 1), UPLOAD);

    assertRejected(result, "ai-review/ holds 26 action files, more than the limit of 25");
    assertThat(result.getMessage()).doesNotContain("must be a string");
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
    writeDirectly(numberedFiles(LIMITS.maxFiles()));

    assertRejected(
        push(ImmutableMap.of("ai-review/one-more.md", VALID), UPLOAD),
        "ai-review/ holds 26 action files, more than the limit of 25");
  }

  @Test
  public void rejectsOwnActionsOverCharacterLimit() throws Exception {
    // 22 actions of 23 characters each.
    assertRejected(
        push(numberedFiles(22), RefNames.REFS_CONFIG),
        "the labels and prompts of the enabled actions in ai-review/ are 506 characters long");
  }

  @Test
  public void countsUntouchedFilesTowardsCharacterLimit() throws Exception {
    writeDirectly(numberedFiles(20));

    assertRejected(
        push(numberedFiles("more", 2), UPLOAD),
        "the labels and prompts of the enabled actions in ai-review/ are 506 characters long");
  }

  @Test
  public void acceptsFilesOverCharacterLimitInBytesOnly() throws Exception {
    // Five files of over 100 bytes each, whose labels and prompts have two characters.
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i < 5; i++) {
      files.put(
          "ai-review/padded-" + i + ".md",
          "---\n{\"displayText\": \"S\", \"notes\": \"" + "x".repeat(100) + "\"}\n---\nC");
    }

    push(files, UPLOAD).assertOkStatus();
  }

  @Test
  public void allowsShrinkingProjectOverLimit() throws Exception {
    Map<String, String> files = numberedFiles(LIMITS.maxFiles() + 4);
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
    writeDirectly(
        "ai-review/java.md", "---\n{\"displayText\": \"J\", \"model\": \"m\"}\n---\nCheck");

    PushOneCommit.Result result = push(ImmutableMap.of("ai-review/other.md", VALID), UPLOAD);

    result.assertOkStatus();
    assertThat(result.getMessage()).doesNotContain("unknown fields");
  }

  @Test
  public void warnsAboutUnknownFieldsOnDirectPush() throws Exception {
    PushOneCommit.Result result =
        push(
            ImmutableMap.of(
                "ai-review/java.md", "---\n{\"displayText\": \"J\", \"model\": \"m\"}\n---\nC"),
            RefNames.REFS_CONFIG);

    result.assertOkStatus();
    assertThat(result.getMessage())
        .contains("ai-review/java.md: unknown fields are ignored: model");
  }

  @Test
  public void acceptsMergeOfFileAlreadyOnTheBranch() throws Exception {
    fetchConfig();
    RevCommit base = configTip();
    writeDirectly("ai-review/security.md", INVALID);
    fetchConfig();
    RevCommit tip = configTip();
    RevCommit local = newCommit().parent(base).add("ai-review/other.md", VALID).create();
    RevCommit merge =
        newCommit()
            .parent(local)
            .parent(tip)
            .add("ai-review/security.md", INVALID)
            .message("Merge")
            .create();
    testRepo.reset(merge);

    PushResult result = pushHead(testRepo, RefNames.REFS_CONFIG);

    assertThat(result.getRemoteUpdate(RefNames.REFS_CONFIG).getStatus())
        .isEqualTo(RemoteRefUpdate.Status.OK);
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
            .create(admin.newIdent(), testRepo, "First", numberedFiles("first", 15))
            .to(UPLOAD);
    first.assertOkStatus();
    testRepo.reset(base);
    PushOneCommit.Result second =
        pushFactory
            .create(admin.newIdent(), testRepo, "Second", numberedFiles("second", 15))
            .to(UPLOAD);
    second.assertOkStatus();

    gApi.changes().id(first.getChangeId()).current().review(ReviewInput.approve());
    gApi.changes().id(first.getChangeId()).current().submit();
    gApi.changes().id(second.getChangeId()).current().review(ReviewInput.approve());

    RestApiException e =
        assertThrows(
            RestApiException.class,
            () -> gApi.changes().id(second.getChangeId()).current().submit());
    assertThat(e).hasMessageThat().contains("ai-review/ holds 30 action files");
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

  private static Map<String, String> invalidFiles(int count) {
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i < count; i++) {
      files.put("ai-review/invalid-" + i + ".md", INVALID);
    }
    return files;
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
