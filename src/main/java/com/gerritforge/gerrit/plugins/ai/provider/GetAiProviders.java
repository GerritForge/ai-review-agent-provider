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
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.Extension;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class GetAiProviders implements RestReadView<AccountResource> {
  private final Provider<CurrentUser> currentUser;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final PermissionBackend permissionBackend;
  private final DynamicSet<AiReviewProvider> aiReviewProviders;

  public record ProviderInfo(
      String plugin, String displayName, Set<String> models, boolean enabled) {}

  public record Output(Set<ProviderInfo> providers) {}

  @Inject
  GetAiProviders(
      DynamicSet<AiReviewProvider> aiReviewProviders,
      Provider<CurrentUser> currentUser,
      VersionedAiUserData.Factory tokenDataFactory,
      PermissionBackend permissionBackend) {
    this.aiReviewProviders = aiReviewProviders;
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
    VersionedAiUserData versionedAiUserData = tokenDataFactory.create(accountId).load();

    Set<ProviderInfo> providersPlugins =
        StreamSupport.stream(aiReviewProviders.entries().spliterator(), false)
            .map(
                ext ->
                    getProviderInfo(
                        ext, versionedAiUserData.getToken(ext.getPluginName()).isPresent()))
            .collect(Collectors.toSet());
    return Response.ok(new Output(providersPlugins));
  }

  private ProviderInfo getProviderInfo(Extension<AiReviewProvider> ext, boolean enabled) {
    AiReviewProvider aiProvider = ext.get();
    return new ProviderInfo(
        ext.getPluginName(), aiProvider.getDisplayName(), aiProvider.getModels(), enabled);
  }
}
