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
of the plugin does not know are ignored, except `paths`: it is reserved
for choosing actions by the files a change touches and is rejected until
that is supported. `displayText` must not contain
control or formatting characters such as line breaks or bidirectional
overrides.

### In the AI chat

The AI chat of a change offers its built-in actions first:

| Action               | Label                  | Context lines |
| -------------------- | ---------------------- | ------------- |
| `review-change`      | Help me with review    | 10            |
| `review-change-full` | Review with full files | 1000000       |
| `review-commit`      | Improve commit message | 3             |

A project file with the id of a built-in action replaces it in place,
and a disabled one hides it. The project's other enabled actions follow,
sorted by id. Each action is a button labelled with `displayText`. A
prompt the user types instead is sent with 10 context lines.

Actions are review aids, not policy: users can edit an action's prompt
before sending it, or type their own.

When a user sends a prompt, the first `{{patch}}` is replaced by the
patch of the revision, `{{project}}` by the project name and
`{{branch}}` by the destination branch. Text inserted for a placeholder
is not expanded again, and other `{{...}}` text, including any further
`{{patch}}`, is left as it is. Without `{{patch}}`, the patch is
appended to the prompt. The patch is cut at 200000 characters, and a
prompt longer than 300000 characters after expansion is not sent.

The chat keeps the actions of the last 20 projects it showed. Opening a
change of such a project again only checks that they are still current.

If the action's label or prompt changed, or the action was removed,
after the change page loaded, the chat does not send the prompt and asks
the user to reload the page. A change of only its context lines is
applied without a reload, as users neither see nor edit them.

If the project's actions cannot be loaded, for example because the file
that would be used is invalid, the chat offers no action, not even a
built-in one, as the project may have disabled or changed it. Typed
prompts still work, and their answers start with a notice naming the
error. An action offered before the error is not sent; the chat says
why.

The patch is written by the change's author and is sent to the model as
it is, so it can steer the model's answer. Treat the answer as a review
aid, not as a verdict.

### Inheritance

For each action id, Gerrit looks in the project, then in its parents
and finally in All-Projects. The first file found is used as it is: a
child file replaces the inherited one entirely, and fields it leaves
out take their defaults, not the parent's values. A disabled file hides
the action for the project and its children; a child can define the
action again. Deleting the child's file brings back the inherited
action.

### Limits

Files in `ai-review/` that are not `.md` files, and files in its
subfolders, are ignored.

* A file must not be larger than 64 KiB.
* A project must not have more than 100 action files.
* At most 200 actions can be enabled for a project.
* The labels and prompts of the enabled actions of a project must not
  be longer than 2097152 characters together, counted in UTF-16 code
  units: an emoji counts as two.

If the action file that would be used is invalid, or a limit is
exceeded, loading the actions fails with an error that gives no reason,
because the reason can name a parent project the user cannot see. The
reason, naming the file and its project, is written to the server log,
at most once a minute for each project. Fix or delete the file to
recover. Invalid files are also logged once for each new revision of
`refs/meta/config`.

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