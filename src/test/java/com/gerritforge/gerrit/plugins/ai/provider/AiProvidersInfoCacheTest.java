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

import static com.gerritforge.gerrit.plugins.ai.provider.AiProvidersInfoCacheImpl.AI_MODELS_BY_PLUGIN;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.common.cache.Cache;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AiProvidersInfoCacheTest extends AbstractDaemonTest {
  private static final String TEST_PLUGIN = "test-plugin";
  private static final String TEST_API_KEY = "test-api-key";

  @Inject(optional = true)
  AiProvidersInfoCache aiProvidersInfoCache;

  @Named(AI_MODELS_BY_PLUGIN)
  @Inject(optional = true)
  Cache<String, LinkedHashSet<String>> aiProvidersModels;

  @Mock AiReviewProvider aiReviewProviderMock;

  @Before
  public void setup() {
    server
        .getTestInjector()
        .createChildInjector(AiProvidersInfoCacheImpl.getModule())
        .injectMembers(this);
  }

  @Test
  public void cacheShouldReturnModelsFromAiReviewProvider() {
    Set<String> expectedModels = Set.of("model1", "model2");
    doReturn(expectedModels).when(aiReviewProviderMock).getModels(eq(TEST_API_KEY));

    AiProvidersInfoCache.ProviderInfo providerInfo =
        aiProvidersInfoCache.getProviderInfo(TEST_PLUGIN, aiReviewProviderMock, TEST_API_KEY);

    assertThat(providerInfo.models()).containsExactlyElementsIn(expectedModels);
    assertThat(aiProvidersModels.getIfPresent(TEST_PLUGIN))
        .containsExactlyElementsIn(expectedModels);
  }

  @Test
  public void cacheShouldReturnEmptyReviewProviderWhenUsingInvalidKey() {
    doThrow(new IllegalStateException("AI failing"))
        .when(aiReviewProviderMock)
        .getModels(eq(TEST_API_KEY));

    AiProvidersInfoCache.ProviderInfo providerInfo =
        aiProvidersInfoCache.getProviderInfo(TEST_PLUGIN, aiReviewProviderMock, TEST_API_KEY);

    assertThat(providerInfo.models()).isEmpty();
    assertThat(aiProvidersModels.getIfPresent(TEST_PLUGIN)).isNull();
  }
}
