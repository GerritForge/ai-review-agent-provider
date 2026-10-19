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

import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionFilesCheck;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.RefReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
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
 * Rejects updates of {@code refs/meta/config} that add or modify an invalid AI review action file:
 * new commits, whether uploaded for review or pushed directly; pushes of commits the server already
 * has; and the commits a submit creates. Deleting a file, even an invalid one, is always allowed.
 */
@Singleton
public class ActionFilesValidator
    implements CommitValidationListener,
        RefOperationValidationListener,
        OnSubmitValidationListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String INVALID = "invalid AI review action files";

  private final GitRepositoryManager repoManager;

  @Inject
  ActionFilesValidator(GitRepositoryManager repoManager) {
    this.repoManager = repoManager;
  }

  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
      throws CommitValidationException {
    if (!RefNames.REFS_CONFIG.equals(event.getRefName())) {
      return List.of();
    }
    ActionFilesCheck.Result result;
    try {
      ObjectId parentTree =
          event.commit.getParentCount() > 0
              ? event.revWalk.parseCommit(event.commit.getParent(0)).getTree()
              : null;
      result =
          ActionFilesCheck.check(
              event.revWalk.getObjectReader(), parentTree, event.commit.getTree());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read AI review action files of commit %s in project %s",
          event.commit.name(), event.project.getName());
      throw new CommitValidationException("cannot read AI review action files", e);
    }

    List<CommitValidationMessage> messages = new ArrayList<>();
    result
        .errors()
        .forEach(e -> messages.add(new CommitValidationMessage(e, ValidationMessage.Type.ERROR)));
    result
        .warnings()
        .forEach(w -> messages.add(new CommitValidationMessage(w, ValidationMessage.Type.WARNING)));
    if (!result.errors().isEmpty()) {
      throw new CommitValidationException(INVALID, messages);
    }
    return messages;
  }

  /** Covers pushes that move the ref to commits the commit validation does not see. */
  @Override
  public List<ValidationMessage> onRefOperation(RefReceivedEvent event) throws ValidationException {
    ReceiveCommand command = event.command;
    if (!RefNames.REFS_CONFIG.equals(event.getRefName())
        || command.getType() == ReceiveCommand.Type.DELETE) {
      return List.of();
    }
    try (Repository repo = repoManager.openRepository(event.getProjectNameKey());
        RevWalk rw = new RevWalk(repo)) {
      // Warnings are left to the commit validation, which runs next for new commits.
      checkUpdate(rw, command.getOldId(), command.getNewId());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read AI review action files in project %s", event.getProjectNameKey());
      throw new ValidationException("cannot read AI review action files", e);
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
      checkUpdate(args.getRevWalk(), command.getOldId(), command.getNewId());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read AI review action files in project %s", args.getProject());
      throw new ValidationException("cannot read AI review action files", e);
    }
  }

  private static void checkUpdate(RevWalk rw, ObjectId oldId, ObjectId newId)
      throws IOException, ValidationException {
    RevCommit newCommit = rw.parseCommit(newId);
    ObjectId oldTree = ObjectId.zeroId().equals(oldId) ? null : rw.parseCommit(oldId).getTree();
    ActionFilesCheck.Result result =
        ActionFilesCheck.check(rw.getObjectReader(), oldTree, newCommit.getTree());
    if (!result.errors().isEmpty()) {
      throw new ValidationException(INVALID + ":\n" + String.join("\n", result.errors()));
    }
  }
}
