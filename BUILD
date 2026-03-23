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
        "//lib/errorprone:annotations",
    ],
)

junit_tests(
    name = "ai-review-agent-provider_tests",
    srcs = glob(["src/test/java/**/*IT.java"]),
    tags = ["ai-review-agent-provider"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":ai-review-agent-provider__plugin",
    ],
)
