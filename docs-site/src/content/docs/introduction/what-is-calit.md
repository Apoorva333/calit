---
title: What is calit
description: A self-hosted, multi-user scheduling app you run on your own server.
---

calit is a self-hosted, multi-user scheduling application — a Calendly alternative you run on your own server. It is built on Quarkus and Java, uses Postgres for all persistent state, and renders every page server-side — no client-side framework, no SPA. calit follows a **progressive-enhancement** rule: every feature works fully without JavaScript, and small inline vanilla scripts only ever enhance the experience (for example, suggesting matching usernames as you type when adding co-hosts to a meeting type) rather than being required for it to work.

## Per-user tenancy

Every user gets a completely isolated scheduling presence. Meeting types, availability rules, bookings, settings, and Google account credentials all belong exclusively to that user. Public booking pages are served at `/<username>/<slug>`. The application enforces strict owner scoping at every layer: one user can never read or write another user's data.

## Booking controls

Each meeting type supports:

- **Approval flows** — bookings can require manual confirmation before they are accepted.
- **Buffer times** — padding before and after each event.
- **Minimum notice** — how far in advance a slot must be booked.
- **Booking horizon** — how many days into the future slots are shown.
- **Multi-host meetings** — a meeting type can require up to 10 hosts; it only becomes bookable once every co-host accepts, and slots are the intersection of all their calendars. See [Multi-host meeting types](/calit/usage/multi-host-meetings/).

## Optional Google Calendar and Meet integration

calit integrates with Google Calendar to check availability against existing events and to create calendar entries with Google Meet links. This integration is entirely optional. If no Google credentials are configured, calit runs in a no-Google mode with no loss of core functionality.

## Authentication and security

- Passwords are hashed with **argon2id** (via BouncyCastle).
- There is no embedded or default admin password. The first admin account is created interactively at `/setup` on first run.
- Sessions use stateless encrypted cookies — no server-side session store.
- Booking forms are protected by **Cloudflare Turnstile**, a honeypot field, and a per-email daily booking cap.

## Infrastructure

calit is a single Quarkus application backed by Postgres. It is stateless and horizontally scalable: run any number of identical replicas pointing at the same database. Background jobs (reminders, expiry sweeps) use `SELECT … FOR UPDATE SKIP LOCKED` for multi-node safety with no leader election.

---

Ready to get started? See the [Quick start](/calit/introduction/quick-start/).
