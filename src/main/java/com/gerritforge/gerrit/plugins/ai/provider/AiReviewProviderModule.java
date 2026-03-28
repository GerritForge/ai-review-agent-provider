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

import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.googlesource.gerrit.plugins.secureconfig.Codec;
import com.googlesource.gerrit.plugins.secureconfig.PBECodec;

public class AiReviewProviderModule extends RestApiModule {
  public static final String API_TOKEN_ENDPOINT = "apiToken";
  public static final String AI_REVIEW_ENDPOINT = "aiReview";

  @Override
  protected void configure() {
    put(AccountResource.ACCOUNT_KIND, API_TOKEN_ENDPOINT).to(AddToken.class);
    post(ChangeResource.CHANGE_KIND, AI_REVIEW_ENDPOINT).to(AiCodeReview.class);

    install(new FactoryModuleBuilder().build(VersionedAiUserData.Factory.class));
    bind(Codec.class).to(PBECodec.class);
  }
}
