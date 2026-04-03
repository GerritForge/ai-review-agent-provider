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
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":secure-config-neverlink",
        "//lib/errorprone:annotations",
        "//plugins/ai-review-agent-provider:ai-review-agent-provider-api",
    ],
)

gerrit_plugin(
    name = "ai-review-agent-provider-api",
    srcs = glob(
        ["src/main/java/com/gerritforge/gerrit/plugins/ai/provider/api/*.java"],
    ),
    dir_name = "ai-review-agent-provider",
    manifest_entries = [
        "Gerrit-PluginName: ai-review-agent-provider",
        "Implementation-Title: AI Review Agent shared provider API",
        "Implementation-URL: https://github.com/GerritForge/ai-review-agent-provider",
        "Gerrit-ApiModule: com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProviderApiModule",
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
