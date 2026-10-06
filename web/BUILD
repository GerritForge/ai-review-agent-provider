load("@npm//@bazel/typescript:index.bzl", "ts_config", "ts_project")
load("//tools/bzl:js.bzl", "gerrit_js_bundle", "web_test_runner")
load("//tools/js:eslint.bzl", "plugin_eslint")

package_group(
    name = "visibility",
    packages = ["//plugins/ai-review-agent-provider/..."],
)

package(default_visibility = [":visibility"])

ts_config(
    name = "tsconfig",
    src = "tsconfig.json",
    deps = [
        "//plugins:tsconfig-plugins-base.json",
    ],
)

ts_project(
    name = "ai-review-agent-provider-ts",
    srcs = glob(
        ["**/*.ts"],
        exclude = ["**/*test*"],
    ),
    incremental = True,
    out_dir = "_bazel_ts_out",
    tsc = "//tools/node_tools:tsc-bin",
    tsconfig = ":tsconfig",
    deps = [
        "@plugins_npm//@gerritcodereview/typescript-api",
        "@plugins_npm//lit",
        "@plugins_npm//rxjs",
    ],
)

ts_project(
    name = "ai-review-agent-provider-ts-tests",
    srcs = glob(["**/*.ts"]),
    incremental = True,
    out_dir = "_bazel_ts_out_tests",
    tags = ["manual"],
    tsc = "//tools/node_tools:tsc-bin",
    tsconfig = ":tsconfig",
    deps = [
        "@plugins_npm//:node_modules",
        "@ui_dev_npm//:node_modules",
    ],
)

gerrit_js_bundle(
    name = "ai-review-agent-provider",
    srcs = [":ai-review-agent-provider-ts"],
    entry_point = "_bazel_ts_out/index.js",
)

web_test_runner(
    name = "web_test_runner",
    srcs = ["web_test_runner.sh"],
    data = [
        ":ai-review-agent-provider-ts-tests",
        ":tsconfig",
        "@plugins_npm//:node_modules",
        "@ui_dev_npm//:node_modules",
    ],
)

filegroup(
    name = "tsconfig-sources",
    srcs = glob(["**/tsconfig.json"]),
)

plugin_eslint()
