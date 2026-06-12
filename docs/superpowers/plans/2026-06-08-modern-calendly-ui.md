# Modern Calendly-like UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle every public and admin page of calit into a clean, modern, Calendly-like UI, with a two-pane calendar+time slot picker as the signature element.

**Architecture:** Replace the single inline `Layout.CSS` string with the **Pico CSS v2** framework (served from a local WebJar via `quarkus-web-dependency-locator` — no internet at runtime) plus one custom `calit.css` layer. Introduce two shared Qute include templates (`base.html` for the `<head>`/container, `adminNav.html` for the admin nav) so every page stops duplicating boilerplate. The booking slot picker becomes a month-calendar + time-column two-pane layout, progressively enhanced by a small vanilla-JS script over server-rendered, JS-free fallback markup.

**Tech Stack:** Quarkus 3.36, Qute templates (`@CheckedTemplate`), Pico CSS v2 (`pico.jade.min.css` — teal/emerald accent, auto light/dark) via `org.webjars.npm:picocss__pico:2.1.1`, vanilla JS, RestAssured tests.

---

## File Structure

**New files:**
- `src/main/resources/META-INF/resources/calit.css` — custom CSS layer on top of Pico: page container width, the two-pane booking grid, calendar widget, slot pills, badges, timezone bar.
- `src/main/resources/templates/base.html` — shared Qute layout: `<!DOCTYPE>`, `<head>` (Pico + calit.css links), `<main class="container">` wrapper with a main `{#insert}` and an optional `{#insert head}` for per-page `<head>` extras.
- `src/main/resources/templates/adminNav.html` — shared admin top nav (Pico `<nav>`), with the pending-count badge.
- `src/test/java/com/calit/web/StaticAssetsTest.java` — asserts the Pico WebJar and `calit.css` are served.

**Modified files:**
- `pom.xml` — add `quarkus-web-dependency-locator` + the Pico WebJar.
- `src/main/java/com/calit/web/Layout.java` — drop `CSS`; keep `TZ_BAR`/`TZ_SCRIPT`; add `CALENDAR_SCRIPT`.
- `src/main/java/com/calit/web/PublicResource.java` — replace `Map<String,List<SlotView>> slotsByDate` with an ordered `List<DaySlots>`; drop the `css` template arg; pass `CALENDAR_SCRIPT` to `book`/`manage`.
- `src/main/java/com/calit/web/AdminResource.java` — drop the `css` arg from all 8 template methods; pass `pendingCount` to every admin page (for the nav badge).
- All 5 public templates (`landing`, `book`, `confirmation`, `manage`, `cancelled`) and all 8 admin templates (`dashboard`, `meetingTypes`, `availability`, `settings`, `google`, `bookingFields`, `dateOverrides`, `pending`) — converted to use `base.html` and (admin) `adminNav.html`, restyled with Pico semantic markup.

**Design invariants the existing tests pin (must survive the redesign):**
- Booking/reschedule slots must remain `<input type="radio" name="startUtc" value="{utc-instant}">` carrying a `<time data-utc=...>` child.
- The honeypot `name="website"` input and (when enabled) `class="cf-turnstile"` widget + the `challenges.cloudflare.com/turnstile/v0/api.js` loader stay on the book page.
- The `CALIT_TZ_REFORMAT` script and the `Times shown in:` bar stay on invitee pages.
- Button text stays exactly `Confirm booking` / `Request` / `Reschedule` / `Cancel`; confirmation headings `You're booked` / `Request sent — pending owner approval`.
- All admin form field `name="..."` attributes and the `secret`/`approval` badges keep their current text.

---

## Task 1: Add Pico CSS via WebJar + web-dependency-locator

