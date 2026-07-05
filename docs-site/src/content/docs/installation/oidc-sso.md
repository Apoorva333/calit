---
title: OIDC / SSO setup
description: Add "Sign in with SSO" backed by any OpenID Connect provider (Authelia, Keycloak, Auth0, Zitadel, Authentik, ...).
---

:::note
OIDC / SSO is **optional** and **off by default** (`OIDC_ENABLED=false`). Form login keeps working exactly as before whether or not OIDC is configured.
:::

## How it works

- calit acts as an OIDC **relying party**, running an authorization-code flow scoped to a single endpoint, `/api/oidc/login`. Everything else (session cookie, `/me`, public booking pages) is unaffected.
- The OIDC login is **login-only**: once the identity is verified, calit bridges into its own normal form-auth session. There is no ongoing OIDC session to manage.
- **Account linking** matches the token's `email` claim against an existing calit user's settings email, but only when the provider marked it `email_verified`. If no verified-email match exists, calit provisions a new account — but only when `SIGNUP_ENABLED=true`. With `SIGNUP_ENABLED=false`, an unmatched SSO login is rejected rather than silently creating an account. If the verified email matches more than one calit account, the SSO login is rejected (the user must sign in with a password instead) rather than calit picking one — resolve it by making the accounts' settings emails unique.
- **Admin is grant-only, never demote**: if `OIDC_ADMIN_GROUP` is set and the token's `groups` claim contains it, the user gets calit admin on that login. Losing the group on a later login revokes the OIDC-granted admin — but an admin granted locally (via `/me/users`) is **never** demoted by OIDC group state.

## Steps

### 1. Register a client with your provider

Every OIDC-compliant provider needs the same three facts from calit:

| Setting | Value |
|---|---|
| Redirect URI | `${APP_BASE_URL}/api/oidc/login` (e.g. `https://cal.example.com/api/oidc/login`) |
| Scopes | `openid email profile groups` |
| Claims calit reads | `sub`, `email`, `email_verified`, `groups` |

### 2. Set the environment variables

```dotenv
OIDC_ENABLED=true
OIDC_ISSUER_URL=https://idp.example.com
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=change-me
OIDC_ADMIN_GROUP=calit-admins
```

`OIDC_ISSUER_URL` is the **base** issuer URL — calit discovers the token/authorization/JWKS endpoints from `${OIDC_ISSUER_URL}/.well-known/openid-configuration`. Leave `OIDC_ADMIN_GROUP` blank if no group should map to calit admin.

## Example A — generic OIDC provider (any compliant IdP)

This is the calit side only, and is identical for Keycloak, Auth0, Zitadel, Authentik, or any other compliant provider — only the issuer URL and client credentials change.

```bash
# calit — generic OIDC client config (any compliant provider)
OIDC_ENABLED=true
OIDC_ISSUER_URL=https://idp.example.com          # base issuer; calit discovers {issuer}/.well-known/openid-configuration
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=<plaintext secret from the provider>
OIDC_ADMIN_GROUP=calit-admins                     # group whose members get admin; blank = no OIDC admin
# Register in your provider:
#   redirect URI : https://cal.example.com/api/oidc/login
#   scopes       : openid email profile groups
#   client auth  : client_secret_post (or client_secret_basic)
```

## Example B — Authelia (concrete)

Authelia is configured on the provider side with a client block in `configuration.yml`, plus group membership in `users_database.yml` that drives the calit admin grant. Note the secret split: Authelia stores it **hashed**, calit's `OIDC_CLIENT_SECRET` is the **plaintext** value you hashed.

```yaml
# Authelia configuration.yml — identity_providers.oidc.clients
identity_providers:
  oidc:
    clients:
      - client_id: calit
        client_name: calit
        # Generate: authelia crypto hash generate pbkdf2 --password '<plaintext>'
        # Store the HASH here; put the PLAINTEXT in calit's OIDC_CLIENT_SECRET.
        client_secret: '$pbkdf2-sha512$310000$...'
        public: false
        authorization_policy: two_factor
        redirect_uris:
          - https://cal.example.com/api/oidc/login
        scopes: [openid, email, profile, groups]
        token_endpoint_auth_method: client_secret_post
```

```yaml
# Authelia users_database.yml — group membership drives calit admin
users:
  pavel:
    disabled: false
    groups:
      - calit-admins        # matches OIDC_ADMIN_GROUP -> admin in calit
```

```bash
# calit .env — matching the Authelia client above
OIDC_ENABLED=true
OIDC_ISSUER_URL=https://auth.example.com
OIDC_CLIENT_ID=calit
OIDC_CLIENT_SECRET=<the plaintext you hashed for Authelia>
OIDC_ADMIN_GROUP=calit-admins
```

:::caution[Hash vs. plaintext mismatch]
Authelia only ever stores the **hash** of the client secret; calit's `OIDC_CLIENT_SECRET` must be the **plaintext** value you hashed. If they don't match, the authorization code exchange fails silently — the user is bounced back to `/login` with no explicit error, so double-check the plaintext before reporting a bug.
:::

## Shared behaviour (applies to any provider)

- **Email must match and be verified.** Account linking requires the token's `email` claim to equal an existing user's settings email *and* `email_verified: true` on the token. An unverified email is treated the same as no match.
- **An ambiguous email is rejected.** If the verified email matches more than one calit account, the SSO login is rejected (the user must sign in with a password instead) rather than calit picking one — resolve it by making the accounts' settings emails unique.
- **New-account creation is gated by `SIGNUP_ENABLED`.** With no matching local account, a new user is auto-provisioned only when `SIGNUP_ENABLED=true`; otherwise the login is rejected.
- **Admin is grant-only, never a demotion.** `OIDC_ADMIN_GROUP` membership grants calit admin on each login where present, and is revoked on a subsequent login if the group is removed — but a locally-granted admin (via `/me/users`) is never demoted by OIDC group state.
- **Silent failures look like `/login`.** A wrong issuer URL, client ID/secret, or a provider that isn't reachable typically manifests as the user landing back on `/login` rather than a clear error page — check calit's logs and the provider's client configuration first.
