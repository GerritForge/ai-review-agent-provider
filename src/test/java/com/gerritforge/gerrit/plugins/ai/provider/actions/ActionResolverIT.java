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

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class ActionResolverIT extends LightweightPluginDaemonTest {
  private static final ImmutableList<String> BUILT_IN_IDS =
      ImmutableList.of("review-change", "review-change-full", "review-commit");

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
  public void unconfiguredProjectGetsBuiltInActions() throws Exception {
    EffectiveActions actions = resolve(child);

    assertThat(ids(actions)).containsExactlyElementsIn(BUILT_IN_IDS).inOrder();
    assertThat(actions.actions().stream().map(ActionDefinition::contextLines))
        .containsExactly(10, 1000000, 3)
        .inOrder();
    assertThat(actions.defaultActionId()).hasValue("review-change");
  }

  @Test
  public void allProjectsActionIsInheritedByGrandchild() throws Exception {
    write(allProjects, "security", action("Security review", 20, "Check {{patch}}"));

    EffectiveActions actions = resolve(child);

    assertThat(ids(actions))
        .containsExactly("review-change", "review-change-full", "review-commit", "security")
        .inOrder();
    ActionDefinition security = find(actions, "security");
    assertThat(security.displayText()).isEqualTo("Security review");
    assertThat(security.contextLines()).isEqualTo(20);
    assertThat(security.prompt()).isEqualTo("Check {{patch}}");
  }

  @Test
  public void customActionsFollowBuiltInsSortedById() throws Exception {
    write(allProjects, "zeta", action("Zeta", "Z"));
    write(parent, "alpha", action("Alpha", "A"));
    write(child, "beta", action("Beta", "B"));

    assertThat(ids(resolve(child)))
        .containsExactly(
            "review-change", "review-change-full", "review-commit", "alpha", "beta", "zeta")
        .inOrder();
  }

  @Test
  public void childFileReplacesParentFileEntirely() throws Exception {
    write(parent, "security", action("Security (team)", 20, "Team prompt"));
    write(child, "security", action("Security (strict)", "Strict prompt"));

    ActionDefinition security = find(resolve(child), "security");

    assertThat(security.displayText()).isEqualTo("Security (strict)");
    assertThat(security.contextLines()).isEqualTo(ActionFileParser.DEFAULT_CONTEXT_LINES);
    assertThat(security.prompt()).isEqualTo("Strict prompt");
    assertThat(find(resolve(parent), "security").displayText()).isEqualTo("Security (team)");
  }

  @Test
  public void childFileReplacesBuiltInAction() throws Exception {
    write(child, "review-commit", action("Commit message", 5, "Improve {{patch}}"));

    ActionDefinition reviewCommit = find(resolve(child), "review-commit");

    assertThat(reviewCommit.displayText()).isEqualTo("Commit message");
    assertThat(reviewCommit.contextLines()).isEqualTo(5);
  }

  @Test
  public void disabledFileHidesInheritedAndBuiltInActions() throws Exception {
    write(allProjects, "security", action("Security", "S"));
    write(child, "security", DISABLED);
    write(child, "review-change", DISABLED);

    EffectiveActions actions = resolve(child);

    assertThat(ids(actions)).containsExactly("review-change-full", "review-commit").inOrder();
    assertThat(actions.defaultActionId()).hasValue("review-change-full");
    assertThat(ids(resolve(parent))).contains("security");
  }

  @Test
  public void allActionsDisabledHasNoDefault() throws Exception {
    for (String id : BUILT_IN_IDS) {
      write(child, id, DISABLED);
    }

    EffectiveActions actions = resolve(child);

    assertThat(actions.actions()).isEmpty();
    assertThat(actions.defaultActionId()).isEmpty();
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
    assertThat(find(resolve(grandchild), "security").displayText()).isEqualTo("Security (again)");
  }

  @Test
  public void invalidWinningFileIsLoadingError() throws Exception {
    write(parent, "security", action("Security (team)", "Team prompt"));
    write(child, "security", "---\n{\"displayText\": 1}\n---\nCheck");

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains("ai-review/security.md");
    assertThat(e).hasMessageThat().contains(child.get());
    assertThat(e).hasMessageThat().contains("\"displayText\" must be a string");
  }

  @Test
  public void invalidOverriddenFileIsNotUsed() throws Exception {
    write(parent, "security", "not an action file");
    write(child, "security", action("Security", "Check"));

    assertThat(find(resolve(child), "security").displayText()).isEqualTo("Security");
    assertThrows(ActionsLoadException.class, () -> resolve(parent));
  }

  @Test
  public void otherFilesAreIgnored() throws Exception {
    writeFile(child, "ai-review/agents/reviewer.md", "not an action file");
    writeFile(child, "ai-review/skills/java/SKILL.md", "not an action file");
    writeFile(child, "ai-review/notes.txt", "not an action file");
    writeFile(child, "ai-review/Invalid_Name.md", "not an action file");
    writeFile(child, "security.md", "not an action file");

    assertThat(ids(resolve(child))).containsExactlyElementsIn(BUILT_IN_IDS).inOrder();
  }

  @Test
  public void unknownFieldsAreIgnored() throws Exception {
    write(
        child,
        "java-review",
        "---\n{\"displayText\": \"Java\", \"paths\": [\"java/**\"]}\n---\nCheck {{patch}}");

    assertThat(find(resolve(child), "java-review").displayText()).isEqualTo("Java");
  }

  @Test
  public void tooManyFilesInProjectIsLoadingError() throws Exception {
    Map<String, String> files = new HashMap<>();
    for (int i = 0; i <= ActionFiles.MAX_FILES; i++) {
      files.put("ai-review/action-" + i + ".md", DISABLED);
    }
    writeFiles(parent, files);

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains(parent.get());
    assertThat(e).hasMessageThat().contains("limit of 100");
  }

  @Test
  public void tooManyEnabledActionsIsLoadingError() throws Exception {
    Map<String, String> parentFiles = new HashMap<>();
    Map<String, String> childFiles = new HashMap<>();
    for (int i = 0; i < 99; i++) {
      parentFiles.put("ai-review/parent-" + i + ".md", action("P" + i, "P"));
      childFiles.put("ai-review/child-" + i + ".md", action("C" + i, "C"));
    }
    writeFiles(parent, parentFiles);
    writeFiles(child, childFiles);

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains("limit of 200");
  }

  @Test
  public void missingParentIsLoadingError() throws Exception {
    setParent(child, "does-not-exist");

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains("does-not-exist");
  }

  @Test
  public void parentCycleIsLoadingError() throws Exception {
    setParent(parent, child.get());

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains("Cycle in the parents of project " + child.get());
    assertThat(e.projects()).containsExactly(child, parent);
  }

  @Test
  public void walkStopsAtAllProjects() throws Exception {
    setParent(allProjects, parent.get());
    write(parent, "security", action("Security", "S"));

    assertThat(ids(resolve(project))).doesNotContain("security");
    assertThat(ids(resolve(child))).contains("security");
  }

  @Test
  public void oversizedFileIsLoadingError() throws Exception {
    write(child, "security", action("Security", "x".repeat(ActionFileParser.MAX_FILE_BYTES)));

    ActionsLoadException e = assertThrows(ActionsLoadException.class, () -> resolve(child));
    assertThat(e).hasMessageThat().contains("larger than 65536 bytes");
  }

  @Test
  public void acceptsLimitsExactly() throws Exception {
    Map<String, String> parentFiles = new HashMap<>();
    Map<String, String> childFiles = new HashMap<>();
    for (int i = 0; i < ActionFiles.MAX_FILES; i++) {
      parentFiles.put("ai-review/parent-" + i + ".md", action("P" + i, "P"));
    }
    // 3 bundled + 100 + 97 = 200 enabled actions.
    for (int i = 0; i < ActionResolver.MAX_ACTIONS - 3 - ActionFiles.MAX_FILES; i++) {
      childFiles.put("ai-review/child-" + i + ".md", action("C" + i, "C"));
    }
    writeFiles(parent, parentFiles);
    writeFiles(child, childFiles);

    assertThat(resolve(child).actions()).hasSize(ActionResolver.MAX_ACTIONS);
  }

  @Test
  public void reportsProjectsWhoseFilesDefineActions() throws Exception {
    write(parent, "security", action("Security", "S"));
    write(child, "review-commit", action("Commit", "C"));
    write(child, "review-change", DISABLED);

    assertThat(resolve(child).sources()).containsExactly(child, parent);
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

  private EffectiveActions resolve(Project.NameKey project) throws Exception {
    return resolver.resolve(projectCache.get(project).orElseThrow());
  }

  private static ImmutableList<String> ids(EffectiveActions actions) {
    return actions.actions().stream()
        .map(ActionDefinition::id)
        .collect(ImmutableList.toImmutableList());
  }

  private static ActionDefinition find(EffectiveActions actions, String id) {
    return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
  }

  private void write(Project.NameKey project, String id, String content) throws Exception {
    writeFile(project, ActionFileParser.FOLDER + id + ActionFileParser.EXTENSION, content);
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

  private void writeFile(Project.NameKey project, String path, String content) throws Exception {
    writeFiles(project, Map.of(path, content));
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
      testRepo
          .branch(RefNames.REFS_CONFIG)
          .commit()
          .add("project.config", config + "[access]\n  inheritFrom = " + parentName + "\n")
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
