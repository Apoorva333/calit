---
title: Bookings & approvals
---

Invitees book meetings through the public booking page at `/<username>/<slug>`. No account is required.

![Custom booking fields](/calit/img/booking-fields.png)

## Booking flow

1. Invitee opens the meeting type's public URL.
2. They pick a date, then a time slot in their local timezone.
3. They fill in the booking form (name, email, and any custom fields defined for that meeting type).
4. On submission, calit validates the slot, checks for conflicts, and either confirms or holds the booking.

## Confirmed vs pending bookings

If the meeting type does **not** require approval, the booking is confirmed immediately. A confirmation email with an `.ics` calendar invite is sent to the invitee. If the owner has connected a Google account, a Google Meet link is generated and included in the invite.

If the meeting type **requires approval**, the booking is created with a **pending** status. The booking is held until you approve or reject it from the owner console at `/me`. Pending bookings that are neither approved nor cancelled expire automatically after the number of hours configured with `APPROVAL_HOLD_HOURS` (default: 24 hours).

## Invitee self-service links

The confirmation email includes a unique link the invitee can use to:

- **View** their booking details.
- **Reschedule** — pick a new available slot; the old slot is released.
- **Cancel** — release the slot and notify both parties.

These actions work via a secure token; no login is required.

## Reminders

calit sends a reminder email to the invitee before each confirmed meeting. The lead time is controlled by `REMINDER_LEAD_MINUTES` (default: `1440`, i.e. 24 hours before the meeting).

## Abuse protection

The public booking form has several layers of protection:

- **Cloudflare Turnstile** — bot-detection challenge on the form (requires setup; see [Turnstile setup](/calit/installation/turnstile/)).
- **Honeypot field** — a hidden field that only bots fill in; submissions that include it are silently rejected.
- **Per-email daily cap** — a single email address cannot make more than `PER_EMAIL_DAILY_CAP` bookings per day (default: 10) across the owner's meeting types.
