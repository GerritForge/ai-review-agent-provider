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

import static com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule.API_TOKEN_ENDPOINT;
import static com.gerritforge.gerrit.plugins.ai.provider.TestAiReviewProviderModule.TEST_PROVIDER_KEY;
import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.secureconfig.Codec;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.TestAiReviewProviderModule")
public class AddTokenIT extends LightweightPluginDaemonTest {
  private String pluginName;
  private VersionedAiUserData.Factory tokenDataFactory;
  private Codec codec;

  @Before
  public void setUp() {
    codec = plugin.getSysInjector().getInstance(Codec.class);
    tokenDataFactory = plugin.getSysInjector().getInstance(VersionedAiUserData.Factory.class);
    pluginName = plugin.getSysInjector().getInstance(Key.get(String.class, PluginName.class));
  }

  @Test
  public void shouldAddTokenForSelf() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-secret-token";

    userRestSession.put(getAddTokenUri("self"), input).assertCreated();

    assertTokenCorrectlySet(user.id(), "my-secret-token");
  }

  @Test
  public void shouldUpdateTokenForSelf() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-initial-token";

    userRestSession.put(getAddTokenUri("self"), input).assertCreated();
    assertTokenCorrectlySet(user.id(), "my-initial-token");

    input.token = "my-updated-token";
    userRestSession.put(getAddTokenUri("self"), input).assertCreated();
    assertTokenCorrectlySet(user.id(), "my-updated-token");
  }

  @Test
  public void adminShouldAddTokenForOtherUser() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-secret-token";

    adminRestSession.put(getAddTokenUri(user.id().toString()), input).assertCreated();

    assertTokenCorrectlySet(user.id(), "my-secret-token");
  }

  @Test
  public void shouldReturnForbiddenWhenModifyingAnotherUser() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "token";

    userRestSession.put(getAddTokenUri(admin.id().toString()), input).assertForbidden();
  }

  @Test
  public void shouldReturnBadRequestOnMissingToken() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = null;

    adminRestSession.put(getAddTokenUri("self"), input).assertBadRequest();
  }

  @Test
  public void shouldReturnBadRequestOnEmptyToken() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "";

    adminRestSession.put(getAddTokenUri("self"), input).assertBadRequest();
  }

  private void assertTokenCorrectlySet(Account.Id accountId, String token)
      throws IOException, ConfigInvalidException {
    assertThat(tokenDataFactory.create(accountId).load().getToken(TEST_PROVIDER_KEY))
        .hasValue(codec.encode(token));
  }

  private String getAddTokenUri(String account) {
    return String.join("/", "/accounts", account, pluginName) + "~" + API_TOKEN_ENDPOINT;
  }
}
