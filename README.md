# Gerrit AI Review Agent Provider

Gerrit Code Review plugin for managing AI API tokens across Gerrit's
AI Code Review Agent provider plugins (e.g., ai-review-agent-gemini).

## License

This project is licensed under the **Business Source License 1.1** (BSL 1.1).
This is a "source-available" license that balances free, open-source-style access to the code
with temporary commercial restrictions.

* The full text of the BSL 1.1 is available in the [LICENSE](LICENSE) file in this
  repository.
* If your intended use case falls outside the **Additional Use Grant** and you require a
  commercial license, please contact [GerritForge Sales](https://gerritforge.com/contact).

## How to build

### Prerequisites

Gerrit v3.14 source code and Bazel 7.6.1 or later.

### Compile

```bash
git clone --recurse-submodules https://gerrit.googlesource.com/gerrit
git clone https://gerrit.googlesource.com/plugins/secure-config
git clone https://github.com/GerritForge/ai-review-agent-provider

cd gerrit/plugins
ln -s ../../secure-config .
ln -s ../../ai-review-agent-provider .

cd ..
bazelisk build plugins/ai-review-agent-provider
```

The build output is:

- `bazel-bin/plugins/ai-review-agent-provider/ai-review-agent-provider.jar`

## Usage

This plugin can be installed directly into Gerrit into the `$GERRIT_SITE/plugins` and can
be used with any other implementation of the AI agent provider LLMs (e.g., `ai-review-agent-gemini`)
that need a shared token management.
