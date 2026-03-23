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

package com.gerritforge.gerrit.plugins.ai.provider;

import com.google.inject.AbstractModule;

public class TestAiReviewProviderModule extends AbstractModule {
  static final String TEST_PROVIDER_KEY = "test-provider";

  @Override
  protected void configure() {
    install(new AiReviewProviderModule(TEST_PROVIDER_KEY));
  }
}
