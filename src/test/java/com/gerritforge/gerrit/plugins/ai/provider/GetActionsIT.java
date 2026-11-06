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
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionLimits;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.util.Modules;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.GetActionsIT$SmallLimits",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class GetActionsIT extends LightweightPluginDaemonTest {
  private static final ActionLimits LIMITS = new ActionLimits(64 * 1024, 2, 2 << 20);

  /** The plugin with a file limit that a test reaches with a few files. */
  public static class SmallLimits extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new AiReviewProviderModule())
              .with(binder -> binder.bind(ActionLimits.class).toInstance(LIMITS)));
    }
  }

  @Inject private ProjectOperations projectOperations;

  private Project.NameKey parent;
  private Project.NameKey child;

  @Before
  public void setUp() throws Exception {
    parent = projectOperations.newProject().create();
    child = projectOperations.newProject().parent(parent).create();
  }

  @Test
  public void returnsActionsAndDisabledIds() throws Exception {
    write(
        child,
        "ai-review/security.md",
        "---\n{\"displayText\": \"Security\", \"contextLines\": 20}\n---\nCheck {{patch}}");
    write(child, "ai-review/review-change.md", "---\n{\"enabled\": false}\n---\n");

    JsonObject output = json(userRestSession.get(url()));

    assertThat(output.keySet()).containsExactly("version", "actions", "disabled_action_ids");
    assertThat(output.get("version").getAsString()).isNotEmpty();
    JsonObject security = output.getAsJsonArray("actions").get(0).getAsJsonObject();
    assertThat(security.keySet()).containsExactly("id", "display_text", "context_lines", "prompt");
    assertThat(security.get("id").getAsString()).isEqualTo("security");
    assertThat(security.get("display_text").getAsString()).isEqualTo("Security");
    assertThat(security.get("context_lines").getAsInt()).isEqualTo(20);
    assertThat(security.get("prompt").getAsString()).isEqualTo("Check {{patch}}");
    assertThat(output.getAsJsonArray("actions")).hasSize(1);
    assertThat(output.getAsJsonArray("disabled_action_ids").get(0).getAsString())
        .isEqualTo("review-change");
  }

  @Test
  public void projectWithoutActionFilesGetsEmptyLists() throws Exception {
    GetActions.Output output = getActions(userRestSession.get(url()));

    assertThat(output.actions()).isEmpty();
    assertThat(output.disabledActionIds()).isEmpty();
  }

  @Test
  public void currentVersionGetsNoActions() throws Exception {
    write(child, "ai-review/security.md", "---\n{\"displayText\": \"S\"}\n---\nCheck");
    String version = getActions(userRestSession.get(url())).version();

    JsonObject output = json(userRestSession.get(url(version)));

    assertThat(output.keySet()).containsExactly("version");
    assertThat(output.get("version").getAsString()).isEqualTo(version);
  }

  @Test
  public void versionChangesWithConfigOfParent() throws Exception {
    String before = getActions(userRestSession.get(url())).version();
    write(parent, "ai-review/security.md", "---\n{\"displayText\": \"S\"}\n---\nCheck");

    assertChangedSince(before);
  }

  @Test
  public void versionChangesWithParent() throws Exception {
    Project.NameKey other = projectOperations.newProject().create();
    String before = getActions(userRestSession.get(url())).version();

    gApi.projects().name(child.get()).parent(other.get());

    assertChangedSince(before);
  }

  @Test
  public void anonymousIsRejected() throws Exception {
    anonymousRestSession.get(url()).assertForbidden();
  }

  @Test
  public void invisibleProjectIsNotFound() throws Exception {
    projectOperations
        .project(child)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();

    userRestSession.get(url()).assertNotFound();
  }

  @Test
  public void loadingErrorIsConflictWithoutReason() throws Exception {
    for (int i = 0; i <= LIMITS.maxFiles(); i++) {
      write(parent, "ai-review/action-" + i + ".md", "---\n{\"displayText\": \"A\"}\n---\nA");
    }

    for (RestSession session : ImmutableList.of(userRestSession, adminRestSession)) {
      RestResponse response = session.get(url());

      response.assertConflict();
      assertThat(response.getEntityContent()).isEqualTo(GetActions.LOAD_ERROR);
    }
  }

  @Test
  public void actionsOfHiddenParentAreReturned() throws Exception {
    write(parent, "ai-review/security.md", "---\n{\"displayText\": \"S\"}\n---\nTeam prompt");
    hideParentFromUser();
    userRestSession.get("/projects/" + Url.encode(parent.get())).assertNotFound();

    assertThat(getActions(userRestSession.get(url())).actions().stream().map(ActionInfo::id))
        .containsExactly("security");
  }

  /** Asserts that a client holding version {@code before} gets a new version and the actions. */
  private void assertChangedSince(String before) throws Exception {
    GetActions.Output output = getActions(userRestSession.get(url(before)));
    assertThat(output.version()).isNotEqualTo(before);
    assertThat(output.actions()).isNotNull();
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

  private String url() {
    return String.format(
        "/projects/%s/%s~%s",
        Url.encode(child.get()), "ai-review-agent-provider", ACTIONS_ENDPOINT);
  }

  private String url(String version) {
    return url() + "?version=" + Url.encode(version);
  }

  private static JsonObject json(RestResponse response) throws Exception {
    response.assertOK();
    return JsonParser.parseReader(response.getReader()).getAsJsonObject();
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
