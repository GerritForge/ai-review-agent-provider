load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_tests",
)

gerrit_plugin(
    name = "ai-review-agent-provider",
    srcs = glob(["src/main/java/com/gerritforge/gerrit/plugins/ai/provider/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: ai-review-agent-provider",
        "Implementation-Title: AI Review Agent shared provider library",
        "Implementation-URL: https://github.com/GerritForge/ai-review-agent-provider",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":secure-config-neverlink",
        "//lib/errorprone:annotations",
    ],
)

java_library(
    name = "test-utils",
    testonly = True,
    srcs = ["src/test/java/com/gerritforge/gerrit/plugins/ai/provider/TestAiReviewProviderModule.java"],
    deps = [
        ":ai-review-agent-provider__plugin",
        "//lib/guice",
    ],
)

<<<<<<< PATCH SET (9d4530fc8f950d6bb387e849fd6790d8140f24b0 Add GET endpoint for reading decrypted API tokens)
[junit_tests(
    name = src.split("/")[-1].replace(".java", ""),
    srcs = [src],
=======
gerrit_plugin_tests(
    name = "ai-review-agent-provider_tests",
    srcs = glob(["src/test/java/**/*.java"]),
>>>>>>> BASE      (52c9dada77b170a1f846e0fb29d51370347836d6 Add PUT endpoint for storing encrypted API tokens)
    tags = ["ai-review-agent-provider"],
    deps = [
        ":ai-review-agent-provider__plugin",
        ":secure-config-neverlink",
        ":test-utils",
        "//plugins/secure-config",
    ],
) for src in glob(["src/test/java/**/*IT.java"])]

java_library(
    name = "secure-config-neverlink",
    neverlink = True,
    exports = ["//plugins/secure-config"],
)