**Files:**
- Modify: `pom.xml:48` (insert into the `<dependencies>` block, near the other `io.quarkus` entries)
- Create: `src/main/resources/META-INF/resources/calit.css`
- Test: `src/test/java/com/calit/web/StaticAssetsTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/web/StaticAssetsTest.java`:

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class StaticAssetsTest {

    @Test
    void picoWebjarIsServedVersionAgnostically() {
        // web-dependency-locator lets us drop the version from the WebJar path.
        given().when().get("/webjars/picocss__pico/css/pico.jade.min.css")
                .then().statusCode(200)
                .contentType(containsString("css"));
    }

    @Test
    void customStylesheetIsServed() {
        given().when().get("/calit.css")
                .then().statusCode(200)
                .body(containsString("--calit"));  // marker custom property
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=StaticAssetsTest`
Expected: FAIL — both endpoints return 404 (WebJar dep + `calit.css` not present yet).

- [ ] **Step 3: Add the dependencies to `pom.xml`**

Insert these two dependencies immediately after the `quarkus-elytron-security-properties-file` line (`pom.xml:45`), inside `<dependencies>`:

```xml
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-web-dependency-locator</artifactId></dependency>
    <!-- Pico CSS v2 (jade = teal/emerald accent, auto light/dark). Served from the jar at
         /webjars/picocss__pico/css/pico.jade.min.css — version omitted by web-dependency-locator. -->
    <dependency><groupId>org.webjars.npm</groupId><artifactId>picocss__pico</artifactId><version>2.1.1</version></dependency>
```

- [ ] **Step 4: Create the custom stylesheet with a marker property**

Create `src/main/resources/META-INF/resources/calit.css` with just enough to make the test pass (filled out fully in Task 2):

```css
/* calit custom layer — sits on top of Pico v2. */
:root {
  --calit-container: 48rem;
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=StaticAssetsTest`
Expected: PASS — both assets serve 200.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/META-INF/resources/calit.css src/test/java/com/calit/web/StaticAssetsTest.java
git commit -m "build: serve Pico CSS v2 via WebJar + web-dependency-locator"
```

---

## Task 2: Flesh out the custom CSS layer

**Files:**
- Modify: `src/main/resources/META-INF/resources/calit.css`

This task is pure CSS (no behaviour to unit-test). The `StaticAssetsTest` from Task 1 keeps it served; visual verification happens in Task 13.

- [ ] **Step 1: Replace `calit.css` with the full custom layer**

Overwrite `src/main/resources/META-INF/resources/calit.css`:

```css
/* calit custom layer — sits on top of Pico v2 (pico.jade.min.css). */
:root {
  --calit-container: 48rem;        /* narrower than Pico default — Calendly-like column */
  --pico-form-element-spacing-vertical: .6rem;
}

/* Tighter reading column for the public/invitee pages. */
main.container { max-width: var(--calit-container); }

/* --- Status badges (secret / inactive / approval / pending count) --- */
.badge {
  display: inline-block;
  font-size: .7rem;
  font-weight: 600;
  line-height: 1;
  padding: .25rem .5rem;
  border-radius: 1rem;
  background: var(--pico-primary-background);
  color: var(--pico-primary-inverse);
  vertical-align: middle;
}

/* --- Landing: meeting-type cards as a responsive grid --- */
.type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
  gap: 1rem;
}
.type-grid article { margin: 0; }
.type-grid article a[role="button"] { width: 100%; }

/* --- Timezone bar (invitee pages) --- */
.tz-bar {
  font-size: .85rem;
  color: var(--pico-muted-color);
  margin: .5rem 0 1rem;
}
.tz-bar select { display: inline; width: auto; margin: 0 0 0 .25rem; }

/* --- Two-pane booking grid: calendar on the left, times on the right --- */
.booking-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 1.5rem;
}
@media (min-width: 48rem) {
  .booking-grid { grid-template-columns: minmax(18rem, 1fr) minmax(12rem, 16rem); }
}

/* Calendar widget (built by CALENDAR_SCRIPT). */
.calendar { border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); padding: 1rem; }
.calendar__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: .75rem; }
.calendar__title { font-weight: 600; }
.calendar__nav { background: none; border: none; color: var(--pico-primary); font-size: 1.2rem; padding: .2rem .6rem; width: auto; margin: 0; }
.calendar__nav[disabled] { color: var(--pico-muted-color); cursor: not-allowed; }
.calendar__grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: .25rem; text-align: center; }
.calendar__dow { font-size: .7rem; color: var(--pico-muted-color); padding: .25rem 0; }
.calendar__day { aspect-ratio: 1; border: none; border-radius: 50%; background: none; color: var(--pico-color); padding: 0; margin: 0; width: 100%; font-size: .9rem; cursor: pointer; }
.calendar__day[disabled] { color: var(--pico-muted-color); opacity: .4; cursor: default; }
.calendar__day--available { font-weight: 600; background: var(--pico-primary-background); color: var(--pico-primary-inverse); }
.calendar__day--selected { outline: 2px solid var(--pico-primary); outline-offset: 1px; }
.calendar__day--empty { visibility: hidden; }

/* Time column: a day's slots as a vertical list of pill buttons. */
.time-column h3 { font-size: .95rem; margin: 0 0 .75rem; }
.day-slots { margin: 0; }
.day-slots[hidden] { display: none; }
.slot { display: block; margin: 0 0 .5rem; }
.slot input[type="radio"] { position: absolute; opacity: 0; }
.slot time {
  display: block;
  text-align: center;
  padding: .55rem .75rem;
  border: 1px solid var(--pico-primary);
  border-radius: var(--pico-border-radius);
  color: var(--pico-primary);
  cursor: pointer;
}
.slot input[type="radio"]:checked + time { background: var(--pico-primary); color: var(--pico-primary-inverse); }
.slot input[type="radio"]:focus-visible + time { outline: 2px solid var(--pico-primary); outline-offset: 2px; }

/* Keep the honeypot invisible regardless of framework resets. */
input[name="website"] { display: none !important; }
```

- [ ] **Step 2: Verify it still serves and tests pass**

Run: `mvn test -Dtest=StaticAssetsTest`
Expected: PASS (the `--calit` marker is still present).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/META-INF/resources/calit.css
git commit -m "feat: custom CSS layer (calendar grid, slot pills, badges)"
```

---

## Task 3: Shared `base.html` layout + convert the landing page

**Files:**
- Create: `src/main/resources/templates/base.html`
- Modify: `src/main/resources/templates/PublicResource/landing.html`
- Modify: `src/main/java/com/calit/web/PublicResource.java:43` (the `landing` native method) and `PublicResource.java:91` (the `landing()` handler call)
- Test: `src/test/java/com/calit/web/PublicLandingTest.java` (existing — must stay green)

- [ ] **Step 1: Create the shared base layout**

Create `src/main/resources/templates/base.html`:

```html
{@java.lang.String title}
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <link rel="stylesheet" href="/webjars/picocss__pico/css/pico.jade.min.css">
  <link rel="stylesheet" href="/calit.css">
  {#insert head}{/insert}
</head>
<body>
  <main class="container">
    {#insert}{/insert}
  </main>
</body>
</html>
```

- [ ] **Step 2: Rewrite the landing template to use the base layout**

Overwrite `src/main/resources/templates/PublicResource/landing.html` (note: the `css` parameter is gone):

```html
{@java.util.List<com.calit.domain.MeetingType> types}
{#include base title="Book a meeting"}
  <hgroup>
    <h1>Book a meeting</h1>
    <p>Pick a meeting type to see available times.</p>
  </hgroup>
  {#if types.isEmpty()}
    <p>No meeting types are currently available.</p>
  {#else}
    <div class="type-grid">
      {#for t in types}
        <article>
          <header><strong>{t.name}</strong></header>
          <p>{t.durationMinutes} minutes</p>
          <a role="button" href="/book/{t.slug}">Choose a time &rarr;</a>
        </article>
      {/for}
    </div>
  {/if}
{/include}
```

- [ ] **Step 3: Drop the `css` arg from the `landing` template method + handler**

In `src/main/java/com/calit/web/PublicResource.java`, change the native declaration (line 43) from:

```java
        public static native TemplateInstance landing(List<MeetingType> types, String css);
```

to:

```java
        public static native TemplateInstance landing(List<MeetingType> types);
```

And change the handler (line 91) from:

```java
        return Templates.landing(MeetingType.listPublic(), Layout.CSS);
```

to:

```java
        return Templates.landing(MeetingType.listPublic());
```

- [ ] **Step 4: Run the landing test to verify it passes**

Run: `mvn test -Dtest=PublicLandingTest`
Expected: PASS — body still contains `Public Intro Call` and not `Secret VIP Session`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/base.html src/main/resources/templates/PublicResource/landing.html src/main/java/com/calit/web/PublicResource.java
git commit -m "feat: shared base layout + redesigned landing page"
```

---

## Task 4: Convert the `cancelled` and `confirmation` pages

**Files:**
- Modify: `src/main/resources/templates/PublicResource/cancelled.html`
- Modify: `src/main/resources/templates/PublicResource/confirmation.html`
- Modify: `src/main/java/com/calit/web/PublicResource.java` (the `cancelled` + `confirmation` native methods at lines 56–66, and the call sites at lines 175 + 217)
- Test: `src/test/java/com/calit/web/BookingPostTest.java`, `ManageBookingTest.java` (existing — must stay green)

- [ ] **Step 1: Rewrite `cancelled.html`**

Overwrite `src/main/resources/templates/PublicResource/cancelled.html` (drop the `css` param):

```html
{#include base title="Booking cancelled"}
  <h1>Your booking is cancelled</h1>
  <p>The meeting has been cancelled and the calendar event removed.</p>
  <p><a role="button" href="/">Book a different time</a></p>
{/include}
```

- [ ] **Step 2: Rewrite `confirmation.html`**

Overwrite `src/main/resources/templates/PublicResource/confirmation.html` (drop the `css` param; keep `tzBar`/`tzScript`, the exact headings, and the `data-utc` time):

```html
{@com.calit.booking.Booking booking}
{@com.calit.domain.MeetingType type}
{@java.lang.Boolean pending}
{@java.lang.String location}
{@java.lang.String whenLabel}
{@java.lang.String startUtcIso}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{#include base title="{#if pending}Request sent{#else}Booking confirmed{/if}"}
  {#if pending}
    <hgroup>
      <h1>Request sent — pending owner approval</h1>
      <p>Thanks, {booking.inviteeName}. Your requested time is held while {type.name}'s owner reviews it.
         You'll get an email once it's approved or declined.</p>
    </hgroup>
  {#else}
    <h1>You're booked, {booking.inviteeName}!</h1>
  {/if}
  {tzBar.raw}
  <article>
    <p><strong>When:</strong> <time data-utc="{startUtcIso}">{whenLabel}</time></p>
    {#if !pending}
      {#if type.locationType.name == 'GOOGLE_MEET'}
        <p><strong>Google Meet:</strong> <a href="{location}">{location}</a></p>
      {#else}
        <p><strong>Location:</strong> {location}</p>
      {/if}
    {/if}
  </article>
  <p>{#if pending}A request confirmation{#else}A confirmation{/if} email is on its way to {booking.inviteeEmail}.</p>
  <p><a href="/booking/{booking.manageToken}/manage">Need to change or cancel this booking?</a></p>
  {tzScript.raw}
{/include}
```

- [ ] **Step 3: Update the `confirmation` + `cancelled` native methods**

In `src/main/java/com/calit/web/PublicResource.java`, change the `confirmation` declaration (lines 56–59) from:

```java
        public static native TemplateInstance confirmation(
                com.calit.booking.Booking booking, com.calit.domain.MeetingType type,
                boolean pending, String location, String whenLabel, String startUtcIso,
                String css, String tzBar, String tzScript);
```

to (remove `String css`):

```java
        public static native TemplateInstance confirmation(
                com.calit.booking.Booking booking, com.calit.domain.MeetingType type,
                boolean pending, String location, String whenLabel, String startUtcIso,
                String tzBar, String tzScript);
```

Change the `cancelled` declaration (line 66) from:

```java
        public static native TemplateInstance cancelled(String css);
```

to:

```java
        public static native TemplateInstance cancelled();
```

- [ ] **Step 4: Update the two call sites**

In `confirmationPage(...)` (lines 175–176) change:

```java
        return Templates.confirmation(booking, type, pending, location, when, startUtcIso,
                                      Layout.CSS, Layout.TZ_BAR, Layout.TZ_SCRIPT);
```

to:

```java
        return Templates.confirmation(booking, type, pending, location, when, startUtcIso,
                                      Layout.TZ_BAR, Layout.TZ_SCRIPT);
```

In `cancelBooking(...)` (line 217) change:

```java
        return Templates.cancelled(Layout.CSS);
```

to:

```java
        return Templates.cancelled();
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `mvn test -Dtest=BookingPostTest,ManageBookingTest`
Expected: PASS — `You're booked`, `Request sent — pending owner approval`, `Sam Invitee`, `/booking/`, `cancelled`, `Times shown in:` all still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/PublicResource/cancelled.html src/main/resources/templates/PublicResource/confirmation.html src/main/java/com/calit/web/PublicResource.java
git commit -m "feat: redesign confirmation + cancelled pages"
```

---

## Task 5: Add the calendar script to `Layout` and reshape slot data

This task introduces the `DaySlots` model + the `CALENDAR_SCRIPT`, but does NOT yet wire them into the templates (Tasks 6–7). It keeps the project compiling and green.

**Files:**
- Modify: `src/main/java/com/calit/web/Layout.java`
- Modify: `src/main/java/com/calit/web/PublicResource.java`

- [ ] **Step 1: Add `CALENDAR_SCRIPT` to `Layout.java`**

In `src/main/java/com/calit/web/Layout.java`, add this constant after `TZ_BAR` (before the closing brace). It builds a month calendar from the rendered `.day-slots[data-date]` sections and toggles the visible day. The marker comment `CALIT_CALENDAR` lets a `@QuarkusTest` assert presence without executing JS:

```java
    /**
     * Vanilla-JS progressive enhancement for the booking/reschedule slot picker. The server
     * renders every available day as a <section class="day-slots" data-date="YYYY-MM-DD"> with
     * radio slots inside; with JS OFF they simply stack (graceful fallback). With JS ON this
     * builds a month calendar, hides all day sections, and reveals one day at a time on click.
     * It never alters any radio value — only which day section is visible.
     */
    public static final String CALENDAR_SCRIPT = """
            <script>
            /* CALIT_CALENDAR — two-pane calendar/time picker progressive enhancement */
            (function () {
              var cal = document.getElementById('calendar');
              var sections = Array.prototype.slice.call(document.querySelectorAll('.day-slots'));
              if (!cal || !sections.length) { return; }

              var byDate = {};
              sections.forEach(function (s) { byDate[s.dataset.date] = s; });
              var dates = Object.keys(byDate).sort();
              var DOW = ['Mo','Tu','We','Th','Fr','Sa','Su'];
              var MONTHS = ['January','February','March','April','May','June','July','August',
                            'September','October','November','December'];

              function parse(iso) { var p = iso.split('-'); return new Date(+p[0], +p[1]-1, +p[2]); }
              function iso(d) {
                var m = ('0'+(d.getMonth()+1)).slice(-2), day = ('0'+d.getDate()).slice(-2);
                return d.getFullYear()+'-'+m+'-'+day;
              }
              var first = parse(dates[0]);
              var last = parse(dates[dates.length-1]);
              var view = new Date(first.getFullYear(), first.getMonth(), 1);
              var selected = dates[0];

              function show(date) {
                selected = date;
                sections.forEach(function (s) { s.hidden = (s.dataset.date !== date); });
                render();
              }

              function render() {
                var y = view.getFullYear(), m = view.getMonth();
                var prevDisabled = (y < first.getFullYear()) || (y === first.getFullYear() && m <= first.getMonth());
                var nextDisabled = (y > last.getFullYear()) || (y === last.getFullYear() && m >= last.getMonth());
                var html = '<div class="calendar__head">'
                  + '<button type="button" class="calendar__nav" data-step="-1"' + (prevDisabled?' disabled':'') + '>&lsaquo;</button>'
                  + '<span class="calendar__title">' + MONTHS[m] + ' ' + y + '</span>'
                  + '<button type="button" class="calendar__nav" data-step="1"' + (nextDisabled?' disabled':'') + '>&rsaquo;</button>'
                  + '</div><div class="calendar__grid">';
                DOW.forEach(function (d) { html += '<span class="calendar__dow">' + d + '</span>'; });
                var firstDow = (new Date(y, m, 1).getDay() + 6) % 7; // Mon=0
                for (var i = 0; i < firstDow; i++) { html += '<span class="calendar__day calendar__day--empty"></span>'; }
                var days = new Date(y, m+1, 0).getDate();
                for (var d = 1; d <= days; d++) {
                  var key = iso(new Date(y, m, d));
                  var avail = !!byDate[key];
                  var cls = 'calendar__day' + (avail ? ' calendar__day--available' : '')
                          + (key === selected ? ' calendar__day--selected' : '');
                  html += '<button type="button" class="' + cls + '" data-date="' + key + '"'
                        + (avail ? '' : ' disabled') + '>' + d + '</button>';
                }
                html += '</div>';
                cal.innerHTML = html;
              }

              cal.addEventListener('click', function (e) {
                var nav = e.target.closest('.calendar__nav');
                if (nav && !nav.disabled) { view.setMonth(view.getMonth() + (+nav.dataset.step)); render(); return; }
                var day = e.target.closest('.calendar__day--available');
                if (day) { view = new Date(parse(day.dataset.date).getFullYear(), parse(day.dataset.date).getMonth(), 1); show(day.dataset.date); }
              });

              show(selected);
            })();
            </script>
            """;
```

- [ ] **Step 2: Add the `DaySlots` record and `daySlots(...)` builder to `PublicResource`**

In `src/main/java/com/calit/web/PublicResource.java`, add a record next to `SlotView` (after line 85):

```java
    /** One day's worth of selectable slots: ISO date (for the JS calendar), a human label, and the slots. */
    public record DaySlots(String isoDate, String label, java.util.List<SlotView> slots) {}
```

Then add a new builder method next to `slotsByDate(...)` (after line 233). It reuses the existing grouping but emits an ordered list carrying the ISO date:

```java
    /** Available slots as an ordered per-day list (ISO date + label), chronological. */
    private List<DaySlots> daySlots(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate from = LocalDate.now(zone);
        LocalDate to = from.plusDays(BOOK_WINDOW_DAYS);
        Map<String, DaySlots> byIso = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String isoDate = slot.start().toLocalDate().toString();
            DaySlots day = byIso.computeIfAbsent(isoDate,
                    k -> new DaySlots(k, slot.start().format(DATE_FMT), new java.util.ArrayList<>()));
            day.slots().add(new SlotView(slot.start().format(TIME_FMT),
                                         slot.start().toInstant().toString()));
        }
        return new java.util.ArrayList<>(byIso.values());
    }
```

> Note: the old `slotsByDate(...)` method stays for now — it is removed in Task 6 once `book`/`manage` switch to `daySlots(...)`.

- [ ] **Step 3: Verify the project still compiles and all tests pass**

Run: `mvn test`
Expected: PASS — no behaviour changed yet; the new method/constant are unused.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/web/Layout.java src/main/java/com/calit/web/PublicResource.java
git commit -m "feat: add calendar enhancement script + per-day slot model"
```

---

## Task 6: Two-pane calendar picker on the booking page

**Files:**
- Modify: `src/main/resources/templates/PublicResource/book.html`
- Modify: `src/main/java/com/calit/web/PublicResource.java` (the `book` native method, the `book()`/`submitBooking()` call sites, and removal of the now-unused `slotsByDate(...)`)
- Test: `src/test/java/com/calit/web/BookPageTest.java`, `BookPageTurnstileEnabledTest.java`, `BookingPostTest.java` (existing) + a new calendar assertion

- [ ] **Step 1: Add a calendar-markup assertion to `BookPageTest`**

In `src/test/java/com/calit/web/BookPageTest.java`, add this test method to the existing class (keep the existing tests intact):

```java
    @Test
    void bookPageRendersCalendarPickerAndDaySections() {
        io.restassured.RestAssured.given()
                .when().get("/book/" + BOOK_PAGE_SLUG)
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("CALIT_CALENDAR"))   // enhancement script present
                .body(org.hamcrest.Matchers.containsString("id=\"calendar\""))  // calendar mount point
                .body(org.hamcrest.Matchers.containsString("class=\"day-slots\"")) // per-day section
                .body(org.hamcrest.Matchers.containsString("name=\"startUtc\"")); // radios still posted
    }
```

> Use whatever existing constant/slug `BookPageTest` already books against for its other tests. If it inlines the slug, reuse that literal instead of `BOOK_PAGE_SLUG`.

- [ ] **Step 2: Run the new test to verify it fails**

Run: `mvn test -Dtest=BookPageTest#bookPageRendersCalendarPickerAndDaySections`
Expected: FAIL — `CALIT_CALENDAR` / `id="calendar"` / `class="day-slots"` not present yet.

- [ ] **Step 3: Rewrite `book.html` with the two-pane layout**

Overwrite `src/main/resources/templates/PublicResource/book.html`. The `css` param is gone; `slotsByDate` becomes `days` (a `List<DaySlots>`); a new `calScript` param carries `Layout.CALENDAR_SCRIPT`. The honeypot, Turnstile widget+loader, `name="startUtc"` radios, and `data-utc` times are preserved exactly:

```html
{@com.calit.domain.MeetingType type}
{@java.util.List<com.calit.web.PublicResource$DaySlots> days}
{@java.util.List<com.calit.domain.BookingField> fields}
{@java.lang.String error}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{@java.lang.String calScript}
{@java.lang.Boolean turnstileEnabled}
{@java.lang.String turnstileSiteKey}
{#include base title="Book — {type.name}"}
  {#head}
    {#if turnstileEnabled}
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
    {/if}
  {/head}

  <p><a href="/">&larr; All meeting types</a></p>
  <hgroup>
    <h1>{type.name}</h1>
    <p>{type.durationMinutes} minutes</p>
  </hgroup>

  {#if type.locationType.name == 'GOOGLE_MEET'}
    <p><strong>Location:</strong> Google Meet — link sent after booking</p>
  {#else}
    <p><strong>Location:</strong> {type.locationDetail}</p>
  {/if}

  {#if type.requiresApproval}
    <p>This meeting requires owner approval — you'll send a request and be notified once it's approved.</p>
  {/if}

  {#if error}<p style="color:var(--pico-del-color)">{error}</p>{/if}

  {#if days.isEmpty()}
    <p>No available times in the next two weeks.</p>
  {#else}
    {tzBar.raw}
    <form method="post" action="/book/{type.slug}">
      <div class="booking-grid">
        <div id="calendar" class="calendar"></div>
        <div class="time-column">
          {#for day in days}
            <section class="day-slots" data-date="{day.isoDate}">
              <h3>{day.label}</h3>
              {#for slot in day.slots}
                <label class="slot">
                  <input type="radio" name="startUtc" value="{slot.startUtc}" required>
                  <time data-utc="{slot.startUtc}" data-time-only="1">{slot.label}</time>
                </label>
              {/for}
            </section>
          {/for}
        </div>
      </div>

      <label>Your name <input type="text" name="inviteeName" required></label>
      <label>Your email <input type="email" name="inviteeEmail" required></label>

      {#for f in fields}
        <label>{f.label}
          {#if f.type.name == 'LONG_TEXT'}
            <textarea name="answers.{f.fieldKey}" {#if f.required}required{/if}></textarea>
          {#else if f.type.name == 'EMAIL'}
            <input type="email" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
          {#else if f.type.name == 'PHONE'}
            <input type="tel" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
          {#else if f.type.name == 'NUMBER'}
            <input type="number" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
          {#else}
            <input type="text" name="answers.{f.fieldKey}" {#if f.required}required{/if}>
          {/if}
        </label>
      {/for}

      {! Honeypot (always present + hidden). A non-empty `website` → rejected server-side. !}
      <input type="text" name="website" tabindex="-1" autocomplete="off">

      {#if turnstileEnabled}
      <div class="cf-turnstile" data-sitekey="{turnstileSiteKey}"></div>
      {/if}

      {#if type.requiresApproval}
        <button type="submit">Request</button>
      {#else}
        <button type="submit">Confirm booking</button>
      {/if}
    </form>
  {/if}
  {tzScript.raw}
  {calScript.raw}
{/include}
```

- [ ] **Step 4: Update the `book` native method signature**

In `src/main/java/com/calit/web/PublicResource.java`, change the `book` declaration (lines 45–54) from:

```java
        public static native TemplateInstance book(
                MeetingType type,
                Map<String, java.util.List<PublicResource.SlotView>> slotsByDate,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String css,
                String tzBar,
                String tzScript,
                boolean turnstileEnabled,
                String turnstileSiteKey);
```

to:

```java
        public static native TemplateInstance book(
                MeetingType type,
                java.util.List<PublicResource.DaySlots> days,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey);
```

- [ ] **Step 5: Update both `book` call sites**

In `book()` (lines 108–110) change:

```java
        return Templates.book(type, byDate, fields, null, Layout.CSS,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT,
                              turnstileEnabled, turnstileSiteKey());
```

to:

```java
        return Templates.book(type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey());
```

In the same method change the `byDate` builder (line 102) from `Map<String, java.util.List<SlotView>> byDate = slotsByDate(type);` to:

```java
        List<DaySlots> byDate = daySlots(type);
```

In `submitBooking(...)`'s catch block (lines 154–157) change:

```java
            return Templates.book(type, slotsByDate(type), BookingField.formFor(type.id),
                                  be.getMessage(), Layout.CSS,
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey());
```

to:

```java
            return Templates.book(type, daySlots(type), BookingField.formFor(type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey());
```

- [ ] **Step 6: Run the booking tests to verify they pass**

Run: `mvn test -Dtest=BookPageTest,BookPageTurnstileEnabledTest,BookingPostTest`
Expected: PASS — calendar markup present; `Confirm booking`, `name="website"`, `class="cf-turnstile"`, `data-sitekey`, the Turnstile loader, and `name="startUtc"` all still present.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/PublicResource/book.html src/main/java/com/calit/web/PublicResource.java src/test/java/com/calit/web/BookPageTest.java
git commit -m "feat: two-pane calendar slot picker on the booking page"
```

---

## Task 7: Two-pane calendar picker on the reschedule (manage) page

**Files:**
- Modify: `src/main/resources/templates/PublicResource/manage.html`
- Modify: `src/main/java/com/calit/web/PublicResource.java` (the `manage` native method + `manage()` call site, and removal of the now-unused `slotsByDate(...)`)
- Test: `src/test/java/com/calit/web/ManageBookingTest.java` (existing — must stay green)

- [ ] **Step 1: Rewrite `manage.html` with the calendar picker**

Overwrite `src/main/resources/templates/PublicResource/manage.html` (drops `css`, swaps `slotsByDate`→`days`, adds `calScript`, keeps `Reschedule`/`Cancel` buttons + `data-utc` times):

```html
{@com.calit.booking.Booking booking}
{@java.lang.String currentLabel}
{@java.lang.String currentUtcIso}
{@java.util.List<com.calit.web.PublicResource$DaySlots> days}
{@java.lang.String tzBar}
{@java.lang.String tzScript}
{@java.lang.String calScript}
{#include base title="Manage booking"}
  <h1>Manage your booking</h1>
  {tzBar.raw}
  <article>
    <p><strong>Currently:</strong> <time data-utc="{currentUtcIso}">{currentLabel}</time></p>
    <p><strong>For:</strong> {booking.inviteeName} ({booking.inviteeEmail})</p>
  </article>

  <h2>Reschedule</h2>
  {#if days.isEmpty()}
    <p>No alternative times available.</p>
  {#else}
    <form method="post" action="/booking/{booking.manageToken}/reschedule">
      <div class="booking-grid">
        <div id="calendar" class="calendar"></div>
        <div class="time-column">
          {#for day in days}
            <section class="day-slots" data-date="{day.isoDate}">
              <h3>{day.label}</h3>
              {#for slot in day.slots}
                <label class="slot">
                  <input type="radio" name="startUtc" value="{slot.startUtc}" required>
                  <time data-utc="{slot.startUtc}" data-time-only="1">{slot.label}</time>
                </label>
              {/for}
            </section>
          {/for}
        </div>
      </div>
      <button type="submit">Reschedule to selected time</button>
    </form>
  {/if}

  <h2>Cancel</h2>
  <form method="post" action="/booking/{booking.manageToken}/cancel">
    <button type="submit" class="secondary">Cancel this booking</button>
  </form>
  {tzScript.raw}
  {calScript.raw}
{/include}
```

- [ ] **Step 2: Update the `manage` native method signature**

In `src/main/java/com/calit/web/PublicResource.java`, change the `manage` declaration (lines 61–64) from:

```java
        public static native TemplateInstance manage(
                com.calit.booking.Booking booking, String currentLabel, String currentUtcIso,
                Map<String, java.util.List<PublicResource.SlotView>> slotsByDate, String css,
                String tzBar, String tzScript);
```

to:

```java
        public static native TemplateInstance manage(
                com.calit.booking.Booking booking, String currentLabel, String currentUtcIso,
                java.util.List<PublicResource.DaySlots> days,
                String tzBar, String tzScript, String calScript);
```

- [ ] **Step 3: Update the `manage()` call site**

In `manage(...)` change the slots builder (line 189) `Map<String, java.util.List<SlotView>> byDate = slotsByDate(type);` to:

```java
        List<DaySlots> byDate = daySlots(type);
```

and the return (lines 193–194) from:

```java
        return Templates.manage(booking, current, currentUtcIso, byDate, Layout.CSS,
                                Layout.TZ_BAR, Layout.TZ_SCRIPT);
```

to:

```java
        return Templates.manage(booking, current, currentUtcIso, byDate,
                                Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT);
```

- [ ] **Step 4: Delete the now-unused `slotsByDate(...)` method**

Both callers now use `daySlots(...)`. Remove the entire `slotsByDate(...)` method (originally lines 220–233) and the now-unused `DATE_FMT`/`TIME_FMT` only if nothing else references them — `daySlots(...)` still uses both, so keep `DATE_FMT`/`TIME_FMT`. Delete only the `slotsByDate` method body.

- [ ] **Step 5: Verify the full suite passes**

Run: `mvn test`
Expected: PASS — `ManageBookingTest` still finds `Reschedule`/`Cancel`/`/manage`; nothing references `slotsByDate` or `Layout.CSS` from the public pages anymore.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/PublicResource/manage.html src/main/java/com/calit/web/PublicResource.java
git commit -m "feat: calendar reschedule picker; drop legacy slotsByDate"
```

---

## Task 8: Shared admin nav + convert the dashboard

**Files:**
- Create: `src/main/resources/templates/adminNav.html`
- Modify: `src/main/resources/templates/AdminResource/dashboard.html`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (the `dashboard` native method at line 40 + handler at lines 70–78)
- Test: `src/test/java/com/calit/web/AdminAuthTest.java` (existing — must stay green)

- [ ] **Step 1: Create the shared admin nav fragment**

Create `src/main/resources/templates/adminNav.html` (Pico renders `<nav><ul>…</ul></nav>` as a horizontal bar):

```html
{@java.lang.Long pendingCount}
<nav>
  <ul><li><strong>calit admin</strong></li></ul>
  <ul>
    <li><a href="/admin">Dashboard</a></li>
    <li><a href="/admin/pending">Pending{#if pendingCount && pendingCount > 0} <span class="badge">{pendingCount}</span>{/if}</a></li>
    <li><a href="/admin/meeting-types">Meeting types</a></li>
    <li><a href="/admin/availability">Availability</a></li>
    <li><a href="/admin/date-overrides">Date overrides</a></li>
    <li><a href="/admin/booking-fields">Booking fields</a></li>
    <li><a href="/admin/settings">Settings</a></li>
    <li><a href="/admin/google">Google</a></li>
  </ul>
</nav>
```

- [ ] **Step 2: Rewrite `dashboard.html`**

Overwrite `src/main/resources/templates/AdminResource/dashboard.html` (drops `css`; nav comes from the include; `pendingCount` now also drives the badge):

```html
{@java.util.List<com.calit.booking.Booking> upcoming}
{@java.lang.Long pendingCount}
{#include base title="Admin — Dashboard"}
  {#include adminNav pendingCount=pendingCount /}
  <h1>Dashboard</h1>
  <h2>Upcoming bookings</h2>
  {#if upcoming.isEmpty()}
    <p>No upcoming bookings.</p>
  {#else}
    {#for b in upcoming}
      <article>
        <p><strong>{b.inviteeName}</strong> ({b.inviteeEmail})</p>
        <p>{b.startUtc} UTC</p>
        <p><a href="{b.meetLink}">{b.meetLink}</a></p>
      </article>
    {/for}
  {/if}
{/include}
```

- [ ] **Step 3: Update the `dashboard` native method + handler**

In `src/main/java/com/calit/web/AdminResource.java`, change the declaration (line 40) from:

```java
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount, String css);
```

to:

```java
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount);
```

and the handler return (line 77) from:

```java
        return Templates.dashboard(upcoming, pendingCount, Layout.CSS);
```

to:

```java
        return Templates.dashboard(upcoming, pendingCount);
```

- [ ] **Step 4: Run the admin auth test to verify it passes**

Run: `mvn test -Dtest=AdminAuthTest`
Expected: PASS — authenticated `/admin` still contains `Dashboard`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/adminNav.html src/main/resources/templates/AdminResource/dashboard.html src/main/java/com/calit/web/AdminResource.java
git commit -m "feat: shared admin nav + redesigned dashboard"
```

---

## Task 9: Convert `pending` + `google` admin pages

**Files:**
- Modify: `src/main/resources/templates/AdminResource/pending.html`
- Modify: `src/main/resources/templates/AdminResource/google.html`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (the `pending` + `google` native methods + their handlers, including `approve`/`decline` which re-render `pending`)
- Test: `src/test/java/com/calit/web/AdminGoogleTest.java`; the pending approve/decline tests (existing — must stay green)

- [ ] **Step 1: Rewrite `pending.html`**

Overwrite `src/main/resources/templates/AdminResource/pending.html` (keeps `Approve`/`Decline` button text + the per-booking card):

```html
{@java.util.List<com.calit.booking.Booking> pending}
{#include base title="Admin — Pending approvals"}
  {#include adminNav pendingCount=pending.size() /}
  <h1>Pending approvals</h1>
  {#if pending.isEmpty()}
    <p>No requests are awaiting approval.</p>
  {#else}
    {#for b in pending}
      <article>
        <p><strong>{b.inviteeName}</strong> ({b.inviteeEmail})</p>
        <p>{b.startUtc} UTC</p>
        <form method="post" action="/admin/bookings/{b.id}/approve" style="display:inline">
          <button type="submit">Approve</button>
        </form>
        <form method="post" action="/admin/bookings/{b.id}/decline" style="display:inline">
          <button type="submit" class="secondary">Decline</button>
        </form>
      </article>
    {/for}
  {/if}
{/include}
```

- [ ] **Step 2: Rewrite `google.html`**

Overwrite `src/main/resources/templates/AdminResource/google.html` (keeps `Connect Google` + `/api/google/connect`):

```html
{@java.lang.Long pendingCount}
{#include base title="Admin — Google"}
  {#include adminNav pendingCount=pendingCount /}
  <h1>Google Calendar</h1>
  <p>Connect your Google account so calit can read your busy times and create Meet events.</p>
  <p><a role="button" href="/api/google/connect">Connect Google</a></p>

  <h2>Calendars</h2>
  <p>After connecting, choose which calendars to read for conflicts and which one to write events to.</p>
  <ul>
    <li><a href="/api/google/calendars">List my calendars</a></li>
  </ul>
{/include}
```

- [ ] **Step 3: Update the `pending` + `google` native methods**

In `src/main/java/com/calit/web/AdminResource.java`:

Change the `google` declaration (line 51) from `public static native TemplateInstance google(String css);` to:

```java
        public static native TemplateInstance google(Long pendingCount);
```

Change the `pending` declaration (line 59) from `public static native TemplateInstance pending(List<Booking> pending, String css);` to:

```java
        public static native TemplateInstance pending(List<Booking> pending);
```

- [ ] **Step 4: Update the `google()`, `pending()`, `approveBooking()`, `declineBooking()` handlers**

Change `google()` (lines 213–214) from:

```java
        return Templates.google(Layout.CSS);
```

to (compute the badge count):

```java
        long pendingCount = Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
        return Templates.google(pendingCount);
```

Change the three `Templates.pending(pending, Layout.CSS)` returns — in `pending()` (line 331), `approveBooking()` (line 342), and `declineBooking()` (line 353) — each to:

```java
        return Templates.pending(pending);
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `mvn test -Dtest=AdminGoogleTest,ApproveDeclineTest`
Expected: PASS — `Connect Google`, `/api/google/connect`, `Approve`, `Decline` all present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/pending.html src/main/resources/templates/AdminResource/google.html src/main/java/com/calit/web/AdminResource.java
git commit -m "feat: redesign pending + google admin pages"
```

---

## Task 10: Convert `meetingTypes` + `availability` admin pages

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html`
- Modify: `src/main/resources/templates/AdminResource/availability.html`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (the `meetingTypes` + `availability` native methods + their handlers)
- Test: `src/test/java/com/calit/web/AdminMeetingTypesTest.java`, `AdminAvailabilityTest.java` (existing — must stay green)

- [ ] **Step 1: Rewrite `meetingTypes.html`**

Overwrite `src/main/resources/templates/AdminResource/meetingTypes.html` (adds `pendingCount` param for the nav; keeps every form field `name`, the `secret`/`inactive`/`approval` badges, and the `GOOGLE_MEET` option):

```html
{@java.util.List<com.calit.domain.MeetingType> types}
{@com.calit.domain.MeetingType.LocationType[] locationTypes}
{@java.lang.Long pendingCount}
{#include base title="Admin — Meeting types"}
  {#include adminNav pendingCount=pendingCount /}
  <h1>Meeting types</h1>

  {#for t in types}
    <article>
      <h3>{t.name}
        {#if t.secret}<span class="badge">secret</span>{/if}
        {#if !t.active}<span class="badge">inactive</span>{/if}
        {#if t.requiresApproval}<span class="badge">approval</span>{/if}
      </h3>
      <p>/{t.slug} &middot; {t.durationMinutes} min &middot; {t.locationType}{#if t.locationDetail} ({t.locationDetail}){/if}</p>
      <p>min notice {t.minNoticeMinutes} min &middot; horizon {t.horizonDays} days{#if t.slotIntervalMinutes} &middot; slot interval {t.slotIntervalMinutes} min{/if}</p>
      <form method="post" action="/admin/meeting-types/{t.id}/toggle" style="display:inline">
        <button type="submit" class="secondary">{#if t.active}Deactivate{#else}Activate{/if}</button>
      </form>
      <form method="post" action="/admin/meeting-types/{t.id}/delete" style="display:inline">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Create meeting type</h2>
  <form method="post" action="/admin/meeting-types">
    <label>Name <input type="text" name="name" required></label>
    <label>Slug <input type="text" name="slug" required></label>
    <label>Duration (minutes) <input type="number" name="durationMinutes" value="30" required></label>
    <label><input type="checkbox" name="secret"> Secret (hidden from public landing)</label>
    <label>Min scheduling notice (minutes)
      <input type="number" name="minNoticeMinutes" value="0" required></label>
    <label>Booking horizon (days)
      <input type="number" name="horizonDays" value="60" required></label>
    <label>Location type
      <select name="locationType">
        {#for lt in locationTypes}<option value="{lt}">{lt}</option>{/for}
      </select>
    </label>
    <label>Location detail (phone number / address / custom text; ignored for Google Meet)
      <input type="text" name="locationDetail"></label>
    <label>Slot interval (minutes, blank = back-to-back)
      <input type="number" name="slotIntervalMinutes"></label>
    <label><input type="checkbox" name="requiresApproval"> Requires owner approval (hold as pending)</label>
    <button type="submit">Create</button>
  </form>
{/include}
```

- [ ] **Step 2: Rewrite `availability.html`**

Overwrite `src/main/resources/templates/AdminResource/availability.html` (keeps the `TUESDAY` option + the rule cards):

```html
{@java.util.List<com.calit.domain.AvailabilityRule> rules}
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.lang.Long pendingCount}
{#include base title="Admin — Availability"}
  {#include adminNav pendingCount=pendingCount /}
  <h1>Availability (work hours)</h1>

  {#for r in rules}
    <article>
      <p><strong>{r.dayOfWeek}</strong> {r.startTime} &ndash; {r.endTime}
        &middot; {#if r.meetingTypeId}type #{r.meetingTypeId}{#else}global{/if}</p>
      <form method="post" action="/admin/availability/{r.id}/delete">
        <button type="submit" class="secondary">Delete</button>
      </form>
    </article>
  {/for}

  <h2>Add a rule</h2>
  <form method="post" action="/admin/availability">
    <label>Day
      <select name="dayOfWeek">
        <option>MONDAY</option><option>TUESDAY</option><option>WEDNESDAY</option>
        <option>THURSDAY</option><option>FRIDAY</option><option>SATURDAY</option><option>SUNDAY</option>
      </select>
    </label>
    <label>Start <input type="time" name="startTime" value="09:00" required></label>
    <label>End <input type="time" name="endTime" value="17:00" required></label>
    <label>Applies to
      <select name="meetingTypeId">
        <option value="">All (global)</option>
        {#for t in types}<option value="{t.id}">{t.name}</option>{/for}
      </select>
    </label>
    <button type="submit">Add rule</button>
  </form>
{/include}
```

- [ ] **Step 3: Update the `meetingTypes` + `availability` native methods**

In `src/main/java/com/calit/web/AdminResource.java`:

Change `meetingTypes` (lines 42–43) from:

```java
        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes, String css);
```

to:

```java
        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes, Long pendingCount);
```

Change `availability` (lines 45–46) from:

```java
        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<MeetingType> types, String css);
```

to:

```java
        public static native TemplateInstance availability(
                List<AvailabilityRule> rules, List<MeetingType> types, Long pendingCount);
```

- [ ] **Step 4: Update every `meetingTypes`/`availability` call site**

Add this private helper to `AdminResource` (e.g. right after the `reminderLeadMinutes` field, ~line 66) so each handler can fetch the badge count:

```java
    /** Pending-approval count for the shared admin nav badge. */
    private long pendingCount() {
        return Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
    }
```

Then replace `Layout.CSS` with `pendingCount()` in all four `meetingTypes(...)` returns — `meetingTypes()` (line 85), `createMeetingType()` (line 117), `toggleActive()` (line 128), `deleteMeetingType()` (line 138) — so each reads:

```java
        return Templates.meetingTypes(MeetingType.listAll(), LocationType.values(), pendingCount());
```

And in all three `availability(...)` returns — `availability()` (lines 145–147), `createRule()` (lines 166–168), `deleteRule()` (lines 178–180) — change the trailing `Layout.CSS` to `pendingCount()`, e.g.:

```java
        return Templates.availability(
                AvailabilityRule.<AvailabilityRule>listAll(),
                MeetingType.listAll(), pendingCount());
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `mvn test -Dtest=AdminMeetingTypesTest,AdminAvailabilityTest`
Expected: PASS — all `name="..."` fields, `GOOGLE_MEET`, the `secret` badge, and `TUESDAY` still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/meetingTypes.html src/main/resources/templates/AdminResource/availability.html src/main/java/com/calit/web/AdminResource.java
git commit -m "feat: redesign meeting-types + availability admin pages"
```

---

## Task 11: Convert `settings` + `bookingFields` + `dateOverrides` admin pages

**Files:**
- Modify: `src/main/resources/templates/AdminResource/settings.html`
- Modify: `src/main/resources/templates/AdminResource/bookingFields.html`
- Modify: `src/main/resources/templates/AdminResource/dateOverrides.html`
- Modify: `src/main/java/com/calit/web/AdminResource.java` (the `settings`, `bookingFields`, `dateOverrides` native methods + their handlers)
- Test: `src/test/java/com/calit/web/AdminSettingsTest.java` (if present), `AdminBookingFieldsTest.java`, `AdminDateOverridesTest.java` (existing — must stay green)

- [ ] **Step 1: Rewrite `settings.html`**

Overwrite `src/main/resources/templates/AdminResource/settings.html` (keeps `Reminder lead`, the timezone default, and all field `name`s):

```html
{@com.calit.domain.OwnerSettings settings}
{@java.lang.Integer reminderLeadMinutes}
{@java.lang.Long pendingCount}
{#include base title="Admin — Settings"}
  {#include adminNav pendingCount=pendingCount /}
  <h1>Owner settings</h1>
  <form method="post" action="/admin/settings">
    <label>Name
      <input type="text" name="ownerName" required
             value="{#if settings}{settings.ownerName}{/if}"></label>
    <label>Email
      <input type="email" name="ownerEmail" required
             value="{#if settings}{settings.ownerEmail}{/if}"></label>
    <label>Timezone (IANA id)
      <input type="text" name="timezone" required
             value="{#if settings}{settings.timezone}{#else}Europe/Amsterdam{/if}"></label>
    <label><input type="checkbox" name="ownerNotificationsEnabled"
             {#if settings && settings.ownerNotificationsEnabled}checked{/if}>
      Send me (the owner) email notifications for bookings</label>
    <button type="submit">Save</button>
  </form>

  <p><strong>Reminder lead:</strong> {reminderLeadMinutes} minutes before the meeting
     <em>(set via the REMINDER_LEAD_MINUTES environment variable)</em></p>
{/include}
```

- [ ] **Step 2: Read the current `bookingFields.html` and `dateOverrides.html`, then convert each**

These two templates were not quoted in full in this plan. Before editing, read them:

Run: `cat src/main/resources/templates/AdminResource/bookingFields.html src/main/resources/templates/AdminResource/dateOverrides.html`

Apply the identical mechanical conversion used on every other admin page:
1. Delete the `{@java.lang.String css}` parameter line; add `{@java.lang.Long pendingCount}`.
2. Delete lines from `<!DOCTYPE html>` through the opening `<body>` **and** the `<nav>…</nav>` block, replacing them with `{#include base title="Admin — <Page>"}` then `{#include adminNav pendingCount=pendingCount /}`.
3. Delete the closing `</body></html>` and replace with `{/include}`.
4. Change each `<div class="card">…</div>` wrapper to `<article>…</article>` (optional but keeps the look consistent).
5. **Do not** change any `name="..."`, the `day off` text, the `2026-12-25`/`10:00` style values, or any field label — the tests assert on those (`name="date"`, `name="windowStart"`, `name="windowEnd"`, `name="meetingTypeId"`, `name="fieldKey"`, `name="type"`, `name="required"`, `LinkedIn URL`, `day off`).

- [ ] **Step 3: Update the `settings`, `bookingFields`, `dateOverrides` native methods**

In `src/main/java/com/calit/web/AdminResource.java`:

Change `settings` (lines 48–49) from:

```java
        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, String css);
```

to:

```java
        public static native TemplateInstance settings(
                OwnerSettings settings, int reminderLeadMinutes, Long pendingCount);
```

Change `bookingFields` (lines 53–54) from:

```java
        public static native TemplateInstance bookingFields(
                List<BookingField> fields, List<MeetingType> types, String css);
```

to:

```java
        public static native TemplateInstance bookingFields(
                List<BookingField> fields, List<MeetingType> types, Long pendingCount);
```

Change `dateOverrides` (lines 56–57) from:

```java
        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides, List<MeetingType> types, String css);
```

to:

```java
        public static native TemplateInstance dateOverrides(
                List<DateOverride> overrides, List<MeetingType> types, Long pendingCount);
```

- [ ] **Step 4: Update every call site for these three**

Replace the trailing `Layout.CSS` with `pendingCount()` (the helper added in Task 10) in:
- `settings()` (line 187) and `updateSettings()` (line 207) → `Templates.settings(s, reminderLeadMinutes, pendingCount())`
- `bookingFields()` (lines 221–223), `createBookingField()` (lines 246–248), `deleteBookingField()` (lines 258–260) → trailing arg becomes `pendingCount()`
- `dateOverrides()` (lines 280–282), `createOverride()` (lines 309–310), `deleteOverride()` (lines 321–322) → trailing arg becomes `pendingCount()`

Example (`settings()`):

```java
        return Templates.settings(OwnerSettings.get(), reminderLeadMinutes, pendingCount());
```

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `mvn test -Dtest=AdminBookingFieldsTest,AdminDateOverridesTest,AdminSettingsTest`
Expected: PASS. (If `AdminSettingsTest` does not exist, drop it from the `-Dtest` list.) `Reminder lead`, `Europe/Berlin`, `New Owner`, `LinkedIn URL`, `day off`, and all override/field `name`s still present.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/settings.html src/main/resources/templates/AdminResource/bookingFields.html src/main/resources/templates/AdminResource/dateOverrides.html src/main/java/com/calit/web/AdminResource.java
git commit -m "feat: redesign settings + booking-fields + date-overrides admin pages"
```

---

## Task 12: Remove the dead `Layout.CSS` constant and run the full suite

**Files:**
- Modify: `src/main/java/com/calit/web/Layout.java`

- [ ] **Step 1: Confirm nothing references `Layout.CSS` anymore**

Run: `grep -rn "Layout.CSS\|\\bCSS\\b" src/main/java/com/calit/web/`
Expected: no `Layout.CSS` usages remain (only the field definition itself, if still present).

- [ ] **Step 2: Delete the `CSS` constant**

In `src/main/java/com/calit/web/Layout.java`, delete the entire `public static final String CSS = """ … """;` block (lines 12–24) and update the class Javadoc (lines 3–7) to drop the "Shared inline CSS" sentence — the new opening line should read:

```java
/**
 * The invitee-only timezone-picker bar + reformat script, and the booking-page calendar
 * enhancement script. Styling now comes from Pico CSS + {@code /calit.css}, not an inline string.
 */
```

- [ ] **Step 3: Run the entire test suite**

Run: `mvn test`
Expected: PASS — full green. No references to `Layout.CSS`, `slotsByDate`, or `<style>` remain.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/calit/web/Layout.java
git commit -m "refactor: remove dead inline Layout.CSS"
```

---

## Task 13: Manual visual verification + README note

**Files:**
- Modify: `README.md` (add the new build dependency note)

- [ ] **Step 1: Run the app in dev mode**

Run: `mvn quarkus:dev` (Docker must be running for Dev Services Postgres).

- [ ] **Step 2: Visually verify each surface**

Open and eyeball:
- `http://localhost:8080/` — landing card grid, teal accent, light/dark follows OS.
- `http://localhost:8080/book/<a-seeded-slug>` — two-pane calendar on the left, time pills on the right; clicking a highlighted day swaps the time column; selecting a slot fills the pill; the booking form submits and confirms.
- `http://localhost:8080/admin` (login `admin`/`changeme`) — horizontal nav bar with the pending badge; walk every admin page.
- Toggle OS dark mode and reload `/` and `/book/...` to confirm both palettes look right.
- Disable JavaScript (devtools) and reload `/book/...` — day sections should stack and still be bookable (graceful fallback).

- [ ] **Step 3: Add a dependency note to the README**

In `README.md`, under "Requirements" (after the `Java 25` bullet, around line 17), add:

```markdown
- The UI is styled with **Pico CSS v2**, bundled as a Maven WebJar (`org.webjars.npm:picocss__pico`)
  and served locally via `quarkus-web-dependency-locator` — there is **no runtime CDN dependency**.
```

- [ ] **Step 4: Final full verification**

Run: `mvn test`
Expected: PASS — the complete suite is green.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: note Pico CSS WebJar dependency"
```

---

## Self-Review Notes

- **Spec coverage:** "Public + admin" → all 5 public + 8 admin templates converted (Tasks 3–11). "Two-pane calendar grid" → Tasks 5–7. "Pico via WebJar, no internet" → Task 1 (`quarkus-web-dependency-locator` + WebJar). "Teal/emerald, auto light/dark" → `pico.jade.min.css` (Task 1) + custom layer (Task 2).
- **Test invariants:** Every existing RestAssured assertion (slot radios, honeypot/Turnstile markers, button text, admin field `name`s, badges, `CALIT_TZ_REFORMAT`/`Times shown in:`, `Connect Google`, `TUESDAY`, `day off`, `Reminder lead`) is explicitly preserved and re-verified in the task that touches its page.
- **Type consistency:** `DaySlots(String isoDate, String label, List<SlotView> slots)` is introduced in Task 5 and used identically in `book.html`/`manage.html` (Tasks 6–7) and the `book`/`manage` native methods. `pendingCount()` helper (Task 10) is reused by Tasks 10–11. `CALENDAR_SCRIPT` is referenced as `calScript` in both invitee templates.
- **Open follow-up (not blocking):** the JS-off fallback shows all day sections stacked; acceptable per the existing app already requiring JS for timezone relabeling.
```
---

# Appendix: Form-Based Authentication (Task 14)

**Goal:** Replace HTTP Basic (browser popup) with a Pico-styled form login: a `/login` page, logout, and a "remember me" option. Single owner; same `ADMIN_PASSWORD` / embedded-properties identity store. Form auth in **every** profile; Basic removed; all admin tests rewritten to log in via the cookie flow.

**Mechanism (verified against Quarkus 3.36 docs):** form auth keeps the identity in an **encrypted cookie** `quarkus-credential` (no server session — fits the stateless N-replica design). Login posts to `/j_security_check` with `j_username`/`j_password`; `quarkus.http.auth.session.encryption-key` must be ≥16 chars (env secret in prod, like `GOOGLE_OAUTH_STATE_SECRET`). There is **no native remember-me** — implemented here with a tiny route filter that makes the credential cookie persistent only when the box is checked.

**Invariants:** every admin page still requires the `admin` role; `/login`, `/logout`, `/j_security_check`, `/calit.css`, `/webjars/*`, and all public booking pages stay open. Admin page markup/tests (field names, badges, headings) unchanged — only the *auth step* of each test changes.

## Task 14.1: Form auth config + login page + migrate admin tests

**Files:**
- Modify: `src/main/resources/application.properties` (swap Basic→form), `src/test/resources/application.properties` (unchanged creds; no Basic).
- Create: `src/main/java/com/calit/web/LoginResource.java`, `src/main/resources/templates/LoginResource/login.html`.
- Create test helper: `src/test/java/com/calit/web/FormAuth.java`.
- Modify (auth step only): all 8 `Admin*Test.java`.
- Modify: `.env.example`, `README.md` (SESSION_ENCRYPTION_KEY).

- [ ] **Step 1 — config.** In `src/main/resources/application.properties`, under the admin-auth section: remove `quarkus.http.auth.basic=true`. Keep the `quarkus.security.users.embedded.*` lines and the `/admin` permission block. Add:
```properties
quarkus.http.auth.form.enabled=true
quarkus.http.auth.form.login-page=/login
quarkus.http.auth.form.error-page=/login?error=true
quarkus.http.auth.form.landing-page=/admin
quarkus.http.auth.form.http-only-cookie=true
quarkus.http.auth.form.cookie-same-site=lax
quarkus.http.auth.session.encryption-key=${SESSION_ENCRYPTION_KEY:dev-only-insecure-session-key-change-me-0123456789}
%prod.quarkus.http.auth.session.encryption-key=${SESSION_ENCRYPTION_KEY}
```
(The default key is ≥16 chars for dev/test; prod requires the env var.)

- [ ] **Step 2 — login page.** Create `LoginResource` (public): `@Path("/login")`, `@GET`, `@Produces(TEXT_HTML)`, renders a `@CheckedTemplate login(boolean error)`; read `?error=true` via `@RestQuery`/`@QueryParam`. Template `login.html` uses `{#include base title="Sign in"}`, an `<article>` card with a form `method="post" action="/j_security_check"` containing `name="j_username"`, `name="j_password"`, a submit `Sign in`, and (when `error`) a `<p class="err">Invalid credentials</p>`.

- [ ] **Step 3 — test helper.** Create `FormAuth` with:
```java
static String login() {
    return io.restassured.RestAssured.given().redirects().follow(false)
        .contentType("application/x-www-form-urlencoded")
        .formParam("j_username", "admin").formParam("j_password", "testpass")
        .when().post("/j_security_check")
        .then().extract().cookie("quarkus-credential");
}
```

- [ ] **Step 4 — migrate the 8 admin tests.** Replace every `.auth().preemptive().basic("admin", "testpass")` with `.cookie("quarkus-credential", FormAuth.login())`. In `AdminAuthTest`, change `dashboardRequiresAuth` to assert the redirect to login instead of 401:
```java
given().redirects().follow(false).when().get("/admin")
    .then().statusCode(302).header("Location", org.hamcrest.Matchers.containsString("/login"));
```
Add a `loginPageRenders` test: `GET /login` → 200, body contains `j_username` + `Sign in`.

- [ ] **Step 5** — `mvn test` full suite green. Commit (config, LoginResource, login.html, FormAuth, 8 tests, .env.example, README).

## Task 14.2: Logout

- [ ] Create `LogoutResource` (public `@Path("/logout")`, `@GET`): clears the cookie and redirects to `/login`. Clear by returning a `Response.seeOther("/login")` with a `quarkus-credential` cookie set to empty, `maxAge=0`, `path=/`, httpOnly. Add a **Log out** link/button to `adminNav.html` (`<li><a href="/logout">Log out</a></li>`).
- [ ] Test: with a logged-in cookie, `GET /logout` (redirects off) → 302 to `/login` + a `Set-Cookie quarkus-credential` with `Max-Age=0`. Commit.

## Task 14.3: Remember-me

- [ ] Add a `remember me` checkbox to `login.html`; a tiny inline script appends `?remember=true` to the form action when checked (keeps the body untouched so the value is readable as a query param).
- [ ] Add a `@io.quarkus.vertx.http.runtime.filters.RouteFilter` (or a Vert.x filter) that, on the `/j_security_check` response when `remember=true` is a query param, rewrites the outgoing `quarkus-credential` Set-Cookie to add `Max-Age` (e.g. 30 days) so it persists across browser restarts. Without the flag the cookie stays a session cookie.
- [ ] Tests: login with `remember=true` → `quarkus-credential` Set-Cookie has a `Max-Age`; without it → no `Max-Age` (session cookie). Commit.
