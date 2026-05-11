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

package com.gerritforge.gerrit.plugins.ai.provider.api;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

@VisibleForTesting
public class AiHttpClientImpl extends HttpClientWrapper implements AiHttpClient {

  @VisibleForTesting
  public AiHttpClientImpl(CloseableHttpClient delegate) {
    super(delegate);
  }

  @Override
  public HttpResponse execute(
      HttpUriRequest request,
      Function<Integer, Boolean> acceptedStatus,
      Function<String, String> errorFromBody)
      throws IOException {
    HttpResponse response = super.execute(request);

    int statusCode = response.getStatusLine().getStatusCode();
    if (!acceptedStatus.apply(statusCode)) {
      String errorMsg =
          String.format(
              "Failed to execute %s %s: HTTP %d: %s",
              request.getMethod(),
              request.getURI(),
              statusCode,
              errorFromBody != null
                  ? errorFromBody.apply(
                      EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8))
                  : "");
      throw new IOException(errorMsg);
    }

    return response;
  }
}
