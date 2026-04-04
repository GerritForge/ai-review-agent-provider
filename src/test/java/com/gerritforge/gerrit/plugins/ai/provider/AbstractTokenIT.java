package com.gerritforge.gerrit.plugins.ai.provider;

import static com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule.API_TOKEN_ENDPOINT;

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.secureconfig.Codec;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

@Ignore
public class AbstractTokenIT extends LightweightPluginDaemonTest {
  protected static final String TEST_PROVIDER_PLUGIN_NAME = "test";
  protected static final String FAKE_NUTS_MODEL = "nuts-1.0";
  protected static final String FAKE_PEARS_MODEL = "pears-experimental";
  public static final String FAKE_AI_DISPLAY_NAME = "Fake AI";
  public static final Set<String> FAKE_AI_MODELS = Set.of(FAKE_NUTS_MODEL, FAKE_PEARS_MODEL);
  protected static GetAiProviders.ProviderInfo FAKE_PROVIDER_INFO =
      new GetAiProviders.ProviderInfo(
          TEST_PROVIDER_PLUGIN_NAME, FAKE_AI_DISPLAY_NAME, FAKE_AI_MODELS, true);
  public static final String AI_FEEDBACK_THIS_IS_A_REALLY_COOL_CODE_LGTM =
      "This is a really cool code, LGTM";
  protected String pluginName;
  protected Codec codec;
  protected VersionedAiUserData.Factory tokenDataFactory;
  private AutoCloseable aiProviderTest;

  @Before
  public void setUp() throws Exception {
    codec = plugin.getSysInjector().getInstance(Codec.class);
    tokenDataFactory = plugin.getSysInjector().getInstance(VersionedAiUserData.Factory.class);
    pluginName = plugin.getSysInjector().getInstance(Key.get(String.class, PluginName.class));

    aiProviderTest = installPlugin(TEST_PROVIDER_PLUGIN_NAME, FakeAiReviewAgentModule.class);
  }

  @After
  public void tearDown() throws Exception {
    if (aiProviderTest != null) {
      aiProviderTest.close();
    }
  }

  protected String getAddTokenUri(String account) {
    return String.join("/", "/accounts", account, pluginName) + "~" + API_TOKEN_ENDPOINT;
  }

  protected <T> T readContentFromJson(RestResponse r, Class<T> clazz) throws Exception {
    r.assertOK();
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      jsonReader.setStrictness(Strictness.LENIENT);
      return newGson().fromJson(jsonReader, clazz);
    }
  }

  protected <T> T readContentFromJson(RestResponse r, TypeToken<T> typeToken) throws Exception {
    r.assertOK();
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      jsonReader.setStrictness(Strictness.LENIENT);
      return newGson().fromJson(jsonReader, typeToken.getType());
    }
  }

  static class FakeAiReviewAgentModule extends AbstractModule {
    @Override
    protected void configure() {
      DynamicSet.bind(binder(), AiReviewProvider.class).toInstance(FakeAiReviewProvider.INSTANCE);
    }
  }

  static class FakeAiReviewProvider implements AiReviewProvider {
    public static final FakeAiReviewProvider INSTANCE = new FakeAiReviewProvider();

    @Override
    public String getDisplayName() {
      return FAKE_AI_DISPLAY_NAME;
    }

    @Override
    public Set<String> getModels() {
      return FAKE_AI_MODELS;
    }

    @Override
    public String review(String apiToken, String model, String prompt) {
      return AI_FEEDBACK_THIS_IS_A_REALLY_COOL_CODE_LGTM;
    }
  }
}
