package com.gerritforge.gerrit.plugins.ai.provider;

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.secureconfig.Codec;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

@Ignore
public class AbstractTokenIT extends LightweightPluginDaemonTest {
  protected static final String TEST_PROVIDER_KEY = "test";
  protected String pluginName;
  protected Codec codec;
  protected VersionedAiUserData.Factory tokenDataFactory;
  private AutoCloseable aiProviderTest;

  @Before
  public void setUp() throws Exception {
    codec = plugin.getSysInjector().getInstance(Codec.class);
    tokenDataFactory = plugin.getSysInjector().getInstance(VersionedAiUserData.Factory.class);
    pluginName = plugin.getSysInjector().getInstance(Key.get(String.class, PluginName.class));

    aiProviderTest = installPlugin("fake-ai-review-agent", FakeAiReviewAgentModule.class);
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
      DynamicItem.bind(binder(), AiReviewProvider.class).toInstance(FakeAiReviewProvider.INSTANCE);
    }
  }

  static class FakeAiReviewProvider implements AiReviewProvider {

    public static final FakeAiReviewProvider INSTANCE = new FakeAiReviewProvider();

    @Override
    public String key() {
      return TEST_PROVIDER_KEY;
    }

    @Override
    public String review(String apiToken, String model, String prompt) {
      return "";
    }
  }
}
