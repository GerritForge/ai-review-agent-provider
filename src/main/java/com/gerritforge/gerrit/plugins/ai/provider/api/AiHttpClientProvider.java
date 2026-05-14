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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;

@Singleton
class AiHttpClientProvider implements Provider<AiHttpClient>, LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AiHttpClientImpl client = new AiHttpClientImpl(createDefaultHttpClient());

  private static class AiTooManyRequestsRetryStrategy implements ServiceUnavailableRetryStrategy {

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MS = 5000L;

    @Override
    public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
      boolean shouldRetry =
          executionCount <= MAX_RETRIES
              && response.getStatusLine().getStatusCode() == HttpStatus.SC_TOO_MANY_REQUESTS;
      if (shouldRetry) {
        logger.atInfo().log(
            "Received HTTP status 429. Retrying request (attempt %d/%d) after %d ms",
            executionCount, MAX_RETRIES, RETRY_INTERVAL_MS);
      }
      return shouldRetry;
    }

    @Override
    public long getRetryInterval() {
      return RETRY_INTERVAL_MS;
    }
  }

  @Override
  public AiHttpClient get() {
    return client;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    try {
      client.close();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to close HTTP client");
    }
  }

  private static CloseableHttpClient createDefaultHttpClient() {
    RequestConfig globalConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build();
    return HttpClients.custom()
        .setDefaultRequestConfig(globalConfig)
        .setServiceUnavailableRetryStrategy(new AiTooManyRequestsRetryStrategy())
        .build();
  }
}
