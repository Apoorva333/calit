# Calendly-Look Visual Upgrade — Design

**Status:** Approved design (brainstorm), ready for implementation plan.

**Date:** 2026-06-08

## Goal

Push calit's already-shipped functional Pico redesign into a polished, Calendly-like look across every surface: a card-on-canvas public booking experience with a left info panel, an indigo accent, a left-sidebar admin shell, and a `<details>`-accordion meeting-type editor — all working in light **and** dark mode.

## Background

The functional redesign already shipped (plan `2026-06-08-modern-calendly-ui.md`): Pico CSS v2 via WebJar, shared `base.html`, top-nav `adminNav.html`, a JS-progressive two-pane calendar+time slot picker, `calit.css` custom layer. This upgrade is **visual only** — no domain, routing, or data-model changes. Every existing test invariant must survive (see "Invariants").

## Reference

Calendly / Upfluence booking page (card on soft canvas, left info panel with clock-icon duration), Calendly event-type editor (accordion sections: Duration / Location / Availability / Host, location picker tiles), Calendly left sidebar nav (logo, Create, icon items, badge).

## Design Decisions (all confirmed)

1. **Scope:** Everything — public pages + admin sidebar + admin accordion editor.
2. **Accent:** Switch to **indigo**. Real change: `base.html` (and the new `adminBase.html`) link `pico.indigo.min.css` instead of `pico.jade.min.css`. The webjar already ships `pico.indigo.min.css` (verified).
3. **Dark mode:** First-class. Pico's auto light/dark stays. `calit.css` must reference **Pico CSS variables only** (e.g. `--pico-primary`, `--pico-card-background-color`, `--pico-muted-border-color`) — no hardcoded hex — plus a small set of new `--calit-*` tokens defined for both schemes via Pico's `[data-theme]` hooks. The mockups used hex purely for preview; production is variable-driven so it flips with the OS theme.
4. **Booking page:** Card floating on a soft canvas. Inside the card, a left info panel (host name, title, clock-icon duration, description, location/approval lines) and a right "Select a Date & Time" pane wrapping the **existing** `.booking-grid` calendar+time picker unchanged.
5. **Admin shell:** Fixed **left sidebar** replaces the top nav — brand, "Create" affordance, icon nav items (active item highlighted, Pending shows count badge), Log out pinned at the bottom. Collapses to a horizontal scrolling top bar on narrow screens (CSS only, no JS).
6. **Meeting-type editor:** The create form becomes Calendly-style **native `<details>`/`<summary>` accordion** sections (zero JS, works without it): **Basics · Duration · Location · Scheduling limits**. Location renders as **radio-tile picker** (GOOGLE_MEET / PHONE / IN_PERSON / CUSTOM) instead of a `<select>`, keeping `name="locationType"`. The GOOGLE_MEET warning note stays.

## Architecture

All work lives in templates + `calit.css` + tiny Java template-arg additions. No new endpoints, no schema changes.

### Foundation (`calit.css` + base templates)

- Switch the Pico stylesheet link to indigo in `base.html`.
- Add `--calit-*` tokens (canvas background, card surface, card shadow, sidebar width) defined for both light and dark using Pico's existing variable system. Body/canvas background uses the token; cards use `--pico-card-background-color`.
- New reusable classes: `.calit-card` (white/dark surface, radius, border, shadow), `.canvas` body backdrop.
- Keep all existing classes (`.booking-grid`, `.calendar*`, `.slot*`, `.tz-bar`, `.badge`, `.type-grid`, `.err`).

### Icons

A minimal inline-SVG set used inline in templates (no icon font, no extra requests): **clock** (duration), **location-pin** / **video** / **phone** (location types), plus the sidebar nav glyphs. `stroke="currentColor"` so they inherit color and adapt to dark mode. Kept as literal SVG in the templates (Qute has no component system; duplication is acceptable and the SVGs are tiny).

### Public pages

