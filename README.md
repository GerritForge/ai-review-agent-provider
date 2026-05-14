# Gerrit AI Review Agent Provider

**Supercharge your Gerrit Code Reviews with Artificial Intelligence**

*A universal provider seamlessly connecting Gerrit's native AI chat interface with the world's most powerful
Large Language Models.*

---

## ✨ Why Use the AI Review Agent Provider?

Gerrit v3.14 introduces a transformative native AI chat interface directly within the change screen.
This plugin acts as the intelligent backend engine for that interface. Instead of switching contexts to a
separate browser tab, reviewers and authors can query an AI directly about specific lines of code, ask for
optimization strategies, or request comprehensive summaries of complex diffs.

### 🌐 Universal LLM Integration

We designed this provider to be completely agnostic, meaning you are never locked into a single ecosystem.
It serves as a unified adapter for:

* **Google Gemini:** Pro and Ultra models.
* **OpenAI:** GPT-4o, GPT-4 Turbo.
* **Anthropic:** Claude 3.5 Sonnet and Opus.
* **Local / On-Premise Models:** Self-hosted infrastructure via Ollama or private cloud endpoints.

---

## 🚀 Getting Started

### Prerequisites

Before you begin, ensure your build environment meets the following requirements:

* **Gerrit Source:** `v3.14.x` or later.
* **Build Tool:** `Bazel 7.1.1` or later (using Bazelisk is highly recommended).
* **Dependencies:** The `secure-config` plugin is required to safely manage your AI API keys.

### 🛠️ Compilation

To build the plugin, you will need to clone the required repositories and link them into the Gerrit source tree.

```bash
# 1. Clone the Gerrit core and required repositories
git clone --recurse-submodules https://gerrit.googlesource.com/gerrit
git clone https://gerrit.googlesource.com/plugins/secure-config
git clone https://github.com/GerritForge/ai-review-agent-provider

# 2. Link plugins into the Gerrit source tree
cd gerrit/plugins
ln -s ../../secure-config .
ln -s ../../ai-review-agent-provider .

# 3. Execute the build
cd ..
bazelisk build plugins/ai-review-agent-provider

```

> **Build Output:**
> Once complete, your compiled artifact will be ready at:
> `bazel-bin/plugins/ai-review-agent-provider/ai-review-agent-provider.jar`

---

## ⚙️ Installation & Usage

1. **Deploy:** Copy the generated `ai-review-agent-provider.jar` file into your Gerrit server's
   `$GERRIT_SITE/plugins` directory.
2. **Activate:** Restart your Gerrit instance, or reload the plugin dynamically via SSH:
```bash
ssh -p 29418 admin@<your-gerrit-server> gerrit plugin reload ai-review-agent-provider

```
3. **Configure your Intelligence:** This provider manages the complex UI lifecycle, but the specific LLM
   logic is driven by external scripts.

> [!TIP]
> **Looking for ready-to-use AI integrations?**
> Browse our extensive collection of pre-configured AI provider scripts (for OpenAI, Gemini, etc.) on the
> official [Gerrit Scripting Project](https://gerrit.googlesource.com/plugins/scripts/+/refs/heads/master/ai/).

---

## ⚖️ License

This project is distributed under the **Business Source License 1.1** (BSL 1.1).

This "source-available" model balances open-source-style access with temporary commercial constraints to ensure
sustainable development.

* **Read the License:** The full text is available in the [`LICENSE`](https://www.google.com/search?q=LICENSE) file.
* **Commercial Use:** If your usage exceeds the **Additional Use Grant**, please secure a commercial license by
  contacting [GerritForge Sales](https://gerritforge.com/contact).
