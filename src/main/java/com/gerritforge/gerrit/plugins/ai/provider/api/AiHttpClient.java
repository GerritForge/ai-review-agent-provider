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

import com.google.common.net.MediaType;
import java.io.Closeable;
import java.io.IOException;
import java.time.temporal.ValueRange;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.message.BasicHeader;

public interface AiHttpClient extends HttpClient, Closeable {

  /** Handlers that accepts any HTTP status codes between 200 (OK) and 202 (ACCEPTED) included */
  StatusCodeHandler HTTP_STATUS_OK_HANDLER =
      ValueRange.of(HttpStatus.SC_OK, HttpStatus.SC_ACCEPTED)::isValidIntValue;

  /** Create an HTTP Content-Type header */
  default Header contentType(String contentType) {
    return new BasicHeader(HttpHeaders.CONTENT_TYPE, contentType);
  }

  /** Create an HTTP Content-Type: application/json header */
  default Header contentTypeApplicationJson() {
    return contentType(MediaType.JSON_UTF_8.toString());
  }

  /** Create an HTTP Accept header */
  default Header accept(String contentType) {
    return new BasicHeader(HttpHeaders.ACCEPT, contentType);
  }

  /** Create an HTTP Accept application/json header */
  default Header acceptApplicationJson() {
    return accept(MediaType.JSON_UTF_8.toString());
  }

  /**
   * Execute an HTTP POST request expecting a 20[0-2] status code, with a logic for extracting the
   * error message from the response body.
   *
   * @param uri the URI of the AI service
   * @param headers the additional HTTP headers for the POST request
   * @param postBody the HTTP POST request body {@link HttpEntity}
   * @param errorFromBody function for converting the response body in case of errors, `null` for no
   *     error description
   * @param responseBodyHandler the response handler
   * @return the HTTP response if the status code is accepted as a success
   * @throws IOException if any errors occur or if the status of the response does not satisfy the
   *     accepted status function
   * @throws AiCodeReviewException if the AI LLM reported a human-readable error
   */
  default <T> T post(
      String uri,
      Header[] headers,
      HttpEntity postBody,
      ErrorBodyHandler errorFromBody,
      ResponseBodyHandler<? extends T> responseBodyHandler)
      throws IOException, AiCodeReviewException {
    HttpPost httpPost = new HttpPost(uri);
    httpPost.setHeaders(headers);
    httpPost.setEntity(postBody);
    return execute(httpPost, HTTP_STATUS_OK_HANDLER, errorFromBody, responseBodyHandler);
  }

  /**
   * Execute an HTTP GET request expecting a 20[0-2] status code, with a logic for extracting the
   * error message from the response body.
   *
   * @param uri the URI of the AI service
   * @param headers the additional HTTP headers for the GET request
   * @param errorFromBody function for converting the response body in case of errors, `null` for no
   *     error description
   * @param responseBodyHandler the response handler
   * @return the HTTP response if the status code is accepted as a success
   * @throws IOException if any errors occur or if the status of the response does not satisfy the
   *     accepted status function
   * @throws AiCodeReviewException if the AI LLM reported a human-readable error
   */
  default <T> T get(
      String uri,
      Header[] headers,
      ErrorBodyHandler errorFromBody,
      ResponseBodyHandler<? extends T> responseBodyHandler)
      throws IOException, AiCodeReviewException {
    HttpGet httpGet = new HttpGet(uri);
    httpGet.setHeaders(headers);
    return execute(httpGet, HTTP_STATUS_OK_HANDLER, errorFromBody, responseBodyHandler);
  }

  /**
   * Execute an HTTP request by checking the status code and logic for extracting the associated
   * error message from the response body.
   *
   * @param request the HTTP request to a target AI URI
   * @param acceptedStatus function for accepting the response's status code
   * @param errorFromBody function for converting the response body in case of errors, `null` for no
   *     error description
   * @return the HTTP response if the status code is accepted as a success
   * @throws IOException if any errors occur or if the status of the response does not satisfy the
   *     accepted status function
   */
  CloseableHttpResponse execute(
      HttpUriRequest request, StatusCodeHandler acceptedStatus, ErrorBodyHandler errorFromBody)
      throws IOException, AiCodeReviewException;

  /**
   * Executes HTTP request using the default context and processes the response using the given
   * response handler.
   *
   * @param request the request to execute
   * @param acceptedStatus function for accepting the response's status code
   * @param errorFromBody function for converting the response body in case of errors, `null` for no
   *     error description
   * @param responseBodyHandler the response handler
   * @return the response object as generated by the response handler.
   * @throws IOException in case of a problem or the connection was aborted, or if any errors occur
   *     or if the status of the response does not satisfy the * accepted status function
   * @throws ClientProtocolException in case of an http protocol error
   */
  <T> T execute(
      HttpUriRequest request,
      StatusCodeHandler acceptedStatus,
      ErrorBodyHandler errorFromBody,
      ResponseBodyHandler<? extends T> responseBodyHandler)
      throws IOException, ClientProtocolException, AiCodeReviewException;
}
