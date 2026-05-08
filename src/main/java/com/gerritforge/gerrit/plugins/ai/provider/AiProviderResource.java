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

import com.google.gerrit.extensions.restapi.RestResource;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.inject.TypeLiteral;

public class AiProviderResource implements RestResource {
  public static final TypeLiteral<RestView<AiProviderResource>> AI_PROVIDER_KIND =
      new TypeLiteral<>() {};

  private final String plugin;

  public AiProviderResource(String plugin) {
    this.plugin = plugin;
  }

  public String getPlugin() {
    return plugin;
  }
}
