load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

package_group(
    name = "visibility",
    packages = ["//plugins/ai-review-agent-provider/..."],
)

package(default_visibility = [":visibility"])

gerrit_plugin(
    name = "ai-review-agent-provider",
    srcs = glob(["src/main/java/com/gerritforge/gerrit/plugins/ai/provider/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: ai-review-agent-provider",
        "Implementation-Title: AI Review Agent shared provider library",
        "Implementation-URL: https://github.com/GerritForge/ai-review-agent-provider",
    ],
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

[junit_tests(
    name = src.split("/")[-1].replace(".java", ""),
    srcs = [src],
    tags = ["ai-review-agent-provider"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
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
