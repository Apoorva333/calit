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

If the meeting type **requires approval**, the booking is created with a **pending** status. The booking is held until you approve or reject it. Pending bookings that are neither approved nor cancelled expire automatically after the number of hours configured with `APPROVAL_HOLD_HOURS` (default: 24 hours).

You can act on a pending request two ways:

- **From the owner console** — the **Pending** page at `/me/pending` lists every request with Approve / Decline buttons.
- **Straight from the request email** — the notification you receive carries one-click **Approve** and **Decline** links. Clicking one opens the owner console; if you are not signed in, you log in first and are returned to the action automatically. These links only work for you, the signed-in owner of the booking — they are not usable by anyone the email is forwarded to.

Owner and invitee receive different copies of every booking email: yours is addressed to you and names the invitee, theirs is addressed to them. Each side only sees the links relevant to it. Booking emails are sent with a friendly sender name — **`<Your name> via calit`** — so recipients recognise who the meeting is with, while the underlying address stays your configured `MAIL_FROM`.

## Managing a confirmed booking as the owner

You can reschedule or cancel any confirmed booking — not just approve or decline pending ones.

- **From the owner console** — each upcoming booking on your dashboard (`/me`) has a **Manage** link. It opens a page where you can **reschedule** (pick a new slot from your own availability — the same picker invitees use) or **cancel** the booking. Both notify the invitee (and any guests) automatically.
- **Straight from the email** — your copy of the confirmation, reschedule, and reminder emails carries a **Reschedule or cancel** link to that same page. Opening it signs you in first if needed and returns you to the booking. The link only works for you, the signed-in owner — it is not usable by anyone the email is forwarded to.

Rescheduling keeps the booking's guests; the manage page lists them read-only (guest membership is managed by the invitee, not the owner).

## Invitee self-service links

The confirmation email includes unique links the invitee can use to:

- **View** their booking details.
- **Reschedule** — pick a new available slot; the old slot is released.
- **Cancel** — open a confirmation page, then release the slot and notify both parties. A direct **Cancel this booking** link is included right in the email.

These actions work via a secure token; no login is required. The attached `.ics` calendar invite is a standard iTIP request, so it loads as an event card in Gmail and other mail clients.

## Guests

The invitee can bring guests along. On the booking form — and again when rescheduling — there is a **Guests** field: type an email address and press Enter (or Tab) to turn it into a chip. Add up to **10** guests per booking. The invitee's own address and any malformed or duplicate entries are dropped automatically, so a typo never blocks the booking.

Guests get their own calendar invite and stay in sync with the meeting:

- **Created** — when the booking is confirmed (or approved), each guest receives an email with an `.ics` invite that adds the meeting to their calendar.
- **Rescheduled** — moving the meeting sends every guest an updated invite that supersedes the old time in their calendar.
- **Cancelled** — cancelling the meeting (or declining an approval request that guests were already invited to) sends each guest a cancellation that removes the event from their calendar.

Guests **cannot** reschedule or cancel the meeting — only the invitee can. A guest who can't attend uses the **decline** link in their own invitation email: it removes them from the meeting, sends them a cancellation, and notifies the invitee (who may then want to reschedule). The guest invite deliberately has no calendar "Yes/No" buttons, so this decline link is the single, reliable way for a guest to bow out.

When the invitee reschedules, the **Guests** field is pre-filled with the current list. Adding a chip invites a new guest, removing one sends that guest a cancellation, and the rest receive the updated time.

## Reminders

calit sends a reminder email to the invitee before each confirmed meeting. The lead time is controlled by `REMINDER_LEAD_MINUTES` (default: `1440`, i.e. 24 hours before the meeting).

## Abuse protection

The public booking form has several layers of protection:

- **Cloudflare Turnstile** — bot-detection challenge on the form (requires setup; see [Turnstile setup](/calit/installation/turnstile/)).
- **Honeypot field** — a hidden field that only bots fill in; submissions that include it are silently rejected.
- **Per-email daily cap** — a single email address cannot make more than `PER_EMAIL_DAILY_CAP` bookings per day (default: 10) across the owner's meeting types.
