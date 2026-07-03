# Multi-host meeting types — design

**Date:** 2026-07-03
**Status:** Approved (design + grilling complete), ready for implementation plan

## Problem

A calit host wants a meeting type where **more than one host must be present**. A creator
(e.g. Pasha) makes a meeting type `intro`; slots are only offered when **every host is free**;
booking it puts **one** event on all hosts' calendars and blocks each host's future
availability. Every co-host must **consent** before the type becomes bookable.

All hosts are existing, enabled users on the **same instance**.

## Terminology

- **Creator** — the owner of the MeetingType (its `owner_id`). Exactly one.
- **Co-host** — an additional host on the same meeting type. Zero or more.
- **Host** — creator or co-host. A meeting type is **multi-host iff it has ≥1 co-host**.
- **Group** — the set of N booking rows (one per host) produced by one multi-host booking,
  linked by a shared `group_id`.
- **Lead row** — the booking row owned by the creator (`CREATOR` host); the canonical row for
  invitee-facing token, reminders, and expiry.

## Non-goals

- Cross-instance / external hosts.
- Per-host duration, min-notice, horizon, or slot grid (these stay shared at the type level;
  only working-hours + buffers are per-host).
- Negotiating type settings between hosts (creator owns type-level settings).
- Cross-listing a shared type as a *second bookable slug* under a co-host namespace (aliases
  resolve to the one canonical type; see §8).

---

## 1. Data model

### New table `meeting_type_host`

One row per host of a multi-host type. Single-host types have **no** rows (behavior unchanged).
Multi-host types have one `CREATOR` row + one `COHOST` row per co-host.

| column | type | notes |
|---|---|---|
| `id` | bigserial PK | |
| `meeting_type_id` | bigint FK → `meeting_type(id)` ON DELETE CASCADE | |
| `owner_id` | bigint FK → `app_user(id)` ON DELETE CASCADE | the host |
| `status` | varchar(16) | `PENDING` / `ACCEPTED` |
| `role` | varchar(16) | `CREATOR` / `COHOST` |
| `consent_token` | uuid NULL | one-click email accept; cleared on response |
| `buffer_before_minutes` | int NULL | per-host buffer override; null → inherit type |
| `buffer_after_minutes` | int NULL | per-host buffer override; null → inherit type |
| `created_at` | timestamptz | |
| `responded_at` | timestamptz NULL | when the host accepted/declined |

- Unique `(meeting_type_id, owner_id)`.
- The `CREATOR` row is inserted `ACCEPTED` automatically when the **first** co-host is added;
  it is deleted when the **last** co-host is removed (→ back to plain single-host).
- `MeetingType` gets **no new column**; multi-host-ness is derived from the presence of
  `COHOST` rows. `ownerId` stays = creator.

### `booking` change

- Add `group_id UUID NULL` (+ index).
- Single-host bookings leave it null (unchanged).
- A multi-host booking = **N rows**, one per host (`owner_id` = that host), sharing one
  `group_id`, identical `meeting_type_id` / `start_utc` / `end_utc` / invitee fields / `answers`
  / `title` / `description`. Each row keeps its own `manageToken` and `approvalToken`; the
  invitee uses only the **lead row's** `manageToken` (§5).
- `googleEventId` / `meetLink` are stored on the **organizer's** row only (§4); other rows leave
  them null.

### Availability reuse (no new table)

`AvailabilityRule` and `DateOverride` already key on `(owner_id, meeting_type_id)`. Each host
stores their own per-type rules under **their own `owner_id`** + the shared `meeting_type_id`.
Per-host buffers live on the `meeting_type_host` row. No schema change for per-host working hours.

---

## 2. Consent lifecycle

1. Creator adds co-hosts by username on the meeting-type edit form. Each new co-host → a
   `meeting_type_host` row `PENDING` with a fresh `consent_token`. The `CREATOR` row is
   ensured `ACCEPTED`. **Idempotent:** adding a host already `PENDING` is a no-op (no duplicate
   row, no repeat email).
2. Notify the co-host two ways: an **email** with a one-click consent link, and a **dashboard
   request** on their `/me`.
