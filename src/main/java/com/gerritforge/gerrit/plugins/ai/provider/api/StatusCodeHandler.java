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

@FunctionalInterface
public interface StatusCodeHandler {

  /**
   * Processes an HTTP status code and returns true if successful, false otherwise.
   *
   * @param statusCode The response status code to process
   * @return true if the request is successful, false otherwise
   */
  boolean isSuccessful(int statusCode);
}
