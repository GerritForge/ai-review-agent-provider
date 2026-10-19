Rest API
========

## User API Token Endpoints

This plugin exposes REST endpoints to allow users to manage their personal
API Token for connecting with the AI LLMs providers.
The API Token is stored on a per-user basis in the account preferences file
`ai-user-data.config`.

All endpoints require authentication.

---

### Set / Update API Token

**PUT** `/a/accounts/self/ai-review-agent-provider~apiToken`

Creates/Updates the API Token for the current user to use the AI code-review in Gerrit AI chat
with an LLM backend provided by a plugin.

#### Request Body

```json
{
  "plugin": "ai-backend-plugin",
  "token": "your-api-token"
}
```

#### Response

- `201 CREATED` on success.

If the API Token is empty or invalid, the request will fail with:

- `400 Bad Request`

### Retrieve the AI providers with their availability to be used

**GET** `/a/accounts/self/ai-review-agent-provider~apiProviders`

Retrieves the list of AI providers installed, keyed on the plugin name.
Each provider includes an `enabled` field indicating whether the current
user has configured an API token for it.

#### Response

```
200 OK
Content-Type: application/json; charset=UTF-8

{ 
  "providers" : [
    {
      "plugin" : ai-review-provider-gemini",
      "displayName": "Gemini",
      "models": ["gemini-2.5-pro", "gemini-2.5-flash"],
      "enabled": true
    },
    {
      "plugin" : "another-ai-plugin",
      "displayName": "Another AI Provider",
      "models": ["model-a", "model-b"],
      "enabled": false
    }
  ]
}
```
on success.

---

### Request AI Code Review

**POST** `/a/changes/<change-id>/ai-review-agent-provider~aiReview`

Request an AI-generated code review for the current change with the associated
prompt.

#### Request Body

```json
{
  "plugin": "ai-backend-plugin",
  "model": "ai-review-model/1.0",
  "prompt": "Review the current change"
}
```

#### Response

```
200 OK
Content-Type: application/json; charset=UTF-8
{
  "text": "AI-generated review for the change"
}
```
on success.

If this AI review agent does not exist, or the model is invalid:

```
400 Bad Request
```

If there is no AI api key present in the user's profile:

```
404 NOT FOUND
```

---

### List AI Review Actions

**GET** `/a/projects/<project-name>/ai-review-agent-provider~actions`

Lists the AI review actions the files of the project and its parents
define, as described in [AI review actions](config.html#ai-review-actions):
the enabled actions in `actions` and the ids of the disabled ones in
`disabled_action_ids`, each sorted by id. An action whose file is
invalid is listed as disabled. Prompts are returned as
written: placeholders such as `{{patch}}` are not expanded.

`version` changes whenever the actions may have changed: a new
`refs/meta/config` revision of the project or of any parent, a different
parent, or a plugin upgrade. With `?version=<version>` naming the
current version, the response holds only the version, without `actions`
and `disabled_action_ids`, and the server reads no action file.

#### Response

```
200 OK
Content-Type: application/json; charset=UTF-8

{
  "version": "5f0c2d...",
  "actions": [
    {
      "id": "performance",
      "display_text": "Performance review",
      "context_lines": 10,
      "prompt": "Look for slow loops, extra queries and allocations ... {{patch}}"
    },
    {
      "id": "security",
      "display_text": "Security review",
      "context_lines": 20,
      "prompt": "Review this patch for security issues ... {{patch}}"
    }
  ],
  "disabled_action_ids": [
    "style-review"
  ]
}
```

With `?version=5f0c2d...` while nothing changed:

```
200 OK
Content-Type: application/json; charset=UTF-8

{
  "version": "5f0c2d..."
}
```

Anonymous users get `403 Forbidden`. Users who cannot see the project
get `404 Not Found`.

If a project in the hierarchy has more than 100 action files, or the
labels and prompts of its enabled actions are longer than 2097152 UTF-16
code units together:

```
409 Conflict

The AI review action configuration is invalid; the reason is in the server log
```

The reason can name a parent project the user cannot see, so it is only
written to the server log.

---

## Security Considerations

- Only the authenticated user can manage their own API Tokens.
- Users with the administrative server capabilities can manage tokens for other accounts.
- Any signed-in user who can see a project can read its AI review
  actions, including those inherited from parents they cannot read; see
  [Visibility](config.html#visibility). Action files must not contain
  secrets.
