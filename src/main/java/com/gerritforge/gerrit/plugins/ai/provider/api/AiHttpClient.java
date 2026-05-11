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
import java.util.function.Function;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;

public interface AiHttpClient extends HttpClient, Closeable {

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
  HttpResponse execute(
      HttpUriRequest request,
      Function<Integer, Boolean> acceptedStatus,
      Function<String, String> errorFromBody)
      throws IOException;
}
