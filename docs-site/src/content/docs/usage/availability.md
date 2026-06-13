---
title: Availability & overrides
---

calit computes bookable time slots dynamically from your weekly availability rules, date-specific overrides, existing bookings, and the constraints set on each meeting type.

## Weekly availability rules

Set the days and hours you are generally available each week. These are your baseline recurring windows — for example, Monday–Friday 09:00–17:00.

![Weekly availability editor](/calit/img/availability.png)

Rules can be global (apply to all your meeting types) or scoped to a specific meeting type.

## Date overrides

Date overrides replace the weekly rule for a specific calendar date with replace semantics — the override takes precedence over the weekly schedule entirely for that day.

![Date overrides](/calit/img/date-overrides.png)

Two override modes are available:

- **Block a date** — set the date as unavailable regardless of the weekly schedule. No slots are offered on that day.
- **Custom windows** — define one or more custom time windows for that specific date (useful for a day when your hours differ from the norm).

Like rules, overrides can be global or scoped to a single meeting type. A per-type override takes precedence over a global one for the same date.

## How slots are computed

When an invitee views your booking page, calit calculates the available slots by:

1. Starting from your weekly availability windows (or the date override if one exists for that day).
2. Subtracting time blocked by existing confirmed or pending bookings, plus any buffer-before and buffer-after configured on the meeting type.
3. Discarding slots that fall within the **minimum notice** window (too soon to book).
4. Discarding slots beyond the **booking horizon** (too far in the future).

## Timezone handling

Invitees see all slots in **their own local timezone** (detected from their browser). You configure your own timezone in `/me/setup` or in your account settings; all availability rules are interpreted in your timezone, and converted for each visitor automatically.
