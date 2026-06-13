---
title: Google OAuth setup
description: Connect Google Calendar so bookings create events and Meet links.
---

:::note
Google Calendar sync is **optional**. Leaving the keys blank runs calit in degraded mode — all booking functionality works fully without a Google account.
:::

## Steps

### 1. Create a Google Cloud project and OAuth client

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create (or select) a project.
2. Navigate to **APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID**.
3. Set the application type to **Web application**.

### 2. Register the redirect URIs

Add **both** of the following as **Authorized redirect URIs** in your OAuth client. Replace `https://book.example.com` with your actual `APP_BASE_URL`:

```
${APP_BASE_URL}/api/google/callback
${APP_BASE_URL}/api/google/login/callback
```

Both URIs must be registered — one is used for the per-user Calendar connection flow, the other for Google sign-in.

:::tip
If you set the optional override vars `GOOGLE_OAUTH_REDIRECT_URI` or `GOOGLE_OAUTH_LOGIN_REDIRECT_URI` (for unusual reverse-proxy paths), register whatever values you set instead.
:::

### 3. Set the environment variables

Copy the **Client ID** and **Client Secret** from the Credentials page, then set:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=your-client-id
GOOGLE_OAUTH_CLIENT_SECRET=your-client-secret
# Strong random string shared by ALL replicas
GOOGLE_OAUTH_STATE_SECRET=<openssl rand -hex 32>
```

`GOOGLE_OAUTH_STATE_SECRET` must be the same value on every replica. Generate it with:

```bash
openssl rand -hex 32
```

### 4. Secure tokens at rest

`TOKEN_ENCRYPTION_KEY` encrypts stored Google OAuth tokens with AES-256-GCM. See the [Configuration reference](/calit/installation/configuration/) for details.

### 5. Connect accounts and use

Each user connects their **own** Google account from the owner console (`/me`). Once connected, every new booking automatically:

- Creates a Google Calendar event on the user's calendar.
- Generates a Google Meet link included in the booking confirmation.
