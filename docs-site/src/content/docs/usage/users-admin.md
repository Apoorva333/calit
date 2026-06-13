---
title: Users & admin
---

calit is multi-tenant: each user has a fully isolated scheduling setup. Site admins have an additional management interface for all user accounts.

![Users & admin console](/calit/img/users-admin.png)

## Site admins

Users with the `is_admin` flag are site administrators. The first user created via `/setup` receives this flag automatically. Admins access the user management interface at `/me/users`.

From there, admins can:

- View all registered user accounts.
- Lock or unlock an account. A locked user cannot log in.
- Promote or demote the admin role on existing accounts.

## Data isolation (owner scoping)

Every piece of data — meeting types, availability rules, bookings, settings — is tied to the user who owns it. One user can never read or modify another user's data. This applies at the database query level; there is no admin override that exposes another user's private data.

## Public signup

By default, `/signup` returns **404** and no new users can self-register. To allow public registration, set the environment variable:

```
SIGNUP_ENABLED=true
```

This requires a **server restart** to take effect (the setting is read at startup). Set it back to `false` and restart to close registration again.

When signup is enabled, new accounts are created as regular users (not admins). Admins can later grant the admin role through `/me/users`.

## Related configuration

See [Configuration](/calit/installation/configuration/) for the full list of environment variables including `SIGNUP_ENABLED` and user-related settings.
