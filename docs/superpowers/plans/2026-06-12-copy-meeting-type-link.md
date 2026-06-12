# Copy Meeting-Type Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Copy link" button to each card on the admin meeting-types list (`/me/meeting-types`) that copies the meeting type's absolute public booking URL (`{app.base-url}/{username}/{slug}`) to the clipboard and shows a daisyUI toast.

**Architecture:** Server renders the absolute URL into a `data-copy-link` attribute per card (URL built server-side where `baseUrl` + `username` live). Vanilla JS, event-delegated on `.copy-link-btn`, reads the attribute, calls `navigator.clipboard.writeText`, and toasts. No new endpoints.

**Tech Stack:** Quarkus (RESTEasy Reactive JAX-RS), Qute type-safe templates, daisyUI 5 + Tailwind v4, JUnit 5 + RestAssured (`@QuarkusTest`).

---

## File Structure

- Modify: `src/main/java/com/calit/web/AdminResource.java` — inject `baseUrl`, extend `Templates.meetingTypes` signature, update 4 call sites.
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` — 2 new header params, copy button per card, toast container + JS.
- Modify: `src/test/java/com/calit/web/AdminMeetingTypesTest.java` — new test for copy-link markup.

No new files. No new migrations. No CSS rebuild needed (daisyUI `.toast`/`.alert`/`.btn` classes already in compiled `calit.css`).

---

## Task 1: Render absolute booking URL + copy button on each card

**Files:**
- Modify: `src/main/java/com/calit/web/AdminResource.java:46-49` (signature), `:81` area (new field), `:117`, `:159`, `:217`, `:229` (call sites)
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html:1-32`
- Test: `src/test/java/com/calit/web/AdminMeetingTypesTest.java`

- [ ] **Step 1: Write the failing test**

Add this method to `AdminMeetingTypesTest` (class already has `@Test`, `containsString`, `FormAuth` imports). It seeds a type with a known slug, then asserts the absolute URL and copy button render.

```java
    @Transactional
    void seedCoffee() {
        if (MeetingType.findBySlug(1L, "coffee") == null) {
            MeetingType m = new MeetingType();
            m.ownerId = 1L;
            m.name = "Coffee Chat";
            m.slug = "coffee";
            m.durationMinutes = 30;
            m.persist();
        }
    }

    @Test
    void cardRendersAbsoluteCopyLinkAndButton() {
        seedCoffee();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                // absolute booking URL in a data attribute (base-url default + admin username + slug)
                .body(containsString("data-copy-link=\"http://localhost:8080/admin/coffee\""))
                // the copy button itself
                .body(containsString("copy-link-btn"));
    }
```

Note: `MeetingType.findBySlug(Long ownerId, String slug)` exists (`MeetingType.java:82`). The seeded admin is `admin`/`testpass` (`FormAuth.java:19`); `app.base-url` test default is `http://localhost:8080` (`application.properties:56`, no test override).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#cardRendersAbsoluteCopyLinkAndButton`
Expected: FAIL — body does not contain `data-copy-link` (button/attribute not yet rendered).

- [ ] **Step 3: Inject baseUrl into AdminResource**

Add the import near the other imports at the top of `AdminResource.java`:

```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
```

Add the field next to the `currentOwner` field (around `AdminResource.java:81`):

```java
    @ConfigProperty(name = "app.base-url")
    String baseUrl;
```

- [ ] **Step 4: Extend the native template method signature**

In the `Templates` inner class, replace the `meetingTypes` declaration (`AdminResource.java:46-49`):

```java
        public static native TemplateInstance meetingTypes(
                List<MeetingType> types, LocationType[] locationTypes,
                DayOfWeek[] daysOfWeek, Long pendingCount, boolean isAdmin,
                String username, String baseUrl);
```

- [ ] **Step 5: Update all 4 call sites**

Each existing call passes `(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(), DayOfWeek.values(), pendingCount(), isAdmin())`. Append `, currentOwner.require().username, baseUrl` to each.

`AdminResource.java:117` (in `meetingTypes()`):

```java
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin(), currentOwner.require().username, baseUrl); // includes secret
```

`AdminResource.java:159` (in `createMeetingType(...)`):

```java
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin(), currentOwner.require().username, baseUrl);
```

`AdminResource.java:217` (in `toggleActive(...)`):

```java
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin(), currentOwner.require().username, baseUrl);
```

`AdminResource.java:229` (in `deleteMeetingType(...)`):

```java
        return Templates.meetingTypes(MeetingType.listForOwner(currentOwner.id()), allowedLocationTypes(),
                DayOfWeek.values(), pendingCount(), isAdmin(), currentOwner.require().username, baseUrl);
```

Note: the exact trailing comment (`// includes secret`) only exists on line 117; preserve whatever comment is already there per line. Confirm there are exactly 4 call sites with `grep -n "Templates.meetingTypes(" src/main/java/com/calit/web/AdminResource.java` — update every one.

- [ ] **Step 6: Add the two new params to the template header**

At the top of `meetingTypes.html`, after the existing `{@java.lang.Boolean isAdmin}` line (`meetingTypes.html:5`), add:

```html
{@java.lang.String username}
{@java.lang.String baseUrl}
```

- [ ] **Step 7: Add the copy-link button to each card's actions row**

In `meetingTypes.html`, the actions row currently is (`meetingTypes.html:20-27`):

```html
        <div class="flex flex-wrap items-center gap-2 mt-1">
          <a href="/me/meeting-types/{t.id}" role="button" class="btn btn-outline btn-sm">Edit</a>
```

Insert the copy button as the first action, immediately after the opening `<div ...>`:

