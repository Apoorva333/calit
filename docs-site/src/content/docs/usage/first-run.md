---
title: First run & admin user
---

When calit starts against a fresh database it has no users. Every incoming request — regardless of path — redirects to `/setup` until the first user is created.

## Creating the first user

Open `http://<your-host>/setup` in a browser. Fill in a username and password. There is **no default password**; you choose one here.

![calit first-run setup screen](/calit/img/setup-wizard.png)

The account created at `/setup` is automatically granted the site-admin role (`is_admin = true`). Once you submit the form, `/setup` permanently returns **404** — it is unavailable from that point on and cannot be used to create additional users.

## First-login wizard

After signing in for the first time you are redirected to `/me/setup`, a short wizard that lets you configure your timezone, display name, and other personal settings before you start using the app.

## Next steps

- [Configure meeting types](/calit/usage/meeting-types/) to define what your invitees can book.
- Review [environment variables](/calit/installation/configuration/) for production settings such as `SIGNUP_ENABLED`, mailer config, and Google OAuth.
