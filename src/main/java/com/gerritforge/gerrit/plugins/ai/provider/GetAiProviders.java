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
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.NavigableSet;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class GetAiProviders implements RestReadView<AccountResource> {
  private final Provider<CurrentUser> currentUser;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final PermissionBackend permissionBackend;
  private final DynamicMap<AiReviewProvider> aiReviewProvidersMap;

  public record Output(Set<String> plugins) {}
  ;

  @Inject
  GetAiProviders(
      DynamicMap<AiReviewProvider> aiReviewProvidersMap,
      Provider<CurrentUser> currentUser,
      VersionedAiUserData.Factory tokenDataFactory,
      PermissionBackend permissionBackend) {
    this.aiReviewProvidersMap = aiReviewProvidersMap;
    this.currentUser = currentUser;
    this.tokenDataFactory = tokenDataFactory;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<Output> apply(AccountResource resource) throws Exception {
    IdentifiedUser iu = resource.getUser();
    Account.Id accountId = iu.getAccountId();

    if (!iu.hasSameAccountId(currentUser.get())) {
      permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    }

    NavigableSet<String> loadedProviders = aiReviewProvidersMap.plugins();
    VersionedAiUserData versionedAiUserData = tokenDataFactory.create(accountId).load();
    Set<String> providers =
        versionedAiUserData.getProviders().stream()
            .filter(p -> versionedAiUserData.getToken(p).isPresent())
            .filter(loadedProviders::contains)
            .collect(Collectors.toSet());

    return Response.ok(new Output(providers));
  }
}