```html
        <div class="flex flex-wrap items-center gap-2 mt-1">
          <button type="button" class="btn btn-ghost btn-sm copy-link-btn" data-copy-link="{baseUrl}/{username}/{t.slug}">
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1"/><path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"/></svg>
            Copy link
          </button>
          <a href="/me/meeting-types/{t.id}" role="button" class="btn btn-outline btn-sm">Edit</a>
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#cardRendersAbsoluteCopyLinkAndButton`
Expected: PASS.

- [ ] **Step 9: Run the full AdminMeetingTypesTest to confirm no regression**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest`
Expected: PASS (all methods, including the existing `adminListShowsSecretTypeUnlikePublicLanding` and `createFormExposesNewFields`).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/meetingTypes.html \
        src/test/java/com/calit/web/AdminMeetingTypesTest.java
git commit -m "feat(web): render absolute copy-link URL on meeting-type cards"
```

---

## Task 2: Wire clipboard copy + daisyUI toast

**Files:**
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (toast container + script at end of inserted content)
- Test: `src/test/java/com/calit/web/AdminMeetingTypesTest.java`

- [ ] **Step 1: Write the failing test**

Add this method to `AdminMeetingTypesTest`. It asserts the toast container and the clipboard script render on the page. (RestAssured cannot execute JS, so this verifies the markup/script are present, matching the existing string-assertion test style.)

```java
    @Test
    void pageIncludesToastAndClipboardScript() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("id=\"copy-toast\""))        // toast container
                .body(containsString("navigator.clipboard"));      // clipboard wiring
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#pageIncludesToastAndClipboardScript`
Expected: FAIL — body contains neither `id="copy-toast"` nor `navigator.clipboard`.

- [ ] **Step 3: Add the toast container and clipboard script**

`meetingTypes.html` ends its content with `{/for}` then `</div>` then `{/include}`. Add the toast container and script just before the closing `{/include}` tag (the last line of the file). The toast lives at the page edge; the script is event-delegated so it covers every card including ones added after a re-render.

```html
  <div class="toast toast-end z-50" id="copy-toast" style="display:none">
    <div class="alert alert-success">
      <span id="copy-toast-msg">Link copied</span>
    </div>
  </div>

  <script>
    (function () {
      var toast = document.getElementById('copy-toast');
      var msg = document.getElementById('copy-toast-msg');
      var timer = null;
      function showToast(text) {
        if (!toast) { return; }
        if (msg) { msg.textContent = text; }
        toast.style.display = '';
        if (timer) { clearTimeout(timer); }
        timer = setTimeout(function () { toast.style.display = 'none'; }, 2000);
      }
      document.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('.copy-link-btn') : null;
        if (!btn) { return; }
        var url = btn.getAttribute('data-copy-link');
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(url).then(
            function () { showToast('Link copied'); },
            function () { showToast('Copy failed — ' + url); }
          );
        } else {
          showToast('Copy failed — ' + url);
        }
      });
    })();
  </script>
{/include}
```

Note: confirm the file's final line is `{/include}` (it closes the `{#include adminBase ...}` opened at `meetingTypes.html:6`); place the block immediately before it. The `{/for}` and its card `</div>`s close earlier — do not nest the toast inside the `{#for}` loop.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest#pageIncludesToastAndClipboardScript`
Expected: PASS.

- [ ] **Step 5: Run the full AdminMeetingTypesTest**

Run: `./mvnw test -Dtest=AdminMeetingTypesTest`
Expected: PASS (all four methods).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/AdminResource/meetingTypes.html \
        src/test/java/com/calit/web/AdminMeetingTypesTest.java
git commit -m "feat(web): copy meeting-type link to clipboard with toast"
```

---

## Task 3: Full test suite + manual browser verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all tests green (the suite uses `reuseForks=true` with per-test DB truncate+reseed; the two new tests seed idempotently).

- [ ] **Step 2: Manual browser verification (clipboard cannot be unit-tested)**

Run the app: `./mvnw quarkus:dev`
Then in a browser:
1. Log in, go to `http://localhost:8080/me/meeting-types`.
2. Click "Copy link" on a card. Expect a green toast "Link copied" bottom-right for ~2s.
3. Paste into a text field — expect `http://localhost:8080/{username}/{slug}` (the booking page URL).
4. Open the pasted URL — expect it loads that meeting type's public booking page.

Expected: toast appears, clipboard holds the correct absolute URL, URL resolves to the booking page.

(Optional automated check: a Playwright test navigating to the page, clicking `.copy-link-btn`, and asserting the toast becomes visible. Not required; clipboard read in headless contexts needs permission grants.)

- [ ] **Step 3: Final commit if any verification fixups were needed**

```bash
git add -A
git commit -m "test: verify copy meeting-type link end to end"
```

(Skip if Steps 1-2 passed with no changes.)

---

## Self-Review Notes

- **Spec coverage:** placement (list only) → Task 1 Step 7; absolute URL → Task 1 Steps 3-7 + test Step 1; daisyUI toast → Task 2. All three locked decisions covered.
- **Type consistency:** the extended signature `meetingTypes(..., String username, String baseUrl)` (Task 1 Step 4) matches the template header params `{@java.lang.String username}` / `{@java.lang.String baseUrl}` (Task 1 Step 6) and the call-site args `currentOwner.require().username, baseUrl` (Task 1 Step 5). Button class `copy-link-btn`, attribute `data-copy-link`, toast id `copy-toast` / `copy-toast-msg` are used identically in markup, JS, and tests.
- **No placeholders:** every code/markup/command step is concrete.