3. **Accept / decline paths (both valid):**
   - **One-click email:** public `GET /consent/{token}` → confirmation page ("Accept co-hosting
     *intro* with Pasha?") → `POST` accept/decline. No login required — the unguessable token
     (emailed to the host's registered address) authorizes it, same trust model as booking
     `approvalToken`/`manageToken`. `consent_token` cleared on response.
   - **Dashboard:** logged-in host sees the pending request on `/me` and accepts/declines there.
4. Co-host **declines / revokes**, or creator **removes** them → row deleted (subject to §6).
5. **Bookability gate:** a multi-host type is bookable/public only when **every** host row is
   `ACCEPTED` **and** every host account is `enabled` (§13) **and** no host's Google calendar is
   broken (§3 fail-closed). Otherwise the public page shows it disabled ("awaiting co-host
   confirmation" / "temporarily unavailable") and a booking `POST` is rejected.

---

## 3. Slot intersection

Offered slots = the **intersection** of every host's free time on the creator's slot grid.

- **Type-level (shared, from the creator's `MeetingType`):** duration, slot interval/grid,
  min-notice, horizon, name/description/location/approval flag.
- **Per-host:**
  - **Working hours / date overrides** — each host's own `(hostOwnerId, meetingTypeId)`
    availability + overrides, falling back to that host's global availability when unset
    (existing `SlotService.windowsFor` precedence, run once per host in that host's own tz).
  - **Effective buffer** — host-row `buffer_before_minutes`/`buffer_after_minutes` if set, else
    the type's buffers.
  - **Busy** — that host's `Booking.heldOverlapping` + Google free/busy when that host has
    Google connected (existing per-owner `busyIntervals`).
- A slot is offered iff **every** host is free for it (windows cover it and the host's buffered
  interval overlaps none of that host's busy).

### Disconnected Google (fail-closed per host)

`GoogleCalendarPort.freeBusy` is **fail-closed**: it throws `CalendarUnavailableException` when a
host's connected Google account is disconnected / `needsReconnect`. (A host who *never* connected
Google is unaffected — busy = just their calit bookings.) If any host's calendar is
connected-but-broken, that host contributes **zero** free time → intersection empty → the type
shows **no slots** ("temporarily unavailable") until reconnected. Never offer a slot we can't
verify. The affected host already gets the reconnect nudge (`needsReconnect`/`reconnectNotifiedAt`);
additionally the creator's host list shows a per-host "calendar needs reconnect" indicator.

### Implementation shape

`BookingService.availableSlots(type, from, to)` is refactored to compute a per-host filtered slot
set for each host (creator + accepted co-hosts) and **AND** them by slot-start instant. Single-host
types → just the creator (unchanged path). `SlotService.generateRawSlots` is generalized to accept
the host `owner_id` whose windows/timezone drive that host's raw slots, while duration/grid come
from the creator's type. Cost: H freeBusy calls + H raw-slot passes per page load; H bounded by the
10-host cap (§14).

---

## 4. Booking write

On a multi-host booking `POST` (`PublicResource.submitBooking` → `BookingService.book`):

1. Validate invitee + fields + abuse guards. The per-email daily cap counts **one per booking**
   (standalone rows + distinct `group_id`), so a multi-host booking = 1 (§20).
2. Re-check intersection availability for the exact requested start across **all** hosts
   (`assertSlotAvailable` generalized to the host set), including a **fail-closed reject** if any
   host's Google calendar is broken. Reject if any host is no longer free.
3. In one `@Transactional` block, insert **N booking rows** (one per host, `owner_id` = host)
   sharing a new `group_id`, identical start/end/invitee/answers, each with its own `manageToken`
   and (if approval) `approvalToken`. Status = `PENDING` if the type `requiresApproval`, else
   `CONFIRMED`. The owner-scoped exclusion constraint (§6/§migrations) lets the N rows coexist; a
   real per-host overlap maps to `BookingConflictException` (409) and rolls back the **whole**
   group (atomic — no partial group).
4. **One Google event, not N.** Choose the organizer host:
   - creator, if the creator has Google connected;
   - else the accepted host with Google connected chosen deterministically (lowest `owner_id`);
   - else **no Google event** (calit-only booking; if `locationType=GOOGLE_MEET` there's simply
     no Meet link — same as today's single-host "owner not connected").

   Create the event on the organizer's calendar with **all other hosts + invitee + active guests
   as attendees** (`sendUpdates=all`, which `createEvent` already does). Google then puts it on
   every attendee's calendar, mints **one** Meet link (when `GOOGLE_MEET`), and marks each invited
   host busy in their own free/busy. Store `googleEventId`/`meetLink` on the organizer's row.
   Hosts **without** Google are still blocked by their calit booking row.
5. Fire **one group-level** domain event (§5). For approval types, the Google event is **not**
   created here — only on full approval (§10).

---

## 5. Booking lifecycle

All group operations are all-or-nothing, resolved via `group_id`.

### Domain events & email fan-out
Each lifecycle transition fires **one group-level domain event** (carrying `group_id` + the host
set), **not** one per row. `EmailService` then fans out: **one** email to the invitee and **one
personalized** email to **each host** (with that host's own approve/manage link). The invitee
never gets N duplicates.

### Approval (all hosts must approve)
- `requiresApproval` type → all host rows created `PENDING`, each with its own `approvalToken`.
  The slot is **held from creation** (the exclusion constraint counts `PENDING` as held — no
  race).
- A host approves (token or `/me`) → flip **that row** `PENDING → CONFIRMED`. If **no row is
  still `PENDING`**, this was the last approval → **now** create the single Google event (§4) and
  fire `BookingConfirmed` (one invitee email + per-host confirmations).
- Any host **declines** → whole group → `DECLINED`; notify invitee + already-approved hosts. No
  Google event existed yet, so nothing to delete.
- Auto-confirm types (`requiresApproval=false`) → all rows `CONFIRMED` at booking, Google event
  immediately.

### Cancel
Invitee (manage link) or **any** host cancels → all rows → `CANCELLED`, the one Google event
deleted, invitee + all hosts notified (single fan-out).

### Reschedule (invitee or any host; PENDING or CONFIRMED)
- Allowed on both PENDING and CONFIRMED groups.
- Re-check the full intersection at the new time (fail-closed on broken calendars); reject
  otherwise. Move **all rows + the one Google event**, bump the lead row's `icsSequence`,
  re-send .ics to invitee + hosts, reset the lead reminder.
- **Reschedule of an `requiresApproval` booking returns it to approval** (this also changes
  existing **single-host** behavior — a deliberate part of this work, with its own tests +
  changelog note):
  - **Invitee-initiated** → all host rows reset to `PENDING`; every host re-approves.
  - **Host-initiated** → the **initiating** host's row stays approved (they chose the time); all
    **other** host rows reset to `PENDING`. (Single-host: sole host initiated → stays confirmed,
    no self-re-approval.)
  - On revert-to-`PENDING`, the existing Google event is **deleted** and **recreated on full
    re-confirmation**. The slot stays **held** throughout. Hosts get the actionable "approve the
    new time" email.
  - Non-approval types → no approval concept; reschedule just moves the event, stays confirmed.

### Editable details (V19 feature)
- **Title/description** edits apply at the **group level** → written to all N rows (kept
  identical) + update the Google event. Editable by invitee or any host.
- **Guests** attach to the **lead row only** (one guest set per meeting), surface as attendees on
  the single Google event, and use the existing guest .ics fan-out. Add/remove propagates to the
  event + guest emails via the group.

### Reminders
Create a `reminder` row for **only the lead row** of a group. On fire, `ReminderDue` carries
`group_id` and `EmailService` fans out (one invitee + one per host). Other rows get no reminder.

### Pending expiry
`PendingExpiryScheduler` evaluates expiry off the **lead row** (all rows share `createdAt`). If
the window elapses without **full** approval (any row still `PENDING`) → whole group → `DECLINED`,
release the hold, single-fan-out notify. Fully-confirmed groups: nothing to do.

### Invitee manage handle
The invitee's link uses the **lead (CREATOR) row's `manageToken`**; `findByManageToken` resolves
that row → loads the group via `group_id` → cancel/reschedule/edit apply to all rows + the one
Google event. Hosts manage from their own `/me` row (host action also cascades to the group).

---

## 6. Consent revoke / host removal with existing bookings

When a co-host **revokes** (or the creator **removes** them) **and** future non-cancelled bookings
(`PENDING` + `CONFIRMED`) exist for that host on that type: show a **warning interstitial** listing
the count with two explicit choices —

- **Keep** — honor existing bookings; only stop new ones (host removed / type non-bookable going
  forward). PENDING kept pending.
- **Cancel** — cascade-cancel all those future bookings (group cancel, delete Google events, notify
  invitee + other hosts). PENDING declined.

The choice is made by **whoever performs the action** (co-host if they revoke, creator if they
remove). If no future bookings exist → revoke immediately, no prompt.

---

## 7. Owner-scoped double-booking constraint

The existing `booking_no_overlap_held` exclusion constraint (from `V4`) is **global** — it forbids
overlaps across the *entire instance*, ignoring `owner_id`. That is a latent multi-tenant bug today
and would make per-host rows collide. A new migration drops it and re-creates it owner-scoped:

```sql
ALTER TABLE booking DROP CONSTRAINT booking_no_overlap_held;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
    EXCLUDE USING gist (owner_id WITH =, tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status IN ('PENDING','CONFIRMED'));
```
(`btree_gist` already enabled in `V4`.)

---

## 8. Public routing, aliases, slug collisions

### Aliases
Every host's namespace is a valid alias to the same page + same data: `/{anyHost}/{slug}` renders
the identical shared type. **Regardless of which alias URL is used, the owner / slot / booking
context = the type's creator + full host set** — the URL username only selects the alias, never
changes whose settings drive the page. `POST` under any alias routes to the same
`book(creatorOwnerId, slug)`.

### Slug-collision hard-block (makes aliases unambiguous)
Adding a co-host is **rejected** if the slug isn't free in that host's namespace. Enforced
symmetrically at every entry point so the invariant stays true:
- **Adding a co-host** → reject if they already own a type with that slug, or are a host of another
  type with that slug.
- **Creating / renaming a multi-host type** → slug must be free in **every** host's namespace.
- **A host creating / renaming their own personal type** → reject if it collides with a multi-host
  type they're a host of.

Result: within a multi-host type's host set the slug is free everywhere → each alias resolves to
exactly one type, no own-vs-alias precedence needed. Blocked-add error to the creator: "Volodya
already uses the slug `intro` — pick a different slug or ask them to free it."

### Visibility
- A multi-host type is listed on **every** host's landing (`/{creator}` and each `/{cohost}`) when:
  `active` ∧ **not** `secret` ∧ all hosts `ACCEPTED` ∧ enabled. `listPublic(ownerId)` gains a
  union: types owned by `ownerId` **plus** multi-host types where `ownerId` is an `ACCEPTED` host.
- `secret = true` → hidden on **all** landings, still bookable by direct link (global switch —
  "hidden = hidden everywhere").
- Pending consent / disabled host / broken calendar → not listed; direct page shows
  disabled-with-reason.
- **Canonical URL** for emails / manage links / co-host landing entries = the **creator's**
  `/{creator}/{slug}` (aliases stay valid, one form is canonical for outbound links).

---

## 9. `.ics` invites (keep hand-rolled `IcsBuilder`, no ical4j)

Reuse the existing **per-recipient** .ics pattern (guests already work this way; no
multi-ATTENDEE VEVENT needed):
- Each recipient (invitee + each host) gets their own .ics: **ORGANIZER = the group's Google-
  organizer host** (creator-preferred per §4; creator if no Google), **ATTENDEE = that recipient**.
- **Shared stable UID per group** (derived from `group_id`) so every recipient's .ics — and every
  reschedule update — maps to the **one** logical event in their calendar clients.
- **SEQUENCE = the lead row's `icsSequence`**, bumped once per group reschedule.
- Attach .ics to a recipient only when they're **not** covered by the native Google event (same
  "Google connected ⇒ skip .ics" rule already in place).

**ical4j deferred:** the hand-rolled builder already ships and produces the same bytes; ical4j
earns its place only for recurrence (RRULE) or proper VTIMEZONE. Pre-existing latent gap (orthogonal
to this feature): the builder doesn't fold lines >75 octets (RFC 5545 §3.1) — file separately.

---

## 10. UI, admin, authorization, i18n

### Creator — meeting-type edit form
- **Add-cohost input** with progressive-enhancement autocomplete (§11).
- **Host list** with each host's status (creator / pending / accepted) + per-host "calendar needs
  reconnect" indicator + a remove control (triggering §6 interstitial when needed).
- Total hosts capped at **10** (creator + 9 co-hosts); reject past it (§14).
- Saving newly-added co-hosts issues consent requests (email + dashboard); idempotent (§2).

### Co-host — `/me`
- **Pending shared-meeting requests** surface (accept / decline), styled like booking approvals.
- **"Shared meetings"** section listing accepted shared types (name, creator, canonical link),
  each opening the **existing per-type availability + buffer editor** scoped to their own
  `owner_id` + that `meeting_type_id`. Default = their global availability until customized (no
  forced setup on accept).

### Public page
- A multi-host type that isn't fully accepted / has a disabled host / broken calendar shows
  disabled with the reason; direct booking `POST` rejected until bookable.

### Authorization (owner-scoped invariant)
- A co-host may read/write availability + buffer rows for a `meeting_type_id` **only if** they are
  an `ACCEPTED` host of it, and **only their own `owner_id`** rows. A host never sees or edits
  another host's windows; the creator can't see/edit co-hosts' hours.
- Consent-token endpoints authorize by unguessable token; dashboard paths by `CurrentOwner`.
- Every new query filters by the acting owner. `CurrentOwner.require()` gates the `/me` surfaces.

### Notifications (per-host prefs)
- **Always send, ignoring `ownerNotificationsEnabled`:** consent request, approval-needed
  (actionable — required for the meeting to proceed; suppressing them would deadlock the flow).
- **Respect each host's `ownerNotificationsEnabled`:** auto-confirm FYI, reminders, and
  cancelled/rescheduled/declined notices (informational).
- Invitee: always notified.

### i18n
Every new user-facing string is a type-safe `@Message` key with the English default **plus** a
matching `de` **and** `he` value in `messages/{msg,adm}_{de,he}.properties`. No English-only
fallback shipped.

---

## 11. Progressive enhancement & the no-JS rule

**Rule change (update `CLAUDE.md` + docs-site):** "no JavaScript ships at runtime" becomes
**progressive enhancement** — *every feature works without JavaScript; JS is optional, kept minimal
and simple, and only enhances.*

### Host autocomplete
- **Baseline (no JS):** a plain daisyUI-styled `<input name="cohost" class="input">`. Type the
  exact username; the server validates it against the **same eligibility predicate** used by the
  endpoint (below) on submit. Fully functional, no autocomplete.
- **Enhancement (JS):** a small (~15-line) inline, debounced script calls
  `GET /me/hosts?q=<prefix>` (owner-authenticated) and rebuilds a `<datalist>` for live typeahead.
  Tiny payload, scales to any tenant size, no full username list ever shipped.
- **Eligibility (endpoint + server-side submit validation, identical predicate):**
  `enabled ∧ settingsComplete ∧ not-self ∧ not-already-a-host-of-this-type`; results capped (≤20).
- daisyUI has no JS-free combobox; this is the minimal correct approach.

---

## 12. Migrations

New sequential `V20`…`V*` (never edit applied ones):
1. `meeting_type_host` table + unique `(meeting_type_id, owner_id)` + FKs (ON DELETE CASCADE).
2. `booking.group_id UUID NULL` + index.
3. Owner-scope `booking_no_overlap_held` (§7).

---

## 13. Testing

Owner-scoped `@QuarkusTest`s against the admin-is-id-1 invariant:

- **Intersection math:** overlapping vs non-overlapping host windows; differing host timezones;
  per-host buffers shrinking overlap; a host's busy booking removing a slot; broken host calendar
  → empty (fail-closed); disabled host → non-bookable.
- **Consent gating:** not bookable (public disabled + POST rejected) until all hosts accept +
  enabled; becomes bookable on last acceptance; drops back on revoke/disable; idempotent add.
- **Consent token:** one-click `GET/POST /consent/{token}` accept + decline; token cleared;
  dashboard path parity.
- **Group booking write:** N rows, shared `group_id`, correct per-host `owner_id`, owner-scoped
  constraint lets them coexist, real overlap → 409 rolls back the whole group; single Google event
  on the chosen organizer (creator-preferred → lowest-owner_id → none).
- **All-hosts approval:** group confirms only after every host approves; event created only on last
  approval; any decline → group `DECLINED`; slot held while pending.
- **Cancel / reschedule propagation:** any host or invitee action transitions the whole group;
  reschedule re-checks intersection; **approval-required reschedule resets non-initiating hosts to
  PENDING and drops/recreates the Google event** (assert single-host case too); guest/title/desc
  edits propagate group-wide.
- **Revoke interstitial:** Keep vs Cancel over existing PENDING+CONFIRMED bookings; actor = the
  performer.
- **Fan-out / dedup:** one invitee email + one per host per transition; one reminder per group; abuse
  cap counts one per group.
- **Aliases + slug block:** `/{anyHost}/{slug}` resolves the same type with creator context;
  symmetric slug-collision rejection at all three entry points; `secret` hides everywhere.
- **Authorization:** co-host edits only their own availability/buffer for an accepted shared type;
  non-hosts rejected; creator can't touch co-host windows.
- **`/me/hosts?q=`:** owner-auth, prefix filter, cap, eligibility predicate; same predicate rejects
  an ineligible submitted username on the no-JS path.
- **Notifications:** actionable emails ignore `ownerNotificationsEnabled`; informational respect it.
- **Transitions:** add first co-host / remove last co-host flips multi↔single cleanly; existing
  bookings untouched.

---

## 14. Open implementation notes (not blockers)

- Meet-link sharing: `sendUpdates=all` on a single event naturally shares the link; verify Meet
  conference behavior when the organizer is a co-host (not the creator).
- `PendingExpiryScheduler` / `ReminderScheduler` must group by `group_id` (drive off the lead row)
  to avoid N-fold processing.
- Host cap constant = 10; `// ponytail: constant cap, revisit only if a real use-case needs more`.
- No consent-spam cooldown; `// ponytail: rely on 10-host cap + idempotent add; add a per-target
  throttle only if abuse shows up`.
