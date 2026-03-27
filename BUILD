load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_tests",
)

gerrit_plugin(
    name = "ai-review-agent-provider",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: ai-review-agent-provider",
        "Implementation-Title: AI Review Agent shared provider library",
        "Implementation-URL: https://github.com/GerritForge/ai-review-agent-provider",
        "Gerrit-Module: com.gerritforge.gerrit.plugins.ai.provider.AiReviewProviderModule",
        "Gerrit-ApiModule: com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule",
        "Gerrit-HttpModule: com.gerritforge.gerrit.plugins.ai.provider.HttpModule",
    ],
    resource_jars = ["//plugins/ai-review-agent-provider/web:ai-review-agent-provider"],
    resource_strip_prefix = "plugins/ai-review-agent-provider/resources",
    resources = glob(["resources/**/*"]),
    deps = [
        ":secure-config-neverlink",
        "//lib/errorprone:annotations",
    ],
)

gerrit_plugin_tests(
    name = "ai-review-agent-provider_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    tags = ["ai-review-agent-provider"],
    deps = [
        ":ai-review-agent-provider__plugin",
        ":secure-config-neverlink",
        "//plugins/secure-config",
    ],
)

java_library(
    name = "secure-config-neverlink",
    neverlink = True,
    exports = ["//plugins/secure-config"],
)
