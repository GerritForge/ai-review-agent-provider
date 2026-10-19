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

import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionDefinition;
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionResolver;
import com.gerritforge.gerrit.plugins.ai.provider.actions.ActionsLoadException;
import com.gerritforge.gerrit.plugins.ai.provider.actions.EffectiveActions;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.kohsuke.args4j.Option;

/**
 * Lists the AI review actions of a project. A client that passes the version it holds gets only the
 * version back while it is still current.
 */
public class GetActions implements RestReadView<ProjectResource> {
  public record ActionInfo(String id, String displayText, int contextLines, String prompt) {}

  /** {@code actions} and {@code disabledActionIds} are left out when the client is up to date. */
  public record Output(
      String version,
      @Nullable List<ActionInfo> actions,
      @Nullable Set<String> disabledActionIds) {}

  private final ActionResolver resolver;

  @Option(name = "--version", metaVar = "VERSION", usage = "version of the actions the client has")
  private String clientVersion;

  @Inject
  GetActions(ActionResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public Response<Output> apply(ProjectResource rsrc)
      throws AuthException, ResourceConflictException, IOException {
    if (!rsrc.getUser().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    // Read the hierarchy once, so that the version and the actions describe the same configs.
    ImmutableList<ProjectState> hierarchy = ImmutableList.copyOf(rsrc.getProjectState().tree());
    String version = ActionResolver.version(hierarchy);
    if (version.equals(clientVersion)) {
      return Response.ok(new Output(version, null, null));
    }

    EffectiveActions effective;
    try {
      effective = resolver.resolve(hierarchy);
    } catch (ActionsLoadException e) {
      throw new ResourceConflictException(e.getMessage());
    }
    return Response.ok(
        new Output(
            version,
            effective.actions().stream().map(GetActions::toInfo).toList(),
            effective.disabledActionIds()));
  }

  private static ActionInfo toInfo(ActionDefinition.Enabled action) {
    return new ActionInfo(
        action.id(), action.displayText(), action.contextLines(), action.prompt());
  }
}
