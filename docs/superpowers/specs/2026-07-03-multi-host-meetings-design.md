# Multi-host meeting types — design

**Date:** 2026-07-03
**Status:** Approved, ready for implementation plan

## Problem

A calit host wants a meeting type where **more than one host must be present**. Example: Pasha
creates a meeting type `/pasha/intro`; slots are only offered when **both Pasha and a second
host (Volodya) are free**; booking it puts the event on **both calendars** and blocks both hosts'
future availability. Any co-host must **consent** before the meeting type becomes bookable.

Hosts are always existing users on the **same instance**.

## Terminology

- **Creator** — the owner of the MeetingType (its `owner_id`). Exactly one.
- **Co-host** — any additional host on the same meeting type. Zero or more.
- **Host** — creator or co-host. A meeting type is **multi-host** iff it has ≥1 co-host.

## Non-goals

- Cross-instance / external hosts. All hosts are local users.
- Per-host duration, min-notice, horizon, or slot grid — those stay shared at the type level.
- Negotiating type settings between hosts. Creator owns type-level settings.

---

## 1. Data model

### New table `meeting_type_host`

One row per host of a meeting type. Single-host meeting types have **no** rows here (behavior
unchanged). Multi-host types have one CREATOR row + one COHOST row per co-host.

| column | type | notes |
|---|---|---|
| `id` | bigserial PK | |
| `meeting_type_id` | bigint FK → `meeting_type(id)` | |
| `owner_id` | bigint FK → `app_user(id)` | the host |
| `status` | varchar(16) | `PENDING` / `ACCEPTED` |
| `role` | varchar(16) | `CREATOR` / `COHOST` |
| `consent_token` | uuid | for the email accept link; null once responded |
| `buffer_before_minutes` | int NULL | per-host buffer override; null → inherit type |
| `buffer_after_minutes` | int NULL | per-host buffer override; null → inherit type |
| `created_at` | timestamptz | |
| `responded_at` | timestamptz NULL | when the host accepted/declined |

- Creator row is inserted `ACCEPTED` / `CREATOR` automatically when the first co-host is added.
- Unique `(meeting_type_id, owner_id)`.
- `MeetingType` gets **no new column**; `ownerId` stays = creator. Multi-host-ness is derived
  from the presence of co-host rows.

### `booking` change

- Add `group_id UUID NULL`.
- Single-host bookings leave it null (unchanged).
- A multi-host booking is **N booking rows** — one per host, each row's `owner_id` = that host —
  sharing one `group_id`, with identical `meeting_type_id`, `start_utc`, `end_utc`, invitee
  fields, and `answers`.

### Availability reuse (no new table)

`AvailabilityRule` and `DateOverride` already key on `(owner_id, meeting_type_id)`. Each host
stores their own per-type rules under **their own `owner_id`** + the shared `meeting_type_id`.
No schema change needed for per-host working hours.

---

## 2. Consent lifecycle

1. Creator adds co-hosts by username on the meeting-type edit form. Each new co-host → a
   `meeting_type_host` row `PENDING` with a fresh `consent_token`. The creator row is
   ensured `ACCEPTED`.
2. Each co-host is notified two ways: an **email** with a consent link (token), and a
   **dashboard request** on their `/me` (same surface style as booking approvals).
3. Co-host **accepts** (dashboard button or email link) → row `ACCEPTED`, `responded_at` set,
   `consent_token` cleared.
4. Co-host **declines / revokes**, or creator **removes** them → row deleted.
5. **Bookability gate:** a multi-host type is bookable/public only when **every** host row is
   `ACCEPTED`. Otherwise the public page shows it disabled ("awaiting co-host confirmation")
   and a booking POST is rejected. If a co-host later revokes, the type silently drops back to
   non-bookable until re-consented.

---

## 3. Slot intersection

Offered slots = the **intersection** of every host's free time on the creator's slot grid.

