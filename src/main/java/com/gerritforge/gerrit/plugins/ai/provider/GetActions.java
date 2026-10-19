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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionDefinition;
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionResolver;
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionsLoadException;
import com.gerritforge.gerrit.plugins.ai.provider.actions.EffectiveActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Lists the AI review actions of a revision's project, with prompts not yet expanded. The revision
 * is returned so that the client fetches the patch of the same revision.
 */
@Singleton
public class GetActions implements RestReadView<RevisionResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final int MAX_RESPONSE_BYTES = 2 << 20;

  public record ActionInfo(String id, String displayText, int contextLines, String prompt) {}

  public record Output(
      String revision,
      List<ActionInfo> actions,
      @Nullable String defaultActionId,
      String fingerprint) {}

  private final ProjectCache projectCache;
  private final ActionResolver resolver;
  private final PermissionBackend permissionBackend;

  @Inject
  GetActions(
      ProjectCache projectCache, ActionResolver resolver, PermissionBackend permissionBackend) {
    this.projectCache = projectCache;
    this.resolver = resolver;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<Output> apply(RevisionResource rsrc)
      throws AuthException, ResourceConflictException, IOException {
    if (!rsrc.getUser().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    ProjectState project =
        projectCache
            .get(rsrc.getProject())
            .orElseThrow(ProjectCache.illegalState(rsrc.getProject()));

    EffectiveActions effective;
    try {
      effective = resolver.resolve(project);
    } catch (ActionsLoadException e) {
      logger.atWarning().atMostEvery(1, MINUTES).log(
          "Cannot load AI review actions of project %s: %s", project.getName(), e.getMessage());
      throw new ResourceConflictException(
          canSeeAll(rsrc.getUser(), e.projects())
              ? e.getMessage()
              : String.format(
                  "AI review actions of project %s cannot be loaded: a parent project has an"
                      + " invalid configuration",
                  project.getName()));
    }

    // Change readers may see actions inherited from projects whose config they cannot read, but
    // not from projects they cannot see at all, such as a hidden project someone made the parent
    // of their own project.
    if (!canSeeAll(rsrc.getUser(), effective.sources())) {
      throw new ResourceConflictException(
          String.format(
              "AI review actions of project %s cannot be loaded: they come from a parent project"
                  + " that is not visible to you",
              project.getName()));
    }

    ImmutableList<ActionInfo> actions =
        effective.actions().stream()
            .map(GetActions::toInfo)
            .collect(ImmutableList.toImmutableList());
    if (jsonBytes(actions) > MAX_RESPONSE_BYTES) {
      throw new ResourceConflictException(
          String.format(
              "AI review actions of project %s are larger than %d bytes",
              project.getName(), MAX_RESPONSE_BYTES));
    }
    return Response.ok(
        new Output(
            rsrc.getPatchSet().commitId().name(),
            actions,
            effective.defaultActionId().orElse(null),
            fingerprint(actions)));
  }

  /**
   * Returns the size of the actions as sent, in compact JSON. The text is counted as it is written
   * and never held in memory.
   */
  private static long jsonBytes(ImmutableList<ActionInfo> actions) {
    Utf8ByteCounter counter = new Utf8ByteCounter();
    OutputFormat.JSON_COMPACT.newGson().toJson(actions, counter);
    return counter.bytes;
  }

  /** Error details name projects; a user who cannot see one of them gets no details. */
  private boolean canSeeAll(CurrentUser user, ImmutableSet<Project.NameKey> projects) {
    for (Project.NameKey project : projects) {
      try {
        if (!permissionBackend.user(user).project(project).test(ProjectPermission.ACCESS)) {
          return false;
        }
      } catch (PermissionBackendException e) {
        logger.atWarning().withCause(e).log("Cannot check access to project %s", project);
        return false;
      }
    }
    return true;
  }

  private static ActionInfo toInfo(ActionDefinition action) {
    return new ActionInfo(
        action.id(), action.displayText(), action.contextLines(), action.prompt());
  }

  /** Changes whenever any returned action changes, so clients can detect stale definitions. */
  private static String fingerprint(ImmutableList<ActionInfo> actions) {
    Hasher hasher = Hashing.sha256().newHasher();
    for (ActionInfo action : actions) {
      putString(hasher, action.id());
      putString(hasher, action.displayText());
      hasher.putInt(action.contextLines());
      putString(hasher, action.prompt());
    }
    return hasher.hash().toString();
  }

  private static void putString(Hasher hasher, String value) {
    hasher.putInt(value.length()).putString(value, UTF_8);
  }

  /** Counts the UTF-8 bytes of the characters written to it and discards them. */
  private static class Utf8ByteCounter extends Writer {
    long bytes;

    @Override
    public void write(char[] chars, int offset, int length) {
      for (int i = offset; i < offset + length; i++) {
        char c = chars[i];
        if (c < 0x80) {
          bytes += 1;
        } else if (c < 0x800 || Character.isSurrogate(c)) {
          // A surrogate pair is 4 bytes in UTF-8, 2 for each half.
          bytes += 2;
        } else {
          bytes += 3;
        }
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