- **`base.html`:** body gets the `.canvas` backdrop. A new optional `{#insert}` is not needed — public pages opt into the card by wrapping their content in `<article class="calit-card">` (Pico `<article>` already gives card affordances; `.calit-card` tunes radius/shadow/centering).
- **`landing.html`:** unchanged structure; the existing `.type-grid` cards get the refreshed surface via Pico/indigo. Centered on canvas.
- **`book.html`:** restructure into a `.book-shell` card with two columns:
  - `.book-info` (left): a logo/initial chip, host name (`OwnerSettings.ownerName`), `type.name` as H1, a clock-icon + `type.durationMinutes` min line, `type.description` (when present), and the location/approval lines currently shown.
  - `.book-main` (right): an H3 "Select a Date & Time" followed by the **existing** `tzBar` + `<form>` + `.booking-grid` block, fully intact (calendar mount `#calendar`, `.day-slots` sections, `name="startUtc"` radios, `data-utc` times, honeypot, Turnstile, name/email/custom fields, submit button text). On narrow screens the two columns stack (info on top).
  - New template arg: `ownerName` (String) passed to `book(...)`; everything else unchanged.
- **`confirmation.html`, `manage.html`, `cancelled.html`, `notReady.html`:** wrapped in `.calit-card` on the canvas; content, headings, button text, tz bar/script, `data-utc` times unchanged.

### Admin shell (sidebar)