- **Type-level (shared, from the creator's `MeetingType`):** duration, slot interval/grid,
  min-notice, horizon, name/description/location/approval flag.
- **Per-host:**
  - **Working hours / date overrides** — each host contributes their own
    `(hostOwnerId, meetingTypeId)` availability rules + overrides, falling back to that host's
    global availability when they've set none. This is the existing
    `SlotService.windowsFor` precedence (per-type rule > global rule; per-type override >
    global override), simply evaluated once per host in that host's own timezone.
  - **Effective buffer** — host-row `buffer_before_minutes` / `buffer_after_minutes` if set,
    else the type's buffers.
  - **Busy** — that host's `Booking.heldOverlapping` + Google free/busy when that host has
    Google connected (existing per-owner `busyIntervals`).
- A slot is offered iff **every** host is free for it (windows cover it, and the host's
  buffered interval overlaps none of that host's busy).

### Implementation shape

`BookingService.availableSlots(type, from, to)` is refactored to compute a per-host filtered
slot set for each host and AND them:

- Determine host set: creator + accepted co-hosts (from `meeting_type_host`). Single-host types
  → just the creator (unchanged path).
- For each host, produce their bookable slots on the shared grid using that host's
  windows/overrides/buffer/busy.
- Intersect by slot start instant. Only slots present for all hosts survive.

`SlotService.generateRawSlots` currently reads `OwnerSettings`/availability for the type's owner;
it is generalized to accept the host `owner_id` whose windows/timezone drive that host's raw
slots, while duration/grid come from the (creator's) type.

---

## 4. Booking write

On a multi-host booking POST (`PublicResource.submitBooking` → `BookingService.book`):

1. Validate invitee + fields + abuse guards (unchanged).
2. Re-check intersection availability for the exact requested start across **all** hosts
   (`assertSlotAvailable` generalized to the host set). Reject if any host is no longer free.
3. In one `@Transactional` block, insert **N booking rows** (one per host, `owner_id` = host)
   sharing a new `group_id`, identical start/end/invitee/answers, each with its own
   `manageToken`. Status = `PENDING` if the type `requiresApproval`, else `CONFIRMED`.
4. The DB exclusion constraint is now owner-scoped (§6), so the N rows coexist; a real
   per-host overlap still maps to `BookingConflictException` (409).
5. Google events: for each host that has Google connected, create an event on **that host's**
   calendar. Attendees = invitee + all hosts' emails + active guests, so every calendar
   cross-invites the others. Store per-row `googleEventId`; share one Meet link across the
   group (minted once when the type is `GOOGLE_MEET`).
6. Fire domain events (§5).

---

## 5. Booking lifecycle

All group operations are all-or-nothing, resolved via `group_id`.

- **Approval (all hosts must approve — per decision Q7):** each PENDING host row carries its
  own `approvalToken`. Each host approves via their `/me` or the token link. The group confirms
  **only when every host row is approved** → at that point Google events are created for all
  connected hosts and `BookingConfirmed` fires. Any host declining → whole group → `DECLINED`,
  invitee and the other hosts notified. Auto-confirm types (`requiresApproval=false`) skip
  approval: N `CONFIRMED` rows + events immediately.
- **Cancel:** invitee (via manage link) or **any** host cancels → all rows → `CANCELLED`, all
  Google events deleted, invitee + all hosts notified.
- **Reschedule:** re-check intersection at the new time across all hosts → move all rows and all
  Google events together; reject if the new slot isn't free for every host.
- **Manage surfaces:** the invitee manage link resolves the whole group. Each host sees their
  own row in their `/me` bookings list; host-initiated approve/decline/cancel cascades to the
  group.

Scheduler jobs (`ReminderScheduler`, `PendingExpiryScheduler`) operate per row as today;
group-awareness only where a whole group must transition together (expiry of a PENDING group
declines the group).

---

## 6. Migrations

New sequential `V20`…`V*` files (never edit applied ones):

1. **`meeting_type_host`** table + unique `(meeting_type_id, owner_id)` + FKs.
2. **`booking.group_id UUID NULL`** + index on `group_id`.
3. **Owner-scope the double-booking guard.** The existing `booking_no_overlap_held` exclusion
   constraint (from `V4`) is **global** — it forbids overlaps across the *entire instance*,
   ignoring `owner_id`. That is a latent multi-tenant bug today and would make per-host rows
   collide. Drop it and re-create with `owner_id` in the gist:

   ```sql
   ALTER TABLE booking DROP CONSTRAINT booking_no_overlap_held;
   ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
       EXCLUDE USING gist (owner_id WITH =, tstzrange(start_utc, end_utc) WITH &&)
       WHERE (status IN ('PENDING','CONFIRMED'));
   ```
   (`btree_gist` already enabled in `V4`.)

---

## 7. UI, admin, authorization, i18n

### Creator — meeting-type edit form
- **Add-cohost input** with progressive-enhancement autocomplete (see §8): type a username, add.
- **Host list** with each host's status (creator / pending / accepted) and a remove control.
- Saving co-hosts issues consent requests (email + dashboard) to newly-added hosts.

### Co-host — `/me`
- **Pending co-host requests** surface (accept / decline), styled like booking approvals.
- **Availability editor for the shared type**: the same working-hours + date-override UI a host
  uses for their own meeting types, plus their per-host before/after buffer, writing
  `AvailabilityRule` / `DateOverride` / host-row buffer rows scoped to **their own `owner_id`** +
  this `meeting_type_id`.

### Public page
- A multi-host type that isn't fully accepted shows disabled with "awaiting co-host
  confirmation"; direct booking POST is rejected until all accepted.

### Authorization (critical, owner-scoped invariant)
- A co-host may read/write availability + buffer rows for a `meeting_type_id` **only if** they
  are an `ACCEPTED` host of it, and **only their own `owner_id`** rows. A host never sees or edits
  another host's windows.
- The creator manages the host set and type-level settings; the creator does **not** see or edit
  co-hosts' working hours.
- `CurrentOwner.require()` gates all of the above; every new query filters by the acting owner.

### i18n
- Every new user-facing string is a type-safe `@Message` key with the English default, plus a
  matching `de` **and** `he` value in `messages/{msg,adm}_{de,he}.properties`. No English-only
  fallback shipped.

---

## 8. Progressive enhancement & the no-JS rule

**Rule change (update `CLAUDE.md` + docs-site):** the old "no JavaScript ships at runtime" rule
becomes **progressive enhancement** — *every feature works without JavaScript; JS is optional,
kept minimal and simple, and only enhances.*

### Host autocomplete
- **Baseline (no JS):** a plain `<input name="cohost" class="input">` (daisyUI-styled). The host
  types the exact username; the server validates it exists + is eligible on submit. Fully
  functional, no autocomplete.
- **Enhancement (JS):** a small (~15-line) inline, debounced script calls
  `GET /me/hosts?q=<prefix>` (owner-authenticated, returns ≤20 matching usernames as JSON,
  excluding the creator and existing hosts) and rebuilds a `<datalist>` for live typeahead.
  Tiny payload, scales to any tenant size, no full username list ever shipped.
- daisyUI has no JS-free combobox; this is the minimal correct approach. Rejected: shipping a
  full static `<datalist>` of all usernames (bloats at thousands of users, no live filter).

---

## 9. Testing

Owner-scoped `@QuarkusTest`s against the admin-is-id-1 invariant:

- **Intersection math:** overlapping vs non-overlapping host windows; differing host timezones;
  per-host buffers shrinking overlap; a host's busy booking removing a slot.
- **Consent gating:** type not bookable (public disabled + POST rejected) until all hosts
  accept; becomes bookable on last acceptance; drops back on revoke.
- **Group booking write:** N rows created with shared `group_id`, correct per-host `owner_id`,
  owner-scoped exclusion constraint lets them coexist, real overlap still 409s.
- **All-hosts approval:** group confirms only after every host approves; any decline → group
  `DECLINED`; events created only on confirm.
- **Cancel / reschedule propagation:** any host or invitee action transitions the whole group;
  reschedule re-checks intersection.
- **Authorization:** co-host can edit only their own availability/buffer for a shared type and
  only when accepted; non-hosts rejected; creator can't touch co-host windows.
- **`/me/hosts?q=` endpoint:** owner-auth required, prefix filter, cap, excludes self/existing
  hosts.

---

## Open implementation notes (not blockers)

- Meet link sharing across per-host events: mint once, copy to each host's event, or create each
  event with the same conference request — verify Google API behavior during implementation.
- `PendingExpiryScheduler` group semantics: expiring one PENDING row should decline the group;
  confirm the query groups by `group_id`.
