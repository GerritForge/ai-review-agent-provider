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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.gerrit.server.git.validators.CommitValidationInfo.NO_METADATA;

import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionFilesCheck;
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionLimits;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.RefReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationInfo;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.OnSubmitValidationListener;
import com.google.gerrit.server.git.validators.RefOperationValidationListener;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Rejects updates of {@code refs/meta/config} that add or modify an invalid AI review action file.
 * Every new commit is checked against its parents, and every update of the ref, by push or submit,
 * against the old tip.
 */
@Singleton
public class ActionFilesValidator
    implements CommitValidationListener,
        RefOperationValidationListener,
        OnSubmitValidationListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String INVALID = "invalid AI review action files";

  private final GitRepositoryManager repoManager;
  private final ActionLimits limits;

  @Inject
  ActionFilesValidator(GitRepositoryManager repoManager, ActionLimits limits) {
    this.repoManager = repoManager;
    this.limits = limits;
  }

  @Override
  public CommitValidationInfo validateCommit(CommitReceivedEvent event)
      throws CommitValidationException {
    if (!RefNames.REFS_CONFIG.equals(event.getRefName())) {
      return CommitValidationInfo.notApplicable(NO_METADATA);
    }
    ActionFilesCheck.Result result;
    try {
      List<ObjectId> parentTrees = new ArrayList<>();
      for (RevCommit parent : event.commit.getParents()) {
        parentTrees.add(event.revWalk.parseCommit(parent).getTree());
      }
      result =
          ActionFilesCheck.check(
              event.revWalk.getObjectReader(), parentTrees, event.commit.getTree(), limits);
    } catch (IOException e) {
      throw new CommitValidationException(cannotRead(event.project.getName(), e), e);
    }

    ImmutableList<CommitValidationMessage> messages =
        ImmutableList.<CommitValidationMessage>builder()
            .addAll(messages(result.errors(), ValidationMessage.Type.ERROR))
            .addAll(messages(result.warnings(), ValidationMessage.Type.WARNING))
            .build();
    if (!result.errors().isEmpty()) {
      throw new CommitValidationException(INVALID, messages);
    }
    return CommitValidationInfo.passed(NO_METADATA, messages);
  }

  @Override
  public List<ValidationMessage> onRefOperation(RefReceivedEvent event) throws ValidationException {
    ReceiveCommand command = event.command;
    if (!RefNames.REFS_CONFIG.equals(event.getRefName())
        || command.getType() == ReceiveCommand.Type.DELETE) {
      return List.of();
    }
    try (Repository repo = repoManager.openRepository(event.getProjectNameKey());
        RevWalk rw = new RevWalk(repo)) {
      // Warnings are left to the commit validation, which sees new commits.
      rejectIfInvalid(checkUpdate(rw, command.getOldId(), command.getNewId()));
    } catch (IOException e) {
      throw new ValidationException(cannotRead(event.getProjectNameKey().get(), e), e);
    }
    return List.of();
  }

  /** Covers the merge, rebase or cherry-pick commit that a submit creates. */
  @Override
  public void preBranchUpdate(Arguments args) throws ValidationException {
    ReceiveCommand command = args.getCommands().get(RefNames.REFS_CONFIG);
    if (command == null || command.getType() == ReceiveCommand.Type.DELETE) {
      return;
    }
    try {
      rejectIfInvalid(checkUpdate(args.getRevWalk(), command.getOldId(), command.getNewId()));
    } catch (IOException e) {
      throw new ValidationException(cannotRead(args.getProject().get(), e), e);
    }
  }

  private static void rejectIfInvalid(ActionFilesCheck.Result result) throws ValidationException {
    if (!result.errors().isEmpty()) {
      throw new ValidationException(INVALID + ":\n" + String.join("\n", result.errors()));
    }
  }

  /** Checks a move of the ref; core rejects anything but a commit as project configuration. */
  private ActionFilesCheck.Result checkUpdate(RevWalk rw, ObjectId oldId, ObjectId newId)
      throws IOException {
    if (!(rw.peel(rw.parseAny(newId)) instanceof RevCommit newCommit)) {
      return ActionFilesCheck.Result.OK;
    }
    List<ObjectId> oldTrees = new ArrayList<>();
    if (!ObjectId.zeroId().equals(oldId)
        && rw.peel(rw.parseAny(oldId)) instanceof RevCommit oldCommit) {
      oldTrees.add(oldCommit.getTree());
    }
    return ActionFilesCheck.check(rw.getObjectReader(), oldTrees, newCommit.getTree(), limits);
  }

  private static ImmutableList<CommitValidationMessage> messages(
      List<String> texts, ValidationMessage.Type type) {
    return texts.stream()
        .map(text -> new CommitValidationMessage(text, type))
        .collect(toImmutableList());
  }

  private static String cannotRead(String project, IOException e) {
    logger.atSevere().withCause(e).log("Cannot read AI review action files in project %s", project);
    return "cannot read AI review action files";
  }
}
