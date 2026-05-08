package com.gerritforge.gerrit.plugins.ai.provider;

import com.google.gerrit.extensions.restapi.RestResource;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.inject.TypeLiteral;

public class AiProviderResource implements RestResource {
	public static final TypeLiteral<RestView<AiProviderResource>> AI_PROVIDER_KIND = new TypeLiteral<>() {};

	private final String plugin;

	public AiProviderResource(String plugin) {
		this.plugin = plugin;
	}

	public String getPlugin() {
		return plugin;
	}
}
