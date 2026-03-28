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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.secureconfig.Codec;

@Singleton
public class GetToken implements RestReadView<AccountResource> {
  private final DynamicItem<AiReviewProvider> providerKey;
  private final Provider<CurrentUser> currentUser;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final Codec codec;

  public static class Output {
    public String token;
  }

  @Inject
  GetToken(
      DynamicItem<AiReviewProvider> providerKey,
      Provider<CurrentUser> currentUser,
      VersionedAiUserData.Factory tokenDataFactory,
      Codec codec) {
    this.providerKey = providerKey;
    this.currentUser = currentUser;
    this.tokenDataFactory = tokenDataFactory;
    this.codec = codec;
  }

  @Override
  public Response<Output> apply(AccountResource resource) throws Exception {
    if (providerKey.get() == null) {
      throw new BadRequestException("No AI review agent providers registered");
    }

    IdentifiedUser iu = resource.getUser();
    Account.Id accountId = iu.getAccountId();

    if (!iu.hasSameAccountId(currentUser.get())) {
      throw new AuthException("Cannot read another user's token");
    }

    String storedToken =
        tokenDataFactory
            .create(accountId)
            .load()
            .getToken(providerKey.get().key())
            .orElseThrow(() -> new ResourceNotFoundException("Token not set"));
    Output out = new Output();
    out.token = codec.decode(storedToken);
    return Response.ok(out);
  }
}