- **New `adminBase.html`:** a full layout (doctype, head with indigo Pico + calit.css) whose body is a `.admin-shell` grid of `<aside class="admin-side">` (from the rewritten `adminNav.html` include) + `<div class="admin-main">{#insert}{/insert}</div>`. Admin pages switch from `{#include base}` + top-nav include to `{#include adminBase}`.
- **`adminNav.html`** rewritten as the sidebar `<aside>`: brand chip + "calit", a "Create" link (points to `/admin/meeting-types` — calit's primary create action), icon nav items (Dashboard, Pending [+badge], Meeting types, Availability, Date overrides, Booking fields, Settings, Google), and "Log out" pinned at the bottom (`margin-top:auto`). Takes `pendingCount` (existing) plus a new `active` String to highlight the current item.
- **Active highlight:** each admin page passes an `active="..."` key (e.g. `"dashboard"`, `"meetingTypes"`). Implemented as a template arg on `adminBase`/`adminNav`; no Java signature change is required if passed positionally through the include, but the cleanest path is adding a `String active` (and reusing existing `pendingCount`) — see plan.
- **Responsive:** `.admin-shell` is a 2-col grid ≥48rem; below that it collapses to one column and `.admin-side` becomes a horizontally scrolling top bar (`overflow-x:auto`, flex row). No JS.
- All other admin page **bodies** (dashboard, pending, availability, dateOverrides, bookingFields, settings, google) keep their current content; only their layout wrapper changes from `base`+top-nav to `adminBase`, and they gain the `active` arg.

### Meeting-type editor (accordion)

- **`meetingTypes.html`:** the existing list of meeting types stays on top (cards with `secret`/`inactive`/`approval` badges, toggle/delete forms). Below it, the **Create meeting type** `<form>` is restructured into native `<details>` sections, each `<summary>` styled as a Calendly section header (icon + title + chevron via CSS marker):
  - **Basics** (open by default): `name`, `slug`, `secret`, `requiresApproval`.
  - **Duration:** `durationMinutes`, `slotIntervalMinutes`.
  - **Location:** radio tiles for GOOGLE_MEET / PHONE / IN_PERSON / CUSTOM (`name="locationType"`, styled via `:checked`), `locationDetail`, and the GOOGLE_MEET info note.
  - **Scheduling limits:** `minNoticeMinutes`, `horizonDays`, plus a link to `/admin/availability` ("Edit weekly hours →").
  - Footer: the `Create` submit button.
- All form field `name="..."` attributes are preserved exactly so `createMeetingType` keeps working. The `<select name="locationType">` becomes radio tiles with the same name/values.

## Invariants (must survive — pinned by existing tests)

- `StaticAssetsTest` checks the Pico WebJar path — **update it from `pico.jade.min.css` to `pico.indigo.min.css`** (the one deliberate test change).
- Booking/reschedule slots stay `<input type="radio" name="startUtc" value="{utc-instant}">` with a child `<time data-utc=...>`; calendar mount `#calendar`; `.day-slots[data-date]` sections; `CALIT_CALENDAR` marker present.
- Honeypot `name="website"` (hidden) and, when enabled, `class="cf-turnstile"` + `data-sitekey` + the `challenges.cloudflare.com/turnstile/v0/api.js` loader stay on the book page.
- `CALIT_TZ_REFORMAT` script + "Times shown in:" bar stay on invitee pages.
- Button text exactly: `Confirm booking` / `Request` / `Reschedule to selected time` / `Cancel this booking`; confirmation headings `You're booked` / `Request sent — pending owner approval`.
- All admin form field `name` attributes unchanged; `secret`/`inactive`/`approval` badges keep their text; `Approve`/`Decline`/`Connect Google`/`Deactivate`/`Activate`/`Delete`/`Create` button text unchanged.
- If `AdminMeetingTypesTest` asserts the `<select>` markup for location, **update it** to assert the radio-tile `name="locationType"` inputs instead.

## Non-Goals (YAGNI)

- No new meeting-type fields, no "More options" extra section, no per-type edit page (create form only, as today).
- No icon font / icon library dependency.
- No JS for the accordion (native `<details>`) or the sidebar collapse (CSS only). The existing calendar/tz JS is unchanged.
- No changes to availability, date-overrides, booking-fields, settings page **bodies** beyond the shell swap.

## Testing Strategy

- Keep every existing RestAssured test green (update only the two noted: WebJar path, location markup).
- Add focused assertions: book page renders `.book-info`/info-panel marker + still has calendar markup; admin pages render the sidebar (`admin-side`) and active highlight; meeting-type create form renders `<details>` sections + `name="locationType"` radio tiles.
- Visual verification of light + dark mode at the end (manual, against the approved mockups).

## File Inventory

**Modify:**
- `src/main/resources/templates/base.html` — indigo link, `.canvas` body.
- `src/main/resources/META-INF/resources/calit.css` — indigo-aware tokens, `.calit-card`, `.canvas`, `.book-shell`/`.book-info`/`.book-main`, `.admin-shell`/`.admin-side` + responsive, `<details>` accordion styling, `.loc-tiles` radio tiles, sidebar/active styles. Variables only (dark-safe).
- `src/main/resources/templates/adminNav.html` — rewritten as sidebar `<aside>`, takes `active`.
- `src/main/resources/templates/PublicResource/book.html` — two-column info+main card; new `ownerName` context.
- `src/main/resources/templates/PublicResource/{landing,confirmation,manage,cancelled,notReady}.html` — card-on-canvas wrap.
- All 8 `AdminResource/*.html` — switch to `adminBase`, pass `active`; `meetingTypes.html` additionally gets the accordion editor + location tiles.
- `src/main/java/com/calit/web/PublicResource.java` — add `ownerName` arg to `book(...)` + call sites.
- `src/main/java/com/calit/web/AdminResource.java` — add `active` arg to the 8 admin template methods + handlers (and `pending`/`google` already special-cased).
- `src/test/java/com/calit/web/StaticAssetsTest.java` — indigo path.
- `src/test/java/com/calit/web/AdminMeetingTypesTest.java` — location-tiles assertion (only if it pins the `<select>`).

**Create:**
- `src/main/resources/templates/adminBase.html` — sidebar admin layout shell.

**No changes:** `Layout.java` (TZ_BAR/TZ_SCRIPT/CALENDAR_SCRIPT all reused as-is), domain, migrations, services, routing.
