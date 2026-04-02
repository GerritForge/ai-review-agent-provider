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

import static com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule.API_PROVIDERS_ENDPOINT;
import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.Account;
import java.io.IOException;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class AddTokenIT extends AbstractTokenIT {

  @Test
  public void shouldAddTokenForSelf() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-secret-token";
    input.plugin = TEST_PROVIDER_PLUGIN_NAME;

    userRestSession.put(getAddTokenUri("self"), input).assertCreated();

    assertTokenCorrectlySet(user.id(), "my-secret-token");

    assertThat(getAiProviders(userRestSession.get(getTokenProvidersUri("self"))))
        .containsExactly(FAKE_PROVIDER_INFO);
  }

  @Test
  public void shouldNotHaveTokenProvidersByDefault() throws Exception {
    assertThat(getAiProviders(userRestSession.get(getTokenProvidersUri("self")))).isEmpty();
  }

  @Test
  public void shouldUpdateTokenForSelf() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-initial-token";
    input.plugin = TEST_PROVIDER_PLUGIN_NAME;

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
    input.plugin = TEST_PROVIDER_PLUGIN_NAME;

    adminRestSession.put(getAddTokenUri(user.id().toString()), input).assertCreated();

    assertTokenCorrectlySet(user.id(), "my-secret-token");

    assertThat(getAiProviders(userRestSession.get(getTokenProvidersUri("self"))))
        .containsExactly(FAKE_PROVIDER_INFO);
  }

  @Test
  public void shouldReturnForbiddenWhenModifyingAnotherUser() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "token";
    input.plugin = TEST_PROVIDER_PLUGIN_NAME;

    userRestSession.put(getAddTokenUri(admin.id().toString()), input).assertForbidden();
    userRestSession.get(getTokenProvidersUri(admin.id().toString())).assertForbidden();
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

  @Test
  public void shouldReturnBadRequestOnNonExistentAiProviderPlugin() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.plugin = "non-existent-plugin";
    input.token = "token";

    adminRestSession.put(getAddTokenUri("self"), input).assertBadRequest();
  }

  private void assertTokenCorrectlySet(Account.Id accountId, String token)
      throws ConfigInvalidException, IOException {
    assertTokenCorrectlySet(accountId, token, TEST_PROVIDER_PLUGIN_NAME);
  }

  private void assertTokenCorrectlySet(Account.Id accountId, String token, String provider)
      throws IOException, ConfigInvalidException {
    assertThat(tokenDataFactory.create(accountId).load().getToken(provider))
        .hasValue(codec.encode(token));
  }

  private Set<GetAiProviders.ProviderInfo> getAiProviders(RestResponse tokenProviders)
      throws Exception {
    tokenProviders.assertOK();
    return readContentFromJson(tokenProviders, GetAiProviders.Output.class).providers();
  }

  private String getTokenProvidersUri(String account) {
    return String.join("/", "/accounts", account, pluginName) + "~" + API_PROVIDERS_ENDPOINT;
  }
}
