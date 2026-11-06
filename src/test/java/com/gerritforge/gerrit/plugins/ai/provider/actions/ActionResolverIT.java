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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.util.Modules;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.actions.ActionResolverIT$SmallLimits",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class ActionResolverIT extends LightweightPluginDaemonTest {
  private static final ActionLimits LIMITS = new ActionLimits(100, 3, 50);

  /** The plugin with limits that tests reach with a few short files. */
  public static class SmallLimits extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new AiReviewProviderModule())
              .with(binder -> binder.bind(ActionLimits.class).toInstance(LIMITS)));
    }
  }

  @Inject private ProjectOperations projectOperations;

  private ActionResolver resolver;
  private Project.NameKey parent;
  private Project.NameKey child;

  @Before
  public void setUp() throws Exception {
    resolver = plugin.getSysInjector().getInstance(ActionResolver.class);
    parent = projectOperations.newProject().create();
    child = projectOperations.newProject().parent(parent).create();
  }

  @Test
  public void unconfiguredProjectHasNoActions() throws Exception {
    EffectiveActions actions = resolve(child);

    assertThat(actions.actions()).isEmpty();
    assertThat(actions.disabledActionIds()).isEmpty();
  }

  @Test
  public void allProjectsActionIsInheritedByGrandchild() throws Exception {
    write(allProjects, "security", action("Security review", 20, "Check {{patch}}"));

    EffectiveActions actions = resolve(child);

    assertThat(ids(actions)).containsExactly("security");
    ActionDefinition.Enabled security = find(actions, "security");
    assertThat(security.displayText()).isEqualTo("Security review");
    assertThat(security.contextLines()).isEqualTo(20);
    assertThat(security.prompt()).isEqualTo("Check {{patch}}");
  }

  @Test
  public void actionsAreSortedById() throws Exception {
    write(allProjects, "zeta", action("Zeta", "Z"));
    write(parent, "alpha", action("Alpha", "A"));
    write(child, "beta", action("Beta", "B"));

    assertThat(ids(resolve(child))).containsExactly("alpha", "beta", "zeta").inOrder();
  }

  @Test
  public void childFileReplacesParentFileEntirely() throws Exception {
    write(parent, "security", action("Security (team)", 20, "Team prompt"));
    write(child, "security", action("Security (strict)", "Strict prompt"));

    ActionDefinition.Enabled security = find(resolve(child), "security");

    assertThat(security.displayText()).isEqualTo("Security (strict)");
    assertThat(security.contextLines()).isEqualTo(ActionFileParser.DEFAULT_CONTEXT_LINES);
    assertThat(security.prompt()).isEqualTo("Strict prompt");
    assertThat(find(resolve(parent), "security").displayText()).isEqualTo("Security (team)");
  }

  @Test
  public void disabledFileHidesInheritedActionAndIsReported() throws Exception {
    write(allProjects, "security", action("Security", "S"));
    write(child, "security", DISABLED);
    write(child, "review-change", DISABLED);

    EffectiveActions actions = resolve(child);

    assertThat(actions.actions()).isEmpty();
    assertThat(actions.disabledActionIds()).containsExactly("review-change", "security").inOrder();
    assertThat(ids(resolve(parent))).containsExactly("security");
  }

  @Test
  public void deletingOverrideRevealsInheritedAction() throws Exception {
    write(parent, "security", action("Security (team)", "Team prompt"));
    write(child, "security", DISABLED);
    assertThat(ids(resolve(child))).doesNotContain("security");

    delete(child, "security");

    assertThat(find(resolve(child), "security").displayText()).isEqualTo("Security (team)");
  }

  @Test
  public void descendantReEnablesDisabledAction() throws Exception {
    Project.NameKey grandchild = projectOperations.newProject().parent(child).create();
    write(parent, "security", action("Security (team)", "Team prompt"));
    write(child, "security", DISABLED);
    write(grandchild, "security", action("Security (again)", "Again"));

    assertThat(ids(resolve(child))).doesNotContain("security");
    EffectiveActions actions = resolve(grandchild);
    assertThat(find(actions, "security").displayText()).isEqualTo("Security (again)");
    assertThat(actions.disabledActionIds()).isEmpty();
  }

  @Test
  public void invalidWinningFileHidesOnlyItsAction() throws Exception {
    write(allProjects, "security", action("Security", "Prompt"));
    write(parent, "security", "---\n{\"displayText\": 1}\n---\nCheck");
    write(parent, "style", action("Style", "S"));

    EffectiveActions actions = resolve(child);

    assertThat(ids(actions)).containsExactly("style");
    assertThat(actions.disabledActionIds()).containsExactly("security");
  }

  @Test
  public void invalidOverriddenFileIsNotUsed() throws Exception {
    write(parent, "security", "not an action file");
    write(child, "security", action("Security", "Check"));

    assertThat(find(resolve(child), "security").displayText()).isEqualTo("Security");
    assertThat(resolve(parent).disabledActionIds()).containsExactly("security");
  }

  @Test
  public void invalidFileNameDefinesNoAction() throws Exception {
    writeFiles(child, Map.of("ai-review/Invalid_Name.md", action("Invalid", "I")));

    EffectiveActions actions = resolve(child);

    assertThat(actions.actions()).isEmpty();
    assertThat(actions.disabledActionIds()).isEmpty();
  }

  @Test
  public void tooManyFilesInProjectIsLoadingError() throws Exception {
    writeActions(parent, "action-", LIMITS.maxFiles() + 1);

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Project " + parent.get() + ": ai-review/ holds more action files than the limit of 3");
  }

  @Test
  public void inheritedActionsDoNotCountTowardLimits() throws Exception {
    writeActions(allProjects, "all-", LIMITS.maxFiles());
    writeLongActions(parent, "L");
    writeActions(child, "child-", LIMITS.maxFiles());

    assertThat(resolve(child).actions()).hasSize(2 * LIMITS.maxFiles() + 2);
  }

  @Test
  public void missingParentFallsBackToAllProjectsLikeCore() throws Exception {
    write(allProjects, "security", action("Security", "S"));
    write(parent, "team", action("Team", "T"));
    setParent(child, "does-not-exist");

    assertThat(ids(resolve(child))).containsExactly("security");
  }

  private static final String DISABLED = "---\n{\"enabled\": false}\n---\n";

  private static String action(String displayText, String prompt) {
    return String.format("---\n{\"displayText\": \"%s\"}\n---\n%s", displayText, prompt);
  }

  private static String action(String displayText, int contextLines, String prompt) {
    return String.format(
        "---\n{\"displayText\": \"%s\", \"contextLines\": %d}\n---\n%s",
        displayText, contextLines, prompt);
  }

  /** Writes {@code count} enabled actions whose ids start with {@code prefix}. */
  private void writeActions(Project.NameKey project, String prefix, int count) throws Exception {
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i < count; i++) {
      files.put("ai-review/" + prefix + i + ".md", action("A" + i, "A"));
    }
    writeFiles(project, files);
  }

  /** Writes two actions whose labels and prompts reach the limit when {@code firstLabel} is "L". */
  private void writeLongActions(Project.NameKey project, String firstLabel) throws Exception {
    String prompt = "x".repeat(LIMITS.maxTotalChars() / 2 - 1);
    writeFiles(
        project,
        Map.of(
            "ai-review/long-1.md", action(firstLabel, prompt),
            "ai-review/long-2.md", action("L", prompt)));
  }

  private EffectiveActions resolve(Project.NameKey project) throws Exception {
    return resolver.resolve(ImmutableList.copyOf(projectCache.get(project).orElseThrow().tree()));
  }

  private static ImmutableList<String> ids(EffectiveActions actions) {
    return actions.actions().stream()
        .map(ActionDefinition.Enabled::id)
        .collect(ImmutableList.toImmutableList());
  }

  private static ActionDefinition.Enabled find(EffectiveActions actions, String id) {
    return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
  }

  private void write(Project.NameKey project, String id, String content) throws Exception {
    writeFiles(project, Map.of(ActionFileParser.FOLDER + id + ActionFileParser.EXTENSION, content));
  }

  private void delete(Project.NameKey project, String id) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      testRepo
          .branch(RefNames.REFS_CONFIG)
          .commit()
          .rm(ActionFileParser.FOLDER + id + ActionFileParser.EXTENSION)
          .create();
    }
    projectCache.evictAndReindex(project);
  }

  /** Sets the parent by editing project.config of {@code project} directly. */
  private void setParent(Project.NameKey project, String parentName) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      RevCommit head = testRepo.getRevWalk().parseCommit(repo.resolve(RefNames.REFS_CONFIG));
      String config =
          new String(
              testRepo
                  .getRevWalk()
                  .getObjectReader()
                  .open(testRepo.get(head.getTree(), "project.config"))
                  .getBytes(),
              UTF_8);
      Config parsed = new Config();
      parsed.fromText(config);
      parsed.setString("access", null, "inheritFrom", parentName);
      testRepo
          .branch(RefNames.REFS_CONFIG)
          .commit()
          .add("project.config", parsed.toText())
          .create();
    }
    projectCache.evictAndReindex(project);
  }

  /** Writes directly to refs/meta/config. */
  private void writeFiles(Project.NameKey project, Map<String, String> files) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      TestRepository<Repository>.CommitBuilder commit =
          testRepo.branch(RefNames.REFS_CONFIG).commit();
      for (Map.Entry<String, String> file : files.entrySet()) {
        commit.add(file.getKey(), file.getValue());
      }
      commit.create();
    }
    projectCache.evictAndReindex(project);
  }
}
