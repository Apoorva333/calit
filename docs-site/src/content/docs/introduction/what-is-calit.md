---
title: What is calit
description: A self-hosted, multi-user scheduling app you run on your own server.
---

calit is a self-hosted, multi-user scheduling application — a Calendly alternative you run on your own server. It is built on Quarkus and Java, uses Postgres for all persistent state, and renders every page server-side with only minimal inline vanilla JavaScript — no client-side framework, no SPA.

## Per-user tenancy

Every user gets a completely isolated scheduling presence. Meeting types, availability rules, bookings, settings, and Google account credentials all belong exclusively to that user. Public booking pages are served at `/<username>/<slug>`. The application enforces strict owner scoping at every layer: one user can never read or write another user's data.

## Booking controls

Each meeting type supports:

- **Approval flows** — bookings can require manual confirmation before they are accepted.
- **Buffer times** — padding before and after each event.
- **Minimum notice** — how far in advance a slot must be booked.
- **Booking horizon** — how many days into the future slots are shown.

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
