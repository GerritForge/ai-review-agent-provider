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

import static com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule.AI_REVIEW_ENDPOINT;
import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.Change;
import org.junit.Test;

@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class AiCodeReviewIT extends AbstractTokenIT {

  @Test
  public void shouldGenerateAiCodeReviewWithUserToken() throws Exception {
    AddToken.Input input = new AddToken.Input();
    input.token = "my-secret-token";
    input.plugin = TEST_PROVIDER_PLUGIN_NAME;

    userRestSession.put(getAddTokenUri("self"), input).assertCreated();

    PushOneCommit.Result changeToReview = createChange();
    changeToReview.assertOkStatus();

    RestResponse aiReviewResult =
        userRestSession.post(
            getAiCodeReviewUri(changeToReview.getChange().change()),
            new AiCodeReview.Input(TEST_PROVIDER_PLUGIN_NAME, FAKE_NUTS_MODEL, "Review this"));
    aiReviewResult.assertOK();
    AiCodeReview.Output aiReviewOutput =
        readContentFromJson(aiReviewResult, AiCodeReview.Output.class);
    assertThat(aiReviewOutput.text()).isEqualTo(AI_FEEDBACK_THIS_IS_A_REALLY_COOL_CODE_LGTM);
  }

  @Test
  public void shouldReturnBadReqeustForUnknownAiReviewProvider() throws Exception {
    PushOneCommit.Result changeToReview = createChange();
    changeToReview.assertOkStatus();

    RestResponse aiReviewResult =
        userRestSession.post(
            getAiCodeReviewUri(changeToReview.getChange().change()),
            new AiCodeReview.Input("non-registered-ai-plugin", "", ""));
    aiReviewResult.assertBadRequest();
  }

  @Test
  public void shouldReturnNotFoundWithoutUserToken() throws Exception {
    PushOneCommit.Result changeToReview = createChange();
    changeToReview.assertOkStatus();

    RestResponse aiReviewResult =
        userRestSession.post(
            getAiCodeReviewUri(changeToReview.getChange().change()),
            new AiCodeReview.Input(TEST_PROVIDER_PLUGIN_NAME, FAKE_NUTS_MODEL, ""));
    aiReviewResult.assertNotFound();
  }

  private String getAiCodeReviewUri(Change change) {
    return String.join(
        "/",
        "/changes",
        change.getProject().get() + "~" + change.getId().get(),
        pluginName + "~" + AI_REVIEW_ENDPOINT);
  }
}
