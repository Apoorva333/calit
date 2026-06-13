---
title: Changelog
description: Notable changes per release.
---

This changelog is maintained manually. The canonical release notes, including
asset downloads, are on
[GitHub Releases](https://github.com/asm0dey/calit/releases).

## 1.4.0

Token-at-rest encryption and security audit remediation.

- **Google OAuth tokens are now encrypted at rest** using AES-256-GCM
  (`TOKEN_ENCRYPTION_KEY`). Existing plaintext tokens are back-filled
  automatically on first boot — no reconnection required.
- Added `TOKEN_ENCRYPTION_KEY` config; production startup fails closed if the
  key is absent or too weak (mirrors the existing `SESSION_ENCRYPTION_KEY`
  guard from 1.3.1).
- Security audit remediation: CSRF tokens on all state-changing form POSTs,
  structured audit log for admin actions and failed logins, ReDoS-safe email
  regex, outbound HTTP timeouts and redirect policy, self-lockout and
  last-admin removal blocked, owner-scope invariant asserted at the JSON API
  layer, SQL logging restricted to `%dev`.
- Container hardened: non-root runtime user, base-image digest pinning,
  Trivy image-scan gate in CI, CodeQL analysis added.
- Google OAuth redirect URIs now derived from `APP_BASE_URL` (no localhost
  leak in production).
- `TOKEN_ENCRYPTION_KEY` **must not be rotated** after first boot without
  re-linking all Google accounts (see [Upgrading](/calit/releases/upgrading/)).

## 1.3.1

Production startup secret guard.

- App now fails fast at startup in `%prod` if required secrets
  (`SESSION_ENCRYPTION_KEY`, etc.) are missing or set to weak/dev defaults.

## 1.3.0

Sign in with Google.

- Users can authenticate via "Sign in with Google" in addition to
  username/password.
- Existing accounts are auto-linked by verified email; unknown Google
  identities can be provisioned as new passwordless users.
- Single-use login tickets bridge the Google OAuth callback to the existing
  form-auth session.
- New V11 migration: nullable `password`, `google_sub`, and `login_ticket`
  columns on `app_user`.
- Copy-meeting-type-link button added to meeting-type cards.

## 1.2.0

Seven-day schedule grid and brand favicon.

- Weekly availability is now displayed and edited as a seven-day grid (global
  schedule and per-meeting-type overrides).
- Bulk replace-all endpoints for weekly schedule slots.
- Brand favicon added matching the landing-page chip.
- Google Meet hint hidden on booking pages when the host has no connected
  Google account.

## 1.1.0

Multi-account Google Calendar.

- Users can connect more than one Google account; each is tracked with its own
  credentials.
- New `/me/google` UI for selecting which calendars to read for free/busy and
  which account to write new events to.
- FreeBusy checks fan out across all connected accounts; write-target routes to
  the selected account.
- New V4-extension migration for multi-account schema fields.

## 1.0.1

Postgres 18 volume fix, trademark disclaimer, version bump.

- Fixed Docker Compose volume configuration incompatible with Postgres 18.
- Added trademark disclaimer to README.
- Dependency and version bumps.

## 1.0.0

Initial release.

- Self-hosted, multi-user scheduling application on Quarkus / Java.
- Per-user booking pages at `/<username>/<slug>`.
- Google Calendar integration (read free/busy, write events).
- Email confirmations with `.ics` invites.
- Admin UI at `/me` for managing meeting types, availability, and settings.
- Site-admin user management at `/me/users`.
- Docker Compose deployment; native multi-arch images published to
  `ghcr.io/asm0dey/calit`.
- CI pipeline (GitHub Actions) with build, test, and release stages.
