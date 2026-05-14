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
import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

@VisibleForTesting
public class AiHttpClientImpl extends HttpClientWrapper implements AiHttpClient {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting
  public AiHttpClientImpl(CloseableHttpClient delegate) {
    super(delegate);
  }

  @Override
  public CloseableHttpResponse execute(
      HttpUriRequest request, StatusCodeHandler acceptedStatus, ErrorBodyHandler errorFromBody)
      throws IOException, AiCodeReviewException {
    CloseableHttpResponse response = (CloseableHttpResponse) super.execute(request);

    int statusCode = response.getStatusLine().getStatusCode();
    if (!acceptedStatus.isSuccessful(statusCode)) {
      String errorMessage =
          errorFromBody != null ? errorFromBody.getErrorFromBody(getStringEntity(response)) : "";
      logger.atWarning().log(
          "Failed to execute %s %s: HTTP %d: %s",
          request.getMethod(), request.getURI(), statusCode, errorMessage);
      throw new AiCodeReviewException(statusCode, errorMessage);
    }

    return response;
  }

  private static String getStringEntity(CloseableHttpResponse response) throws IOException {
    return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
  }

  @Override
  public <T> T execute(
      HttpUriRequest request,
      StatusCodeHandler acceptedStatus,
      ErrorBodyHandler errorFromBody,
      ResponseBodyHandler<? extends T> responseBodyHandler)
      throws IOException, ClientProtocolException, AiCodeReviewException {
    try (CloseableHttpResponse response = execute(request, acceptedStatus, errorFromBody)) {
      return responseBodyHandler.handleResponse(getStringEntity(response));
    }
  }
}
