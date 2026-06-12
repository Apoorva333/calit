# calit: Pico CSS → DaisyUI Migration — Design

**Date:** 2026-06-11
**Status:** Approved (design); pending implementation plan

## Summary

Replace Pico CSS v2 with Tailwind CSS v4 + daisyUI 5 across all 16 HTML page
templates. Keep the existing server-rendered, full-page-reload model and the
existing vanilla JS (calendar widget, slug autofill, timezone scripts). Build
the CSS with the Tailwind **standalone CLI** (no Node.js / node_modules) wired
into the Maven build. Introduce a custom indigo daisyUI theme (light + dark,
auto via OS + a manual toggle). Fold the findings from the design review into
the rewrite, since every template is being rewritten anyway.

Transactional **email** templates are out of scope (inline-CSS / table layout;
different constraints).

## Goals

- Swap the CSS framework from Pico to Tailwind v4 + daisyUI 5.
- Preserve current behavior: server-rendered Qute templates, POST-and-reload
  forms, existing vanilla JS.
- No Node.js toolchain in build or runtime.
- Custom indigo theme keeping today's accent; light + dark; auto + manual toggle.
- Fix the design-review issues during the rewrite (consistency, destructive-action
  styling, admin time formatting, weak landing/dashboard/google pages).

## Non-Goals (YAGNI)

- No HTMX / Alpine / SPA behavior. (Can be added later.)
- No email-template restyling.
- No new frontend test framework.
- No unrelated backend refactoring (only the minimal touchups below).

## Current State

- Quarkus 3.36.1, Maven, Qute templates (`@CheckedTemplate`).
- Pico v2 via WebJar (`org.webjars.npm:picocss__pico:2.1.1`), served at
  `/webjars/picocss__pico/css/pico.indigo.min.css`.
- Hand-written custom layer `src/main/resources/META-INF/resources/calit.css`
  (cards, booking grid, calendar widget, slot pills, admin sidebar, accordion,
  location tiles).
- Two base templates: `base.html` (public, centered card on canvas) and
  `adminBase.html` (sidebar shell).
- 16 page templates: 6 public, 8 admin, 1 login, plus base/adminBase.
- Dark mode today: Pico auto light/dark via `prefers-color-scheme`.
- Runtime image: Liberica `jre-26-musl`.

## Architecture: Build Pipeline

CSS is compiled with **Bun** (a single fast JS runtime / package manager), used
both in local dev and as a dedicated Docker build stage. tailwindcss + daisyUI
are normal npm dependencies pinned by `bun.lock`. **Nothing JS ships at
runtime** — `node_modules` exists only in the build stage; the only artifact that
leaves the build is the compiled `calit.css`.

### Inputs (committed)

- `package.json` — dev dependencies `tailwindcss`, `@tailwindcss/cli`, `daisyui`;
  scripts:
  - `css:build` → `tailwindcss -i src/main/css/input.css -o
    src/main/resources/META-INF/resources/calit.css --minify`
  - `css:watch` → same with `--watch`
- `bun.lock` — lockfile pinning exact versions (reproducible builds).
- `src/main/css/input.css` — Tailwind + daisyUI entry:

  ```css
  @import "tailwindcss";
  @source "../resources/templates/**/*.html";   /* scan Qute templates for class names */
  @source "../resources/META-INF/resources/**/*.js";

  @plugin "daisyui";

  @plugin "daisyui/theme" { /* calit-light block (see Theming) */ }
  @plugin "daisyui/theme" { /* calit-dark block (see Theming) */ }

  @layer components {
    /* custom widgets daisyUI can't express: calendar grid, slot pills,
       location tiles, booking two-pane grid, admin shell */
  }
  ```

  daisyUI is referenced as a node dependency (`@plugin "daisyui"`), not a vendored
  `.mjs` bundle.

### Compile step

- Output path (single, for both dev and Docker):
  `src/main/resources/META-INF/resources/calit.css` — the **same** `/calit.css`
  URL served today, so the base templates only change by dropping the Pico `<link>`.
