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

/**
 * AI Review Provider interface to a concrete LLMs implementation provided by a Gerrit plugin
 * through a DynamicItem.
 */
public interface AiReviewProvider {

  /**
   * Call the AI Review Provider model with a prompt and return the AI-generated content.
   *
   * @param apiToken authentication key for accessing the LLMs API
   * @param model LLMs model to use for the content generation
   * @param prompt prompt for requesting the review
   * @return the AI-generated review content
   */
  String review(String apiToken, String model, String prompt);
}
