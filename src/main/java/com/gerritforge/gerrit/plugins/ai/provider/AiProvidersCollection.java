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

import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
class AiProvidersCollection implements ChildCollection<AccountResource, AiProviderResource> {

  private final Provider<GetAiProviders> list;
  private final DynamicMap<RestView<AiProviderResource>> views;

  @Inject
  AiProvidersCollection(
      Provider<GetAiProviders> list, DynamicMap<RestView<AiProviderResource>> views) {
    this.list = list;
    this.views = views;
  }

  @Override
  public RestView<AccountResource> list() throws RestApiException {
    return list.get();
  }

  @Override
  public AiProviderResource parse(AccountResource parent, IdString aiPlugin) {
    return new AiProviderResource(parent.getUser(), aiPlugin.get());
  }

  @Override
  public DynamicMap<RestView<AiProviderResource>> views() {
    return views;
  }
}
