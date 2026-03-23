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

<<<<<<< PATCH SET (9adce88a4fbf676ae9eb8c060372f4cff40df1da Add PUT endpoint for storing encrypted API tokens)
java_library(
    name = "test-utils",
    testonly = True,
    srcs = ["src/test/java/com/gerritforge/gerrit/plugins/ai/provider/TestAiReviewProviderModule.java"],
    deps = [
        ":ai-review-agent-provider__plugin",
        "//lib/guice",
    ],
)

junit_tests(
=======
gerrit_plugin_tests(
>>>>>>> BASE      (a6d9a74e8e11f44d57acdf60dfe2bd42272da52a Add Jenkinsfile for changes verification)
    name = "ai-review-agent-provider_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    tags = ["ai-review-agent-provider"],
    deps = [
        ":ai-review-agent-provider__plugin",
        ":secure-config-neverlink",
        ":test-utils",
        "//plugins/secure-config",
    ],
)

java_library(
    name = "secure-config-neverlink",
    neverlink = True,
    exports = ["//plugins/secure-config"],
)
