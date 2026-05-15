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

import com.gerritforge.gerrit.plugins.ai.provider.api.AiCodeReviewException;
import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.secureconfig.Codec;
import java.util.Set;

public class AiCodeReview implements RestModifyView<ChangeResource, AiCodeReview.Input> {

  public record Input(String plugin, String model, String prompt) {}

  public record ErrorInfo(int statusCode, String message) {

    static ErrorInfo fromException(AiCodeReviewException e) {
      return new ErrorInfo(e.getStatusCode(), e.getMessage());
    }
  }

  public record Output(@Nullable String text, @Nullable ErrorInfo error) {}

  private final DynamicSet<AiReviewProvider> aiReviewProvidersSet;
  private final VersionedAiUserData.Factory tokenDataFactory;
  private final Codec codec;
  private final AiProvidersInfoCache providersInfoCache;

  @Inject
  AiCodeReview(
      DynamicSet<AiReviewProvider> aiReviewProvidersSet,
      VersionedAiUserData.Factory tokenDataFactory,
      Codec codec,
      AiProvidersInfoCache providersInfoCache) {
    this.aiReviewProvidersSet = aiReviewProvidersSet;
    this.tokenDataFactory = tokenDataFactory;
    this.codec = codec;
    this.providersInfoCache = providersInfoCache;
  }

  @Override
  public Response<?> apply(ChangeResource resource, Input input) throws Exception {
    Set<Provider<AiReviewProvider>> aiReviewProviderSet =
        aiReviewProvidersSet.byPlugin(input.plugin);

    if (aiReviewProviderSet.isEmpty()) {
      throw new BadRequestException("No AI review agent providers registered");
    }
    if (aiReviewProviderSet.size() > 1) {
      throw new BadRequestException(
          "Multiple AI review agent providers registered for agent " + input.plugin);
    }

    Account.Id accountId = resource.getUser().getAccountId();

    String storedToken =
        tokenDataFactory
            .create(accountId)
            .load()
            .getToken(input.plugin)
            .orElseThrow(() -> new ResourceNotFoundException("Token not set"));
    String token = codec.decode(storedToken);

    AiReviewProvider aiReviewProvider = aiReviewProviderSet.iterator().next().get();
    Set<String> aiModels =
        providersInfoCache.getProviderInfo(input.plugin, aiReviewProvider, token).models();
    if (!aiModels.contains(input.model)) {
      throw new BadRequestException(
          "AiReviewProvider "
              + aiReviewProvider.getDisplayName()
              + " does not support "
              + input.model
              + " model");
    }

    AiCodeReview.Output resp;
    try {
      resp =
          new AiCodeReview.Output(aiReviewProvider.review(token, input.model, input.prompt), null);
      return Response.ok(resp);
    } catch (AiCodeReviewException e) {
      resp = new AiCodeReview.Output(null, ErrorInfo.fromException(e));
    }
    return Response.ok(resp);
  }
}
