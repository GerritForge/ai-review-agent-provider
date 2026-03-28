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

<<<<<<< PATCH SET (1e70b63e7c04991f67e14a1700f2032afe5a4e6c Introduce a backend API the call to LLMs)
Creates/Updates the API key for the current user to use the AI code-review in Gerrit AI chat
with an LLM backend provided by a plugin.
=======
Updates the API Token for the current user.
>>>>>>> BASE      (719c2d0120148f55efac38ff30841595d035151e Remove the Gemini word for better clarity)

#### Request Body

```json
{
<<<<<<< PATCH SET (1e70b63e7c04991f67e14a1700f2032afe5a4e6c Introduce a backend API the call to LLMs)
  "plugin": "ai-backend-plugin",
  "token": "your-ai-api-key"
=======
  "token": "your-api-token"
>>>>>>> BASE      (719c2d0120148f55efac38ff30841595d035151e Remove the Gemini word for better clarity)
}
```

#### Response

- `201 CREATED` on success.

If the key is empty or invalid, the request will fail with:

- `400 Bad Request`

---

<<<<<<< PATCH SET (1e70b63e7c04991f67e14a1700f2032afe5a4e6c Introduce a backend API the call to LLMs)
### Request AI Code Review
=======
### Get API Token
>>>>>>> BASE      (719c2d0120148f55efac38ff30841595d035151e Remove the Gemini word for better clarity)

**POST** `/a/changes/<change-id>/ai-review-agent-provider~aiReview`

Request an AI-generated code review for the current change with the associated
prompt.

#### Request Body

```json
{
<<<<<<< PATCH SET (1e70b63e7c04991f67e14a1700f2032afe5a4e6c Introduce a backend API the call to LLMs)
  "plugin": "ai-backend-plugin",
  "model": "ai-review-model/1.0",
  "prompt": "Review the current change"
=======
  "token": "your-api-token"
>>>>>>> BASE      (719c2d0120148f55efac38ff30841595d035151e Remove the Gemini word for better clarity)
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

If there is AI review agent available:

```
400 Bad Request
```

If there is no AI api key present in the user's profile:

```
404 NOT FOUND
```

---

## Security Considerations

- Only the authenticated user can manage their own key.
- Users with the administrative server capabilities can manage tokens for other accounts.
