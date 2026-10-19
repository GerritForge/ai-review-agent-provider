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

import static com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule.ACTIONS_ENDPOINT;
import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.deny;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.permissionKey;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.gerritforge.gerrit.plugins.ai.provider.GetActions.ActionInfo;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.change.ChangeOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ChangeIdentifier;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class GetActionsIT extends LightweightPluginDaemonTest {
  @Inject private ProjectOperations projectOperations;
  @Inject private ChangeOperations changeOperations;

  private Project.NameKey parent;
  private Project.NameKey child;
  private ChangeIdentifier change;

  @Before
  public void setUp() throws Exception {
    parent = projectOperations.newProject().create();
    child = projectOperations.newProject().parent(parent).create();
    change = changeOperations.newChange().project(child).create();
  }

  @Test
  public void returnsBuiltInActionsForRevision() throws Exception {
    GetActions.Output output = getActions(userRestSession.get(url("current")));

    assertThat(output.revision()).isEqualTo(gApi.changes().id(change.id()).get().currentRevision);
    assertThat(output.actions().stream().map(ActionInfo::id))
        .containsExactly("review-change", "review-change-full", "review-commit")
        .inOrder();
    ActionInfo reviewChange = output.actions().get(0);
    assertThat(reviewChange.displayText()).isEqualTo("Help me with review");
    assertThat(reviewChange.contextLines()).isEqualTo(10);
    assertThat(reviewChange.prompt()).contains("{{patch}}");
    assertThat(output.defaultActionId()).isEqualTo("review-change");
    assertThat(output.fingerprint()).isNotEmpty();
  }

  @Test
  public void returnsActionsInheritedByChangeProject() throws Exception {
    write(
        parent,
        "ai-review/security.md",
        "---\n{\"displayText\": \"Security\", \"contextLines\": 20}\n"
            + "---\nCheck {{project}} {{branch}} {{patch}}");
    write(child, "ai-review/review-change.md", "---\n{\"enabled\": false}\n---\n");

    GetActions.Output output = getActions(userRestSession.get(url("current")));

    assertThat(output.actions().stream().map(ActionInfo::id))
        .containsExactly("review-change-full", "review-commit", "security")
        .inOrder();
    ActionInfo security = output.actions().get(2);
    assertThat(security.contextLines()).isEqualTo(20);
    assertThat(security.prompt()).isEqualTo("Check {{project}} {{branch}} {{patch}}");
    assertThat(output.defaultActionId()).isEqualTo("review-change-full");
  }

  @Test
  public void fingerprintChangesOnlyWithActions() throws Exception {
    String before = getActions(userRestSession.get(url("current"))).fingerprint();
    assertThat(getActions(userRestSession.get(url("current"))).fingerprint()).isEqualTo(before);

    write(parent, "ai-review/review-commit.md", "---\n{\"displayText\": \"C\"}\n---\nNew");

    assertThat(getActions(userRestSession.get(url("current"))).fingerprint()).isNotEqualTo(before);
  }

  @Test
  public void allActionsDisabledHasNoDefault() throws Exception {
    for (String id : new String[] {"review-change", "review-change-full", "review-commit"}) {
      write(child, "ai-review/" + id + ".md", "---\n{\"enabled\": false}\n---\n");
    }

    GetActions.Output output = getActions(userRestSession.get(url("current")));

    assertThat(output.actions()).isEmpty();
    assertThat(output.defaultActionId()).isNull();
  }

  @Test
  public void parentConfigIsNotExposed() throws Exception {
    write(parent, "ai-review/security.md", "---\n{\"displayText\": \"S\"}\n---\nTeam prompt");
    write(child, "ai-review/review-commit.md", "---\n{\"displayText\": \"C\"}\n---\nChild");
    write(parent, "ai-review/review-commit.md", "---\n{\"displayText\": \"P\"}\n---\nHidden");

    RestResponse response = userRestSession.get(url("current"));
    response.assertOK();
    String body = response.getEntityContent();

    assertThat(body).contains("Team prompt");
    assertThat(body).doesNotContain("Hidden");
    assertThat(body).doesNotContain(parent.get());
  }

  @Test
  public void anonymousIsRejected() throws Exception {
    anonymousRestSession.get(url("current")).assertForbidden();
  }

  @Test
  public void invisibleChangeIsNotFound() throws Exception {
    projectOperations
        .project(child)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();

    userRestSession.get(url("current")).assertNotFound();
  }

  @Test
  public void invalidConfigurationIsConflict() throws Exception {
    write(parent, "ai-review/security.md", "---\n{\"displayText\": 1}\n---\nCheck");

    RestResponse response = userRestSession.get(url("current"));

    response.assertConflict();
    assertThat(response.getEntityContent()).contains("ai-review/security.md");
  }

  @Test
  public void invalidParentConfigurationIsHiddenFromUserWhoCannotSeeParent() throws Exception {
    write(parent, "ai-review/security.md", "---\n{\"displayText\": 1}\n---\nCheck");
    hideParentFromUser();

    RestResponse response = userRestSession.get(url("current"));

    response.assertConflict();
    assertThat(response.getEntityContent())
        .contains("a parent project has an invalid configuration");
    assertThat(response.getEntityContent()).doesNotContain(parent.get());
    assertThat(response.getEntityContent()).doesNotContain("ai-review/security.md");
    adminRestSession.get(url("current")).assertConflict();
    assertThat(adminRestSession.get(url("current")).getEntityContent())
        .contains("ai-review/security.md in project " + parent.get());
  }

  @Test
  public void actionsOfHiddenParentAreNotReturned() throws Exception {
    write(parent, "ai-review/security.md", "---\n{\"displayText\": \"S\"}\n---\nSecret prompt");
    hideParentFromUser();

    RestResponse response = userRestSession.get(url("current"));

    response.assertConflict();
    assertThat(response.getEntityContent()).contains("not visible to you");
    assertThat(response.getEntityContent()).doesNotContain("Secret prompt");
    assertThat(
            getActions(adminRestSession.get(url("current"))).actions().stream().map(ActionInfo::id))
        .contains("security");
  }

  @Test
  public void bundledActionsNeedNoParentAccess() throws Exception {
    hideParentFromUser();

    assertThat(getActions(userRestSession.get(url("current"))).actions()).hasSize(3);
  }

  @Test
  public void returnsRequestedRevision() throws Exception {
    String firstRevision = gApi.changes().id(change.id()).get().currentRevision;
    changeOperations
        .change(Change.id(gApi.changes().id(change.id()).get()._number))
        .newPatchset()
        .create();

    GetActions.Output output = getActions(userRestSession.get(url("1")));

    assertThat(output.revision()).isEqualTo(firstRevision);
    assertThat(gApi.changes().id(change.id()).get().currentRevision).isNotEqualTo(firstRevision);
  }

  @Test
  public void tooLargeResponseIsConflict() throws Exception {
    try (Repository repo = repoManager.openRepository(child);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      TestRepository<Repository>.CommitBuilder commit =
          testRepo.branch(RefNames.REFS_CONFIG).commit();
      for (int i = 0; i < 40; i++) {
        commit.add(
            "ai-review/large-" + i + ".md",
            "---\n{\"displayText\": \"L\"}\n---\n" + "x".repeat(60_000));
      }
      commit.create();
    }
    projectCache.evictAndReindex(child);

    RestResponse response = userRestSession.get(url("current"));

    response.assertConflict();
    assertThat(response.getEntityContent()).contains("larger than 2097152 bytes");
  }

  /** Makes the parent project invisible to {@code user}, while the child stays readable. */
  private void hideParentFromUser() {
    projectOperations
        .project(parent)
        .forUpdate()
        .add(deny(Permission.READ).ref("refs/heads/*").group(ANONYMOUS_USERS))
        .add(deny(Permission.READ).ref("refs/meta/version").group(ANONYMOUS_USERS))
        .update();
    projectOperations
        .project(child)
        .forUpdate()
        .add(allow(Permission.READ).ref("refs/heads/*").group(REGISTERED_USERS))
        .setExclusiveGroup(permissionKey(Permission.READ).ref("refs/heads/*"), true)
        .update();
  }

  private String url(String revision) {
    return String.format(
        "/changes/%s/revisions/%s/%s~%s",
        change.id(), revision, "ai-review-agent-provider", ACTIONS_ENDPOINT);
  }

  private GetActions.Output getActions(RestResponse response) throws Exception {
    response.assertOK();
    try (JsonReader reader = new JsonReader(response.getReader())) {
      reader.setStrictness(Strictness.LENIENT);
      return newGson().fromJson(reader, GetActions.Output.class);
    }
  }

  /** Writes directly to refs/meta/config. */
  private void write(Project.NameKey project, String path, String content) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      testRepo.branch(RefNames.REFS_CONFIG).commit().add(path, content).create();
    }
    projectCache.evictAndReindex(project);
  }
}
