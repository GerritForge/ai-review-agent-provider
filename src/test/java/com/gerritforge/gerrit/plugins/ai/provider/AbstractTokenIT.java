package com.gerritforge.gerrit.plugins.ai.provider;

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
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
  protected static final String TEST_PROVIDER_MODEL_NAME = "testModel";
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

  static class FakeAiReviewAgentModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(AiReviewProvider.class)
          .annotatedWith(Exports.named(TEST_PROVIDER_MODEL_NAME))
          .toInstance(FakeAiReviewProvider.INSTANCE);
    }
  }

  static class FakeAiReviewProvider implements AiReviewProvider {
    public static final FakeAiReviewProvider INSTANCE = new FakeAiReviewProvider();

    @Override
    public String getDisplayName() {
      return "Fake AI";
    }

    @Override
    public Set<String> getModels() {
      return Set.of("nuts-1.0", "pears-experimental");
    }

    @Override
    public String review(String apiToken, String model, String prompt) {
      return "This is a really cool code, LGTM";
    }
  }
}
