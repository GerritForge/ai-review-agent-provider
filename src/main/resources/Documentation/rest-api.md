Rest API
========

## User Gemini API Key Endpoints

This plugin exposes REST endpoints to allow users to manage their personal
Gemini API key. The API key is stored on a per-user basis in the account
preferences file `ai-user-data.config`.

All endpoints require authentication.

---

### Set / Update Gemini API Key

**PUT** `/a/accounts/self/ai-review-agent-gemini~apiToken`

Updates the Gemini API key for the current user.

#### Request Body

```json
{
  "token": "your-gemini-api-key"
}
```

#### Response

- `201 CREATED` on success.

If the key is empty or invalid, the request will fail with:

- `400 Bad Request`

---

### Get Gemini API Key

**GET** `/a/accounts/self/ai-review-agent-gemini~apiToken`

Retrieves the currently set Gemini API token for the user.

**Request:**
```http
  GET /a/accounts/self/ai-review-agent-gemini~apiToken HTTP/1.0
```

**Response:**
```http
  HTTP/1.1 200 OK
  Content-Type: application/json; charset=UTF-8

{
  "token": "your-gemini-api-key"
}
```

Enforce token privacy:

**Request:**
```http
  GET /a/accounts/<other-user-accountid>/ai-review-agent-gemini~apiToken HTTP/1.0
```

**Response:**
```http
  HTTP/1.1 403 FORBIDDEN
  Content-Type: application/json; charset=UTF-8
```

## Security Considerations

- Only the authenticated user can manage their own key.
- Users with the administrative server capabilities can manage tokens for other accounts.
