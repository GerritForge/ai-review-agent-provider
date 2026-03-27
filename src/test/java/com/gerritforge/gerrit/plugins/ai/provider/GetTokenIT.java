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
import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gson.Gson;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule")
@Sandboxed
public class GetTokenIT extends AbstractTokenIT {
  private final Gson gson = new Gson();

  @Test
  public void shouldGetTokenForSelf() throws Exception {
    String token = "my-secret-token";
    AddToken.Input input = new AddToken.Input();
    input.token = token;

    userRestSession.put(getTokenUri("self"), input).assertCreated();

    RestResponse response = userRestSession.get(getTokenUri("self"));
    response.assertOK();

    GetToken.Output out = gson.fromJson(response.getReader(), GetToken.Output.class);
    assertThat(out.token).isEqualTo(token);
  }

  @Test
  public void shouldReturnNotFoundWhenTokenNotSet() throws Exception {
    userRestSession.get(getTokenUri("self")).assertNotFound();
  }

  @Test
  public void shouldReturnForbiddenWhenGettingAnotherUserToken() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "admin-token";

    adminRestSession.put(getTokenUri("self"), input).assertCreated();

    userRestSession.get(getTokenUri(admin.id().toString())).assertForbidden();
  }

  @Test
  public void shouldReturnForbiddenWhenAdminGetsAnotherUserToken() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "user-token";

    userRestSession.put(getTokenUri("self"), input).assertCreated();

    adminRestSession.get(getTokenUri(user.id().toString())).assertForbidden();
  }

  @Test
  public void shouldNotReturnTokenFromDifferentProvider() throws Exception {
    String otherProvider = "other-provider";
    String otherToken = codec.encode("other-secret");
    tokenDataFactory.create(user.id()).load().setToken(otherProvider, otherToken);

    userRestSession.get(getTokenUri("self")).assertNotFound();
  }

  @Test
  public void shouldReturnOwnProviderTokenWhenMultipleExist() throws Exception {
    String otherProvider = "other-provider";
    String otherToken = codec.encode("other-secret");
    tokenDataFactory.create(user.id()).load().setToken(otherProvider, otherToken);

    String myToken = "my-secret-token";
    AddToken.Input input = new AddToken.Input();
    input.token = myToken;
    userRestSession.put(getTokenUri("self"), input).assertCreated();

    RestResponse response = userRestSession.get(getTokenUri("self"));
    response.assertOK();

    GetToken.Output out = gson.fromJson(response.getReader(), GetToken.Output.class);
    assertThat(out.token).isEqualTo(myToken);
  }

  private String getTokenUri(String account) {
    return String.join("/", "/accounts", account, pluginName) + "~" + API_TOKEN_ENDPOINT;
  }
}
