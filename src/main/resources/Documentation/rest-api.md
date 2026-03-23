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

## Security Considerations

- Only the authenticated user can manage their own key.
- Users with the administrative server capabilities can manage tokens for other accounts.
