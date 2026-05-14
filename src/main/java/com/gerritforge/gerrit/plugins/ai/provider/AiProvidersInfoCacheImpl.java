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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.cache.CacheModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import java.time.Duration;
import java.util.HashSet;

class AiProvidersInfoCacheImpl implements AiProvidersInfoCache {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration DEFAULT_CACHE_MAX_AGE = Duration.ofDays(1);
  private static final int CACHE_VERSION = 1;

  @VisibleForTesting public static final String AI_MODELS_BY_PLUGIN = "models";

  private final Cache<String, HashSet<String>> modelsCache;

  @Inject
  AiProvidersInfoCacheImpl(
      @Named(AI_MODELS_BY_PLUGIN) Cache<String, HashSet<String>> aiProvidersModels) {
    this.modelsCache = aiProvidersModels;
  }

  @Override
  public ProviderInfo getProviderInfo(
      String pluginName, AiReviewProvider aiProvider, String apiKey) {
    try {
      return new AiProvidersInfoCache.ProviderInfo(
          pluginName,
          aiProvider.getDisplayName(),
          modelsCache.get(pluginName, () -> new HashSet<>(aiProvider.getModels(apiKey))),
          true);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Unable to retrieve full AI provider info for plugin '%s' from cache", pluginName);
      return AiProvidersInfoCache.emptyAiProviderInfo(pluginName, aiProvider.getDisplayName());
    }
  }

  public static CacheModule getModule() {
    return new CacheModule() {
      @Override
      protected void configure() {
        persist(AI_MODELS_BY_PLUGIN, String.class, new TypeLiteral<HashSet<String>>() {})
            .expireAfterWrite(DEFAULT_CACHE_MAX_AGE)
            .version(CACHE_VERSION);
        bind(AiProvidersInfoCache.class).to(AiProvidersInfoCacheImpl.class).in(Scopes.SINGLETON);
      }
    };
  }
}
