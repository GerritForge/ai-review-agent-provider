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
import java.io.Serializable;
import java.util.Set;

public interface AiProvidersInfoCache {

  record ProviderInfo(String plugin, String displayName, Set<String> models, boolean enabled)
      implements Serializable {}

  static ProviderInfo emptyAiProviderInfo(String pluginName, String displayName) {
    return new AiProvidersInfoCache.ProviderInfo(pluginName, displayName, Set.of(), false);
  }

  ProviderInfo getProviderInfo(String pluginName, AiReviewProvider aiReviewProvider, String apiKey);
}
