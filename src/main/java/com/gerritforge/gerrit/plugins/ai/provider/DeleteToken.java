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

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.secureconfig.Codec;

@Singleton
public class DeleteToken implements RestModifyView<AiProviderResource, Input> {
  private final DynamicSet<AiReviewProvider> aiReviewProvidersSet;
  private final Provider<CurrentUser> currentUser;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final PermissionBackend permissionBackend;

  @Inject
  DeleteToken(
      DynamicSet<AiReviewProvider> aiReviewProvidersSet,
      Provider<CurrentUser> currentUser,
      VersionedAiUserData.Factory tokenDataFactory,
      PermissionBackend permissionBackend) {
    this.aiReviewProvidersSet = aiReviewProvidersSet;
    this.currentUser = currentUser;
    this.tokenDataFactory = tokenDataFactory;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<?> apply(AiProviderResource resource, Input input) throws Exception {
    if (resource.getPlugin() == null || resource.getPlugin().isBlank()) {
      throw new BadRequestException("token provider plugin be present and non-empty");
    }
    if (!aiReviewProvidersSet.plugins().contains(resource.getPlugin())) {
      throw new BadRequestException(
          "token refers to a non-existent or not-loaded ai-review-provider " + resource.getPlugin());
    }

    IdentifiedUser iu = currentUser.get().asIdentifiedUser();
    Account.Id accountId = iu.getAccountId();

    if (!iu.hasSameAccountId(currentUser.get())) {
      permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    }

    VersionedAiUserData tokenData = tokenDataFactory.create(accountId).load();
    tokenData.removeToken(resource.getPlugin());

    return Response.none();
  }
}
