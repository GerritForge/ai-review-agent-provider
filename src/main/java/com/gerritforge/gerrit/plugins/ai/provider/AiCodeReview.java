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
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.secureconfig.Codec;

public class AiCodeReview implements RestModifyView<ChangeResource, AiCodeReview.Input> {

  public record Input (
    String plugin,
    String model,
    String prompt
  ) {}

  public record Output (
    String text
  ) {}

  private final DynamicMap<AiReviewProvider> aiReviewProvidersMap;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final Codec codec;

  @Inject
  AiCodeReview(
      DynamicMap<AiReviewProvider> aiReviewProvidersMap,
      VersionedAiUserData.Factory tokenDataFactory,
      Codec codec) {
    this.aiReviewProvidersMap = aiReviewProvidersMap;
    this.tokenDataFactory = tokenDataFactory;
    this.codec = codec;
  }

  @Override
  public Response<?> apply(ChangeResource resource, Input input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {
    AiReviewProvider aiReviewProvider = aiReviewProvidersMap.get(input.plugin, input.model);

    if (aiReviewProvider == null) {
      throw new BadRequestException("No AI review agent providers registered");
    }

    Account.Id accountId = resource.getUser().getAccountId();

    String storedToken =
        tokenDataFactory
            .create(accountId)
            .load()
            .getToken(input.plugin)
            .orElseThrow(() -> new ResourceNotFoundException("Token not set"));
    String token = codec.decode(storedToken);

    AiCodeReview.Output resp = new AiCodeReview.Output(aiReviewProvider.review(token, input.model, input.prompt));
    return Response.ok(resp);
  }
}
