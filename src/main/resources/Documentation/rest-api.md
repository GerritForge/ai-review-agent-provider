Rest API
========

## User API Token Endpoints

This plugin exposes REST endpoints to allow users to manage their personal
API Token for connecting with the AI LLMs providers.
The API Token is stored on a per-user basis in the account preferences file
`ai-user-data.config`.

All endpoints require authentication.

---

### Set / Update AI API Token

**PUT** `/a/accounts/self/ai-review-agent-provider~apiToken`

Updates the API Token for the current user.

#### Request Body

```json
{
  "token": "your-ai-api-key"
}
```

#### Response

- `201 CREATED` on success.

If the key is empty or invalid, the request will fail with:

- `400 Bad Request`

---

### Get AI API Token

**GET** `/a/accounts/self/ai-review-agent-provider~apiToken`

Retrieves the currently set API token for the user.

**Request:**
```http
  GET /a/accounts/self/ai-review-agent-provider~apiToken HTTP/1.0
```

**Response:**
```http
  HTTP/1.1 200 OK
  Content-Type: application/json; charset=UTF-8

{
  "token": "your-ai-api-key"
}
```

Enforce token privacy:

**Request:**
```http
  GET /a/accounts/<other-user-accountid>/ai-review-agent-provider~apiToken HTTP/1.0
```

**Response:**
```http
  HTTP/1.1 403 FORBIDDEN
  Content-Type: application/json; charset=UTF-8
```

## Security Considerations

- Only the authenticated user can manage their own key.
- Users with the administrative server capabilities can manage tokens for other accounts.
