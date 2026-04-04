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

Retrieves the map of AI providers installed, keyed on the plugin name, and with an
API key inserted and available to use.

> **NOTE**: AI providers that are installed but not having an API key for the current user
> will not be returned.

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

## Security Considerations

- Only the authenticated user can manage their own API Tokens.
- Users with the administrative server capabilities can manage tokens for other accounts.
