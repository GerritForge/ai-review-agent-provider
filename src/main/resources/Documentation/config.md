ai-review-agent-provider Configuration
====================================

The encryption/decryption logic is provided by [secure-config](https://gerrit.googlesource.com/plugins/secure-config) plugin.

Refers to its configuration settings for more details about encryption/decryption.

## AI review actions

An AI review action is a named prompt for reviewing a change, such as
a security review. Each action is a file `ai-review/<id>.md` on the
`refs/meta/config` branch of a project. The file name is the action id;
it must match `[a-z][a-z0-9-]{0,63}`. Other `.md` files there define no
action, but count towards the limit on files. The
[actions REST endpoint](rest-api.html#list-ai-review-actions) returns
the actions of a project.

A file starts with a `---` line, followed by a JSON object, a second
`---` line and the prompt:

```
---
{"displayText": "Security review", "contextLines": 20}
---
Review this patch for security issues: injection, missing permission
checks, secrets in code.

{{patch}}
```

| Field          | Meaning                                          | Default |
| -------------- | ------------------------------------------------ | ------- |
| `enabled`      | `false` hides the action                         | `true`  |
| `displayText`  | Label; required, at most 200 characters          |         |
| `contextLines` | Unchanged lines around each hunk of the patch    | `10`    |
|                | reviewed with the prompt, 0 to 1000000           |         |

The prompt after the header is required. A disabled action needs
neither `displayText` nor a prompt:

```
---
{"enabled": false}
---
```

The header must be strict JSON: double-quoted names and strings, no
comments, no trailing commas, no duplicate fields. Fields this version
of the plugin does not know are ignored, except `paths`: it is reserved
for choosing actions by the files a change touches and is rejected until
that is supported. `displayText` must not contain
control or formatting characters such as line breaks or bidirectional
overrides.

### Inheritance

For each action id, Gerrit looks in the project, then in its parents
and finally in All-Projects. The first file found is used as it is: a
child file replaces the inherited one entirely, and fields it leaves
out take their defaults, not the parent's values. A disabled file hides
the action for the project and its children; a child can define the
action again. Deleting the child's file brings back the inherited
action.

An invalid file hides its action in the same way: neither an inherited
action nor a built-in one with the same id is offered instead, and the
project's other actions stay available. Invalid files are logged once
for each new revision of `refs/meta/config`; fix or delete the file to
bring the action back.

### Limits

Files in `ai-review/` that are not `.md` files, and files in its
subfolders, are ignored. The limits apply to the files of each project;
inherited actions do not count.

* A file must not be larger than 64 KiB; a larger file is invalid.
* A project must not have more than 100 action files.
* The labels and prompts of the enabled actions of a project must not
  be longer than 2097152 characters together, counted in UTF-16 code
  units: an emoji counts as two.

If a project in the hierarchy has more files, or longer labels and
prompts, loading the actions fails with an error that gives no reason,
because the reason can name a parent project the user cannot see. The
reason, naming the project, is written to the server log, at most once a
minute for each project.

### Visibility

Any signed-in user who can see a project can read its actions. This
includes the project's own action files and those inherited from parent
projects, even when the user cannot read their `refs/meta/config` (by
default only administrators and project owners can) or cannot see the
parent project at all. Do not put secrets in action files.

## Caches

The list of AI providers' modules by each provider is kept in a Gerrit persistent cache, which by default expires
after 1 day.

For customizing the cache policies, amend the `$GERRIT_SITE/etc/gerrit.config` adding a specific
configuration entry for the `ai-review-agent-provider.models` cache.

Example:

```
[cache "ai-review-agent-provider.models"]
  maxAge = 5 days
  memoryLimit = 4096
  diskLimit = 100m
```

Parsed action files are kept in the in-memory cache
`ai-review-agent-provider.action_files`, keyed by project and
`refs/meta/config` revision, so a new revision applies without restart.

For more details on all the cache settings, please refer to the official documentation
[Gerrit documentation](https://gerrit-documentation.storage.googleapis.com/Documentation/3.14.0/config-gerrit.html#cache.name.maxAge).