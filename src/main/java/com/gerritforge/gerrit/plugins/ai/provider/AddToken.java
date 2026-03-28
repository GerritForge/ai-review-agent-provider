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

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.secureconfig.Codec;

@Singleton
public class AddToken implements RestModifyView<AccountResource, AddToken.Input> {
  private final Provider<CurrentUser> currentUser;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final Codec codec;
  private final PermissionBackend permissionBackend;

  public static class Input {
    public String plugin;
    public String token;
  }

  @Inject
  AddToken(
      Provider<CurrentUser> currentUser,
      VersionedAiUserData.Factory tokenDataFactory,
      Codec codec,
      PermissionBackend permissionBackend) {
    this.currentUser = currentUser;
    this.tokenDataFactory = tokenDataFactory;
    this.codec = codec;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<?> apply(AccountResource resource, AddToken.Input input) throws Exception {
    if (input.token == null || input.token.isBlank()) {
      throw new BadRequestException("token must be present and non-empty");
    }

    IdentifiedUser iu = resource.getUser();
    Account.Id accountId = iu.getAccountId();

    if (!iu.hasSameAccountId(currentUser.get())) {
      permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    }

    String encryptedToken = codec.encode(input.token.trim());
    VersionedAiUserData tokenData = tokenDataFactory.create(accountId).load();
    tokenData.setToken(input.plugin, encryptedToken);

    return Response.created();
  }
}
