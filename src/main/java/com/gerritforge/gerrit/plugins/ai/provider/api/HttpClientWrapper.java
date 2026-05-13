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

import java.io.Closeable;
import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

class HttpClientWrapper implements HttpClient, Closeable {
  private final CloseableHttpClient delegate;

  HttpClientWrapper(CloseableHttpClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public HttpResponse execute(HttpUriRequest request) throws IOException, ClientProtocolException {
    return delegate.execute(request);
  }

  @Override
  public HttpResponse execute(HttpUriRequest request, HttpContext context)
      throws IOException, ClientProtocolException {
    return delegate.execute(request, context);
  }

  @Override
  public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler)
      throws IOException, ClientProtocolException {
    return delegate.execute(request, responseHandler);
  }

  @Override
  public <T> T execute(
      HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context)
      throws IOException, ClientProtocolException {
    return delegate.execute(request, responseHandler, context);
  }

  @Override
  public HttpResponse execute(HttpHost target, HttpRequest request)
      throws IOException, ClientProtocolException {
    return delegate.execute(target, request);
  }

  @Override
  public HttpResponse execute(HttpHost target, HttpRequest request, HttpContext context)
      throws IOException, ClientProtocolException {
    return delegate.execute(target, request, context);
  }

  @Override
  public <T> T execute(
      HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler)
      throws IOException, ClientProtocolException {
    return delegate.execute(target, request, responseHandler);
  }

  @Override
  public <T> T execute(
      HttpHost target,
      HttpRequest request,
      ResponseHandler<? extends T> responseHandler,
      HttpContext context)
      throws IOException, ClientProtocolException {
    return delegate.execute(target, request, responseHandler, context);
  }

  @Deprecated
  @Override
  public ClientConnectionManager getConnectionManager() {
    return delegate.getConnectionManager();
  }

  @Deprecated
  @Override
  public HttpParams getParams() {
    return delegate.getParams();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
