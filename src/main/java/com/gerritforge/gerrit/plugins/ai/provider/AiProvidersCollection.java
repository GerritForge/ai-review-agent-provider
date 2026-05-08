package com.gerritforge.gerrit.plugins.ai.provider;

import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.project.BranchResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
public class AiProvidersCollection implements ChildCollection<AccountResource, AiProviderResource> {

	private final Provider<GetAiProviders> list;
	private final DynamicMap<RestView<AiProviderResource>> views;

	@Inject
	public AiProvidersCollection(Provider<GetAiProviders> list, DynamicMap<RestView<AiProviderResource>> views) {
		this.list = list;
		this.views = views;
	}

	@Override
	public RestView<AccountResource> list() throws RestApiException {
		return list.get();
	}

	@Override
	public AiProviderResource parse(AccountResource parent, IdString aiPlugin) throws ResourceNotFoundException, Exception {
		return new AiProviderResource(aiPlugin.get());
	}

	@Override
	public DynamicMap<RestView<AiProviderResource>> views() {
		return views;
	}
}
