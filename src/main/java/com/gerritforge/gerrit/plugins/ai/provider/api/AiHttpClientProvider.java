package com.gerritforge.gerrit.plugins.ai.provider.api;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

@Singleton
class AiHttpClientProvider implements Provider<CloseableHttpClient>, LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final CloseableHttpClient client = HttpClients.createDefault();

  @Override
  public CloseableHttpClient get() {
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
