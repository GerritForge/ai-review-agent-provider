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
import static java.util.stream.Collectors.joining;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import java.util.stream.IntStream;
import org.junit.Test;

/**
 * Pins the core patch endpoint behaviour that the web UI relies on to build the AI review prompt.
 */
@TestPlugin(
    name = "ai-review-agent-provider",
    sysModule = "com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
    apiModule = "com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule")
@Sandboxed
public class PatchContextIT extends AbstractTokenIT {
  private static final String FILE_NAME = "large.txt";
  private static final int LINE_COUNT = 100;
  private static final int CHANGED_LINE = 50;
  private static final String CHANGE_SUBJECT = "Modify a line in the middle";
  private static final String CHANGE_BODY = "Explain why the line in the middle changes.";

  @Test
  public void shouldReturnRawPatchWithLimitedContext() throws Exception {
    String patch = getPatch(createLargeFileChange(), 10);

    assertThat(patch).contains("Subject: [PATCH] " + CHANGE_SUBJECT);
    assertThat(patch).contains(CHANGE_BODY);
    assertThat(patch).contains("diff --git a/" + FILE_NAME + " b/" + FILE_NAME);
    assertThat(patch).contains("@@ -40,21 +40,21 @@");
    assertThat(patch).contains("\n-" + line(CHANGED_LINE) + "\n");
    assertThat(patch).contains("\n+" + line(CHANGED_LINE) + " modified\n");
    assertThat(patch).doesNotContain(line(1));
    assertThat(patch).doesNotContain(line(LINE_COUNT));
  }

  @Test
  public void shouldReturnRawPatchWithWholeFileForLargeContext() throws Exception {
    String patch = getPatch(createLargeFileChange(), 1_000_000);

    assertThat(patch).contains("@@ -1,100 +1,100 @@");
    assertThat(patch).contains("\n-" + line(CHANGED_LINE) + "\n");
    assertThat(patch).contains("\n+" + line(CHANGED_LINE) + " modified\n");
    assertThat(patch).contains(line(1));
    assertThat(patch).contains(line(LINE_COUNT));
  }

  private String createLargeFileChange() throws Exception {
    pushFactory
        .create(admin.newIdent(), testRepo, "Add large file", FILE_NAME, fileContent(false))
        .to("refs/heads/master")
        .assertOkStatus();
    PushOneCommit.Result change =
        pushFactory
            .create(
                admin.newIdent(),
                testRepo,
                CHANGE_SUBJECT + "\n\n" + CHANGE_BODY,
                FILE_NAME,
                fileContent(true))
            .to("refs/for/master");
    change.assertOkStatus();
    return change.getChangeId();
  }

  private String getPatch(String changeId, int context) throws Exception {
    RestResponse patchResponse =
        adminRestSession.get(
            "/changes/" + changeId + "/revisions/current/patch?raw&context=" + context);
    patchResponse.assertOK();
    return patchResponse.getEntityContent();
  }

  private static String fileContent(boolean modified) {
    return IntStream.rangeClosed(1, LINE_COUNT)
        .mapToObj(i -> modified && i == CHANGED_LINE ? line(i) + " modified" : line(i))
        .collect(joining("\n", "", "\n"));
  }

  private static String line(int number) {
    return String.format("line-%03d", number);
  }
}