- This file is a **generated artifact**: gitignored, not committed (today's
  hand-written `calit.css` at this path is deleted from VCS; its irreducible custom
  rules move into `input.css`'s `@layer components`).
- **Dev:** `bun install` once; `bun run css:watch` alongside `./mvnw quarkus:dev`.
- **Docker:** a `oven/bun` stage runs `bun install` + `bun run css:build`, then the
  Java build stage copies the compiled `calit.css` into resources before
  `mvnw package`. Maven does **not** build CSS.
- Purge safety: every class name is a literal string in the templates (even inside
  Qute `{#if}` branches), so Tailwind's source scan finds them all. No dynamic
  class-name construction exists. The Bun stage must therefore have the templates
  present so `@source` can scan them.

### Removed

- `pom.xml`: drop `org.webjars.npm:picocss__pico`. Drop
  `quarkus-web-dependency-locator` if no other WebJar remains (verify first). No
  Maven CSS-build plugin is added — CSS is built by Bun, not Maven.
- `base.html` / `adminBase.html`: drop the Pico `<link>`.
- The hand-written `calit.css` becomes a **generated** artifact; its irreducible
  custom rules move into the `@layer components` block of `input.css`.

## Theming

Two custom themes defined in the `@plugin "daisyui/theme"` blocks (all required
daisyUI variables present: `--color-*`, `--radius-*`, `--size-*`, `--border`,
`--depth`, `--noise`):

- **calit-light** — `default: true`, `color-scheme: light`. Indigo `--color-primary`
  (oklch equivalent of today's `#5d5fef`). `base-*` neutral light surfaces.
- **calit-dark** — `prefersdark: true`, `color-scheme: dark`. Same indigo primary
  tuned for dark surfaces.

Radii tuned slightly rounder/friendlier than corporate: `--radius-field: 0.5rem`,
`--radius-box: 1rem`, `--radius-selector: 1rem`.

Color discipline (daisyUI rule): `base-*` for the majority of surfaces, `primary`
used sparingly for the single most important element per view; destructive uses
`error`, success uses `success`.

**Light/dark behavior:** auto via OS (`prefersdark`) plus a manual override using
daisyUI's `theme-controller` swap, persisted to `localStorage`, setting
`data-theme` on `<html>`. Placement: public `base.html` top-right corner; admin
`adminBase.html` sidebar footer near logout. A tiny inline script applies the
stored preference before paint to avoid a flash.

## Component Mapping (Pico → daisyUI 5)

| Current | daisyUI |
|---|---|
| `<article>` / `.calit-card` | `card` + `card-body` (+ `card-title`, `card-actions`) |
| `<button>` (default) | `btn` |
| primary action | `btn btn-primary` |
| secondary action | `btn btn-outline` / `btn btn-ghost` |
| **destructive** (delete/cancel/decline) | `btn btn-error` |
| `role="button"` link | `btn` |
| `<label><input></label>` | `fieldset` + `label` + `input` |
| `<select>` | `select` |
| `<textarea>` | `textarea` |
| checkbox | `checkbox` |
| `<fieldset><legend>` | `fieldset` + legend label |
| `.badge` | `badge` (+ `badge-primary` / `badge-ghost`) |
| `.editor <details>` accordion | `collapse collapse-arrow` |
| admin sidebar nav | `menu` (+ `menu-active` for active item) |
| `.err` inline error | `alert alert-error` |
| dashboard plain list | `stat` tiles + `card`s |
| canvas background | `bg-base-200` |

Component docs in `.claude/skills/daisyui/components/` are the authoritative
reference; read the relevant ones at implementation time and use default variants
unless specified.

## Per-Page Redesign (review fixes baked in)

**Bases**
- `base.html` — `bg-base-200` canvas, centered card column, theme toggle top-right.
- `adminBase.html` — sidebar as `menu`, pending `badge`, theme toggle in footer.

**Public**
- `landing.html` — fix card-system inconsistency: meeting-type grid as `card`s
  showing duration + location + description + hover; primary CTA per card.
- `book.html` — keep two-pane (info | picker); daisy `input`/`select`/`textarea`;
  slot pills; `btn btn-primary btn-block` confirm; error as `alert alert-error`;
  Turnstile + honeypot preserved.
- `confirmation.html` — `card` + detail panel; optional "add to calendar".
- `manage.html` — reschedule form in card; Cancel → `btn btn-error`.
- `cancelled.html` / `notReady.html` — centered `card`.

**Admin**
- `meetingTypes.html` — list as `card`s; Edit/Toggle row; Delete → `btn btn-error
  btn-sm`; create form as `collapse` accordion; remove inline `style` attributes.
- `meetingTypeDetail.html` — unify with create page: same `collapse` accordion
  sections (Basics / Booking fields / Working hours / Date overrides); Delete →
  `btn-error`.
- `pending.html` — `card`s; Approve `btn-primary`, Decline `btn-error`; fix raw
  UTC display (see Backend Touchpoints).
- `dashboard.html` — `stat` tiles (upcoming count, pending count) + upcoming
  `card`s; fix raw UTC display.
- `availability.html` / `dateOverrides.html` / `bookingFields.html` /
  `settings.html` — consistent daisy forms (`fieldset` + inputs), rule/field
  lists as `card`s.
- `google.html` — real `btn`s for connect / list calendars; connection-state
  `alert` (state flag optional — see Backend Touchpoints).

**Login**
- `login.html` — centered `card`, daisy `input`s, remember-me `checkbox`; keep the
  existing remember-me submit script and `/j_security_check` action.

## Backend Touchpoints (minimal, flagged)

- **Admin time formatting** (fixes review finding): `dashboard.html` and
  `pending.html` print raw `{b.startUtc} UTC`. Change to render
  `<time data-utc="{iso}">` and include the existing `tzScript` so the owner sees
  local time, consistent with public pages. Requires `AdminResource` to pass an
  ISO-8601 UTC string (and the tzScript fragment) to those two templates.
- **Google connection state** (optional): `google.html` "connected/not connected"
  display may need a boolean from the backend. If not trivially available, ship the
  buttons + generic copy and defer the live state. Mark optional.

No other backend changes. Template parameter contracts otherwise unchanged.

## Dev Workflow

- Prerequisite: install Bun once (`curl -fsSL https://bun.sh/install | bash`), then
  `bun install` in the repo.
- **Dev:** `./mvnw quarkus:dev` in one terminal; `bun run css:watch` in another.
  Quarkus live-reload serves the regenerated CSS. Documented in README.
- **One-shot:** `bun run css:build` produces the minified `calit.css`.

## Docker

- Multi-stage:
  1. **css stage** `FROM oven/bun:1` — copy `package.json`, `bun.lock`,
     `src/main/css/`, and `src/main/resources/templates/` (needed for `@source`
     scanning), run `bun install --frozen-lockfile` + `bun run css:build`, producing
     `calit.css`. Working dir mirrors the `src/main/...` layout so `input.css`'s
     relative `@source` paths resolve.
  2. **build stage** = existing Liberica `jdk-26-musl`: after `COPY src/ src/`,
     `COPY --from=css` the compiled `calit.css` into
     `src/main/resources/META-INF/resources/`, then `mvnw package` bakes it into the
     fast-jar.
  3. **runtime stage** = existing Liberica `jre-26-musl`, unchanged.
- No JS toolchain in the build or runtime Java stages; nothing JS ships.
- `.dockerignore` and `.gitignore` exclude `node_modules/` and the generated
  `src/main/resources/META-INF/resources/calit.css`.

## Testing / Rollout

- **Verification:** manual visual walk of all 16 pages in `quarkus dev`, in light,
  dark, and after toggling. Checklist:
  - public: landing, book (with/without slots, approval-required, Turnstile on),
    confirmation (booked + pending), manage, cancelled, notReady
  - admin: dashboard, pending (empty + populated), meetingTypes, meetingTypeDetail,
    availability, dateOverrides, bookingFields, settings, google
  - login (error + success), theme toggle persists across reload
- **Backend tests:** existing JUnit suite must stay green. Adjust only tests that
  assert rendered output for dashboard/pending if any exist (ISO field addition).
- **Build gate:** fail the build if `tailwindcss` produces a missing/empty
  `calit.css`.
- **Commit order** (single branch; Pico removed in commit 1, so no two-framework
  coexistence):
  1. Build pipeline + theme + `base.html` / `adminBase.html`; remove Pico.
  2. Public pages.
  3. Admin pages.
  4. Backend UTC touchups + dashboard/pending.
  Each commit independently runnable.
- **Risk:** Tailwind purging a class it didn't see — mitigated by literal class
  strings; sanity-check `calit.css` after first build.

## Open Questions

- None blocking. Google connection-state is the only explicitly-optional item.
