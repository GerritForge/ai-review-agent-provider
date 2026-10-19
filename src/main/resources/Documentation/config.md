ai-review-agent-provider Configuration
====================================

The encryption/decryption logic is provided by [secure-config](https://gerrit.googlesource.com/plugins/secure-config) plugin.

Refers to its configuration settings for more details about encryption/decryption.

## AI review actions

An AI review action is a named prompt for reviewing a change, such as
"Help me with review". Each action is a file `ai-review/<id>.md` on the
`refs/meta/config` branch of a project. The file name is the action id;
it must match `[a-z][a-z0-9-]{0,63}`. The
[actions REST endpoint](rest-api.html#list-ai-review-actions) returns
the actions of a change's project.

A file holds a JSON object between two `---` lines, followed by the
prompt:

```
---
{"displayText": "Security review", "contextLines": 20}
---
Review this patch for security issues: injection, missing permission
checks, secrets in code. Project: {{project}}, branch: {{branch}}.

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
of the plugin does not know are ignored, with a warning on push.
`displayText` must not contain control or formatting characters such
as line breaks or bidirectional overrides.

HTML comments before the first `---` line are skipped, so a file can
carry a licence header that is never part of the prompt:

```
<!--
Copyright 2026 Example Corp.
SPDX-License-Identifier: Apache-2.0
-->
---
{"displayText": "Security review"}
---
...
```

### In the AI chat

The AI chat of a change offers the enabled actions as buttons labelled
with `displayText`. When a user sends a prompt, the first `{{patch}}` is
replaced by the patch of the revision, `{{project}}` by the project
name and `{{branch}}` by the destination branch. Text inserted for a
placeholder is not expanded again, and other `{{...}}` text, including
any further `{{patch}}`, is left as it is. Without `{{patch}}`, the
patch is appended to the prompt. The patch is cut at 200000 characters,
and a prompt longer than 300000 characters after expansion is not sent.

If the action changed or was removed after the change page loaded, the
chat does not send the prompt and asks the user to reload the page.

If the actions cannot be loaded, the chat shows the error instead of
accepting prompts. If every action is disabled, users can still type
their own prompts.

The patch is written by the change's author and is sent to the model as
it is, so it can steer the model's answer. Treat the answer as a review
aid, not as a verdict.

### Inheritance

For each action id, Gerrit looks in the project, then in its parents,
then in All-Projects and finally in the actions bundled with the
plugin. The first file found is used as it is: a child file replaces
the inherited one entirely, and fields it leaves out take their
defaults, not the parent's values. A disabled file hides the action for
the project and its children; a child can define the action again.
Deleting the child's file brings back the inherited action.

The bundled actions are `review-change` ("Help me with review"),
`review-change-full` ("Review with full files") and `review-commit`
("Improve commit message"). Override or disable them by id like any
other action.

Bundled actions are listed first, followed by the other actions sorted
by id. `review-change` is selected by default; when it is disabled, the
first action is.

### Validation and limits

Updates of `refs/meta/config` are rejected when they add or modify an
invalid action file: changes uploaded for review, direct pushes, pushes
of commits the server already has, and the commits a submit creates.
Deleting a file is always allowed, and files an update does not change
are not checked. Files in `ai-review/` that are not `.md` files, and
files in its subfolders, are ignored.

* A file must not be larger than 64 KiB.
* A project must not have more than 100 action files; an update that
  adds files beyond that is rejected.
* At most 200 actions can be enabled for a project.
* The actions of a project, as returned by the REST API, must not be
  larger than 2 MiB together.

The last two limits depend on the parent projects too, so they are
checked only when the actions are loaded.

If the action file that would be used is invalid nonetheless, for
example because it was written before the plugin was installed, the AI
chat shows an error instead of any actions, naming the file, and its
project if the user can see it. Fix or delete the file to recover.

### Visibility

Anyone who can see a change can read the actions of its project. This
includes the project's own action files and those inherited from
parent projects, although by default only project owners can read
`refs/meta/config`. Do not put secrets in action files. Actions
inherited from a project the user cannot see at all are not returned:
loading the actions fails instead.

Actions are review aids, not policy: they do not restrict which prompts
users can send.

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