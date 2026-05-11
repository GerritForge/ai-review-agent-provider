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
import org.apache.http.impl.client.HttpClients;

@Singleton
class AiHttpClientProvider implements Provider<AiHttpClient>, LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AiHttpClientImpl client = new AiHttpClientImpl(HttpClients.createDefault());

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
}
