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

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "test-plugin",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class VersionedAiUserDataIT extends AbstractTokenIT {
  private VersionedAiUserData.Factory geminiTokenFactory;

  @Before
  public void setUp() throws Exception {
    super.setUp();
    geminiTokenFactory = plugin.getSysInjector().getInstance(VersionedAiUserData.Factory.class);
  }

  @Test
  public void getTokenReturnsEmptyIfUnset() throws Exception {
    assertThat(geminiTokenFactory.create(user.id()).load().getToken(TEST_PROVIDER_KEY))
        .isEqualTo(Optional.empty());
  }

  @Test
  public void setTokenPersistsGeminiConfig() throws Exception {
    String encryptedToken = "encrypted-token";

    VersionedAiUserData geminiToken = geminiTokenFactory.create(user.id()).load();
    geminiToken.setToken(TEST_PROVIDER_KEY, encryptedToken);

    assertThat(geminiTokenFactory.create(user.id()).load().getToken(TEST_PROVIDER_KEY))
        .hasValue(encryptedToken);
  }

  @Test
  public void setTokenOverwritesExistingValue() throws Exception {
    VersionedAiUserData geminiToken = geminiTokenFactory.create(user.id()).load();
    geminiToken.setToken(TEST_PROVIDER_KEY, "old-token");

    geminiToken = geminiTokenFactory.create(user.id()).load();
    geminiToken.setToken(TEST_PROVIDER_KEY, "new-token");

    assertThat(geminiTokenFactory.create(user.id()).load().getToken(TEST_PROVIDER_KEY))
        .hasValue("new-token");
  }
}
