ai-review-agent-provider Configuration
====================================

The encryption/decryption logic is provided by [secure-config](https://gerrit.googlesource.com/plugins/secure-config) plugin.

Refers to its configuration settings for more details about encryption/decryption.

## Caches

The list of AI providers modules by each provider is kept in a Gerrit persistent cache, which is by default expires
after 1 day.

For customizing the cache policies, amend the `$GERRIT_SITE/etc/gerrit.config` adding a specific
configuration entry for the `ai-review-agent-provider-models` cache.

Example:

```
[cache "ai-review-agent-provider-models"]
  maxAge = 5 days
  memoryLimit = 4096
  diskLimit = 100m
```

For more details on all the cache settings, please refer to the official
[Gerrit documentation](https://gerrit-documentation.storage.googleapis.com/Documentation/3.14.0/config-gerrit.html#cache.name.maxAge).