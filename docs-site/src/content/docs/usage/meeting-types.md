---
title: Meeting types
---

Meeting types define what your invitees can schedule. Each type gets its own public URL and can be configured independently.

![Meeting types list](/calit/img/meeting-types.png)

## Owner console

All meeting-type management lives in your owner console at `/me`. From there you can create, edit, activate, deactivate, or delete meeting types.

![Meeting type editor](/calit/img/meeting-type-detail.png)

## Public URLs

Each meeting type has a **slug** — a short identifier that forms the public booking URL:

```
/<username>/<slug>
```

Your landing page at `/<username>` lists all of your **active, non-secret** meeting types. Secret meeting types are still bookable via their direct URL but do not appear on the listing.

## Per-type settings

| Setting | Description |
|---|---|
| **Slug** | URL-safe identifier; must be unique within your account. |
| **Duration** | Length of the meeting in minutes. |
| **Buffer before / after** | Padding added before and after each booking so it does not count as free time. |
| **Minimum notice** | How far in advance a booking must be made (e.g. 60 minutes means no same-hour bookings). |
| **Booking horizon** | How many days into the future invitees can book (default 60 days). |
| **Requires approval** | When enabled, new bookings are held in a pending state until you approve them. See [Bookings & approvals](/calit/usage/bookings/). |
| **Custom booking fields** | Extra questions shown to the invitee on the booking form (name, company, notes, etc.). |
| **Secret** | Hides the type from `/<username>` while keeping the direct link active. |

:::tip[Minimum notice smart default]
When creating a new meeting type, **Min scheduling notice** defaults to 4× the duration and updates automatically as you adjust the duration (e.g. a 45-minute meeting suggests 180 minutes' notice). Once you edit the notice field yourself it stops updating. You can set it to any value — including 0 for instant bookings — before saving.
:::

## Google account

Connect a Google account in your account settings to enable Google Meet links and Google Calendar sync for your bookings.

![Connect a Google account](/calit/img/google-connect.png)

## Availability

Bookable slots for a meeting type come from your availability rules and any date overrides. See [Availability & overrides](/calit/usage/availability/).
