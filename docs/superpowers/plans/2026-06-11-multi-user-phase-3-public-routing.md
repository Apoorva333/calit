# Multi-User Support — Phase 3: Public Per-User Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-owner public routes (`/` landing, `/book/{slug}`) with per-user public routing (`/{user}` landing, `/{user}/{slug}` booking) and make Google OAuth per-user, so each user is served from their own personal URL and connects their own Google account.

**Architecture:** `PublicResource` resolves the owner from the leading `{user}` path segment (`AppUser.findByUsername(Usernames.normalize(user))`, 404 on miss), sets `CurrentOwner`, and runs every lookup owner-scoped (`MeetingType.findBySlug(ownerId, slug)`, `OwnerSettings.forOwner(ownerId)`). The root `/` becomes a minimal generic calit index (no owner). RESTEasy Reactive resolves literal path segments (`/login`, `/me`, `/api/...`, `/booking/...`, `/q/health`) before the `/{user}` and `/{user}/{slug}` templates, so reserved literals keep working; `Usernames.isReserved` is defence-in-depth enforced at signup/create in Phase 4. Google OAuth `state` is extended to carry the initiating owner id (HMAC-signed) so the callback writes `GoogleCredential.forOwner(ownerId)` for the right user; `/api/google/*` is authenticated and reads `CurrentOwner`.

**Tech Stack:** Quarkus 3.36.1, RESTEasy Reactive, Hibernate Panache, Qute, Postgres, Google OAuth client.

> **Docker required:** the tests use Quarkus Dev Services, which start an ephemeral Postgres container. Docker (or Podman) must be running before `./mvnw test`.

---

## Assumed-present contracts (Phases 1 & 2 — DO NOT redefine)

These types/methods exist already; reference them, never re-implement them:

- `com.calit.user.AppUser` — Panache entity. `public Long id;`, `public String username;`. Static `AppUser findByUsername(String username)` (returns `null` if none).
- `com.calit.user.Usernames` — `static boolean isReserved(String)`, `static boolean isValid(String)`, `static String normalize(String)` (lowercase/trim).
- `com.calit.user.CurrentOwner` — `@RequestScoped`. `void set(AppUser)`, `AppUser get()`, `Long id()`, `AppUser require()`.
- `OwnerSettings.forOwner(Long ownerId)` → that owner's `OwnerSettings` row or `null`.
- `MeetingType.findBySlug(Long ownerId, String slug)` → that owner's type for `slug` or `null` (secret types reachable by direct link).
- `MeetingType.listPublic(Long ownerId)` → that owner's active, non-secret types.
- `GoogleCredential.forOwner(Long ownerId)` → that owner's credential row or `null`. `GoogleCredential` has a `public Long ownerId;` field.
- `Booking` has `public Long ownerId;`, set from the resolved owner via the meeting type in Phase 2.
- `/me/*` is the authenticated owner UI; `MeOwnerFilter` sets `CurrentOwner` from the logged-in identity for `/me/*`.
- `CalendarPort.isConnected()` already resolves per-owner via `CurrentOwner` (Phase 2). The public handlers set `CurrentOwner` before calling it.

---

## File Structure

**Modified:**
- `src/main/java/com/calit/web/PublicResource.java` — root `/` becomes generic index; `/book/{slug}` GET+POST become `/{user}/{slug}`; add `/{user}` landing; all handlers resolve owner → `CurrentOwner` → owner-scoped lookups.
- `src/main/resources/templates/PublicResource/landing.html` — meeting-type links `/book/{slug}` → `/{user}/{slug}`; template now takes `user` + `ownerName`.
- `src/main/resources/templates/PublicResource/book.html` — form action `/book/{type.slug}` → `/{user}/{type.slug}`; "All meeting types" back-link `/` → `/{user}`; template takes `user`.
- `src/main/java/com/calit/google/GoogleOAuthResource.java` — `/connect` and `/callback` become authenticated and owner-aware; callback redirects to `/me/google`.
- `src/main/java/com/calit/google/GoogleTokenService.java` — `state` carries the owner id; `buildConsentUrl(ownerId, now)`, `validateState` returns the decoded owner id; `exchangeCode(code, ownerId, now)` writes `GoogleCredential.forOwner(ownerId)`.

**Created:**
- `src/main/resources/templates/PublicResource/index.html` — generic root `/` page (no owner).
- `src/test/java/com/calit/web/PublicUserRoutingTest.java` — `/{user}` and `/{user}/{slug}` resolution + 404 on unknown user.
- `src/test/java/com/calit/web/ReservedRouteTest.java` — literal routes win over `/{user}`; reserved words are not user landings.
- `src/test/java/com/calit/google/PerUserOAuthStateTest.java` — state round-trips the owner id; per-user `GoogleCredential` written.

**Updated tests:**
- `src/test/java/com/calit/web/PublicLandingTest.java` — `/` is the generic index; per-user landing moves to `PublicUserRoutingTest`.
- `src/test/java/com/calit/web/BookPageTest.java` — `/book/{slug}` → `/{user}/{slug}`; seed an `AppUser` owner.
- `src/test/java/com/calit/web/BookingPostTest.java` — `/book/{slug}` → `/{user}/{slug}`; seed an `AppUser` owner.

---

## Task 1: Generic root `/` index (replace owner landing at root)

**Files:**
- Create: `src/main/resources/templates/PublicResource/index.html`
- Modify: `src/main/java/com/calit/web/PublicResource.java` (root handler + Templates)
- Modify (test): `src/test/java/com/calit/web/PublicLandingTest.java`

- [ ] **Step 1: Rewrite the failing test** — `/` is now a generic calit index, NOT an owner page.

Replace the entire contents of `src/test/java/com/calit/web/PublicLandingTest.java` with:

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class PublicLandingTest {

    @Test
    void rootServesGenericCalitIndexNotAnOwnerLanding() {
        given()
            .when().get("/")
            .then()
                .statusCode(200)
                // Generic index: names the product and points users at login.
                .body(containsString("calit"))
                .body(containsString("href=\"/login\""))
                // It must NOT list any owner's meeting types (no per-owner data on the root).
                .body(not(containsString("Choose a time")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PublicLandingTest`
Expected: FAIL — the current `/` renders `landing.html` listing meeting types (no `/login` link, contains "Choose a time").

- [ ] **Step 3: Create the generic index template**

Create `src/main/resources/templates/PublicResource/index.html`:

```html
{#include base title="calit"}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <div class="card-body items-center text-center">
      <h1 class="text-3xl font-bold">calit</h1>
      <p class="text-base-content/70">Self-hosted scheduling. Each user has their own booking page.</p>
      <div class="card-actions mt-2">
        <a role="button" class="btn btn-primary" href="/login">Sign in</a>
      </div>
    </div>
  </div>
{/include}
```

- [ ] **Step 4: Point the root handler at the index template**

In `src/main/java/com/calit/web/PublicResource.java`, change the `Templates` declaration for `landing` and add `index`. Replace this line:

```java
        public static native TemplateInstance landing(List<MeetingType> types);
```

with:

```java
        public static native TemplateInstance index();
        public static native TemplateInstance landing(List<MeetingType> types, String user, String ownerName);
```

Then replace the root handler:

```java
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance landing() {
        // listPublic() = active && !secret — secret types never reach this page.
        return Templates.landing(MeetingType.listPublic());
    }
```

with:

```java
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        // Root is a generic product page — NOT any owner's landing. Per-owner landings live at /{user}.
        return Templates.index();
    }
```

(The `landing(...)` Templates method now has the new 3-arg signature for Task 3; it is unused until then, which is fine — `@CheckedTemplate` only requires the matching template, added in Task 3.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=PublicLandingTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/PublicResource/index.html \
        src/main/java/com/calit/web/PublicResource.java \
        src/test/java/com/calit/web/PublicLandingTest.java
git commit -m "feat: generic root index, move owner landing off /

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Per-user landing `/{user}`

**Files:**
- Modify: `src/main/java/com/calit/web/PublicResource.java` (add `/{user}` handler + `CurrentOwner` inject)
- Modify: `src/main/resources/templates/PublicResource/landing.html`
- Test: `src/test/java/com/calit/web/PublicUserRoutingTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/web/PublicUserRoutingTest.java`:

```java
package com.calit.web;

import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class PublicUserRoutingTest {

    /** Idempotent across committed tx: a user "alice" with settings + one public + one secret type. */
    @Transactional
    Long seedAlice() {
        AppUser alice = AppUser.findByUsername("alice");
        if (alice == null) {
            alice = new AppUser();
            alice.username = "alice";
            alice.passwordHash = "x"; // not exercised by public routing
            alice.persist();
        }
        Long ownerId = alice.id;

        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "alice-intro");
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "alice-secret");

        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Alice Owner"; s.ownerEmail = "alice@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType pub = new MeetingType();
        pub.ownerId = ownerId; pub.name = "Alice Intro Call"; pub.slug = "alice-intro";
        pub.durationMinutes = 30;
        pub.persist();

        MeetingType secret = new MeetingType();
        secret.ownerId = ownerId; secret.name = "Alice Secret Session"; secret.slug = "alice-secret";
        secret.durationMinutes = 30; secret.secret = true;
        secret.persist();
        return ownerId;
    }

    @Test
    void userLandingListsThatOwnersPublicTypesAndHidesSecret() {
        seedAlice();
        given()
            .when().get("/alice")
            .then()
                .statusCode(200)
                .body(containsString("Alice Owner"))                 // ownerName rendered
                .body(containsString("Alice Intro Call"))            // public type listed
                .body(containsString("href=\"/alice/alice-intro\"")) // per-user booking link
                .body(not(containsString("Alice Secret Session")));  // secret hidden
    }

    @Test
    void unknownUserLandingReturns404() {
        given()
            .when().get("/nobody-here")
            .then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PublicUserRoutingTest`
Expected: FAIL — there is no `/{user}` GET handler yet (404 for `/alice`).

- [ ] **Step 3: Add the `CurrentOwner` injection and the `/{user}` handler**

In `src/main/java/com/calit/web/PublicResource.java`, add imports near the other `com.calit` imports:

```java
import com.calit.user.AppUser;
import com.calit.user.Usernames;
import com.calit.user.CurrentOwner;
```

Add the injection next to the other `@Inject` fields (after `bookingService`):

```java
    @Inject
    CurrentOwner currentOwner;
```

Add a private helper method (place it just above `private List<DaySlots> daySlots(...)`):

```java
    /** Resolve the {user} segment to an owner, 404 if unknown, and bind CurrentOwner for the request. */
    private AppUser resolveOwner(String user) {
        AppUser owner = AppUser.findByUsername(Usernames.normalize(user));
        if (owner == null) {
            throw new NotFoundException("No user " + user);
        }
        currentOwner.set(owner);
        return owner;
    }
```

Add the per-user landing handler (place it right after the root `index()` handler):

```java
    @GET
    @Path("/{user}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance userLanding(@PathParam("user") String user) {
        AppUser owner = resolveOwner(user);
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady();
        }
        // listPublic(ownerId) = that owner's active && !secret types.
        return Templates.landing(MeetingType.listPublic(owner.id), owner.username, settings.ownerName);
    }
```

- [ ] **Step 4: Update `landing.html` to take `user` + `ownerName` and link per-user**

Replace the entire contents of `src/main/resources/templates/PublicResource/landing.html` with:

```html
{@java.util.List<com.calit.domain.MeetingType> types}
{@java.lang.String user}
{@java.lang.String ownerName}
{#include base title="Book a meeting"}
  <div class="max-w-3xl mx-auto">
    <header class="mb-6">
      <h1 class="text-3xl font-bold">Book a meeting</h1>
      {#if ownerName}<p class="font-semibold text-base-content/70">{ownerName}</p>{/if}
      <p class="text-base-content/70">Pick a meeting type to see available times.</p>
    </header>
    {#if types.isEmpty()}
      <div class="alert">No meeting types are currently available.</div>
    {#else}
      <div class="grid gap-4 sm:grid-cols-2">
        {#for t in types}
          <div class="card bg-base-100 border border-base-300 transition-shadow hover:shadow-lg">
            <div class="card-body">
              <h2 class="card-title">{t.name}</h2>
              <p class="text-sm text-base-content/70">{t.durationMinutes} min &middot; {t.locationType.display}</p>
              {#if t.description}<p class="text-sm text-base-content/70">{t.description}</p>{/if}
              <div class="card-actions mt-2">
                <a role="button" class="btn btn-primary btn-block" href="/{user}/{t.slug}">Choose a time &rarr;</a>
              </div>
            </div>
          </div>
        {/for}
      </div>
    {/if}
  </div>
{/include}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=PublicUserRoutingTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/landing.html \
        src/test/java/com/calit/web/PublicUserRoutingTest.java
git commit -m "feat: per-user landing at /{user}

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Booking GET `/{user}/{slug}`

**Files:**
- Modify: `src/main/java/com/calit/web/PublicResource.java` (`book` handler + `book` Templates signature)
- Modify: `src/main/resources/templates/PublicResource/book.html`
- Modify (test): `src/test/java/com/calit/web/BookPageTest.java`

- [ ] **Step 1: Update `BookPageTest` to the new scheme**

In `src/test/java/com/calit/web/BookPageTest.java`:

(a) Add imports near the existing `com.calit.domain` imports:

```java
import com.calit.user.AppUser;
```

(b) The `seed()` and `seedApprovalPhoneType()` methods currently use the singleton `OwnerSettings.get()` and unscoped `MeetingType`. Replace `seed()` with this owner-scoped version:

```java
    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) {
            owner = new AppUser(); owner.username = "bob"; owner.passwordHash = "x"; owner.persist();
        }
        Long ownerId = owner.id;

        // Idempotent across the multiple seed() calls in this class (committed tx, fixed slug).
        BookingField.delete("meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)",
                "book-page", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "book-page");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = ownerId; t.name = "Book Page Type"; t.slug = "book-page"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; // auto type, Meet location
        t.persist();

        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }

        BookingField f = new BookingField();
        f.meetingTypeId = t.id; f.fieldKey = "company"; f.label = "Company Name";
        f.type = FieldType.SHORT_TEXT; f.required = true; f.position = 0;
        f.persist();
    }
```

Replace `seedApprovalPhoneType()` with:

```java
    @Transactional
    void seedApprovalPhoneType() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) {
            owner = new AppUser(); owner.username = "bob"; owner.passwordHash = "x"; owner.persist();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "phone-approval");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = ownerId; t.name = "Phone Approval Type"; t.slug = "phone-approval"; t.durationMinutes = 60;
        t.locationType = LocationType.PHONE; t.locationDetail = "Call +1-555-0100";
        t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }
```

(c) Replace every `.get("/book/book-page")` with `.get("/bob/book-page")`, every `.get("/book/phone-approval")` with `.get("/bob/phone-approval")`, and `.get("/book/does-not-exist")` with `.get("/bob/does-not-exist")` throughout the file (8 occurrences total).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BookPageTest`
Expected: FAIL — no `/{user}/{slug}` GET handler yet; `/bob/book-page` resolves to the `/{user}` landing (200 but missing booking-form content) or 404, not the booking page.

- [ ] **Step 3: Rewrite the `book` GET handler and its Templates signature**

In `src/main/java/com/calit/web/PublicResource.java`, update the `book` Templates declaration to add the `user` parameter. Replace:

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
                String turnstileSiteKey,
                boolean googleConnected,
                String ownerName);
```

with:

```java
        public static native TemplateInstance book(
                String user,
                MeetingType type,
                java.util.List<PublicResource.DaySlots> days,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String tzBar,
                String tzScript,
                String calScript,
                boolean turnstileEnabled,
                String turnstileSiteKey,
                boolean googleConnected,
                String ownerName);
```

Replace the whole `book(...)` GET handler:

```java
    @GET
    @Path("/book/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("slug") String slug) {
        MeetingType type = MeetingType.findBySlug(slug); // secret types reachable by direct link
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        if (OwnerSettings.get() == null) {
            return Templates.notReady();
        }
        List<DaySlots> byDate = daySlots(type);
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        return Templates.book(type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                              OwnerSettings.get().ownerName);
    }
```

with:

```java
    @GET
    @Path("/{user}/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("user") String user, @PathParam("slug") String slug) {
        AppUser owner = resolveOwner(user); // 404 if unknown; binds CurrentOwner for this request
        MeetingType type = MeetingType.findBySlug(owner.id, slug); // secret types reachable by direct link
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady();
        }
        List<DaySlots> byDate = daySlots(type);
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        return Templates.book(owner.username, type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                              settings.ownerName);
    }
```

- [ ] **Step 4: Update `book.html` for the new param + per-user URLs**

In `src/main/resources/templates/PublicResource/book.html`:

(a) Add the `user` parameter declaration. After the line `{@com.calit.domain.MeetingType type}` (line 1) insert:

```html
{@java.lang.String user}
```

(b) Replace the back-link:

```html
  <p class="mb-3"><a class="link link-hover text-sm" href="/">&larr; All meeting types</a></p>
```

with:

```html
  <p class="mb-3"><a class="link link-hover text-sm" href="/{user}">&larr; All meeting types</a></p>
```

(c) Replace the form action:

```html
            <form method="post" action="/book/{type.slug}" class="space-y-4">
```

with:

```html
            <form method="post" action="/{user}/{type.slug}" class="space-y-4">
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=BookPageTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/book.html \
        src/test/java/com/calit/web/BookPageTest.java
git commit -m "feat: per-user booking GET /{user}/{slug}

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Booking POST `/{user}/{slug}` + owner_id assertion

**Files:**
- Modify: `src/main/java/com/calit/web/PublicResource.java` (`submitBooking` handler)
- Modify (test): `src/test/java/com/calit/web/BookingPostTest.java`

- [ ] **Step 1: Update `BookingPostTest` to the new scheme + assert `Booking.ownerId`**

In `src/test/java/com/calit/web/BookingPostTest.java`:

(a) Add imports near the existing `com.calit.domain` imports:

```java
import com.calit.user.AppUser;
import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
```

(b) Replace `seed()` with this owner-scoped version (captures the owner id in a field so the test can assert it):

```java
    Long seededOwnerId;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) {
            owner = new AppUser(); owner.username = "bob"; owner.passwordHash = "x"; owner.persist();
        }
        seededOwnerId = owner.id;

        com.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)",
                "confirm-type", owner.id);
        BookingField.delete("meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)",
                "confirm-type", owner.id);
        MeetingType.delete("ownerId = ?1 and slug = ?2", owner.id, "confirm-type");
        OwnerSettings s = OwnerSettings.forOwner(owner.id);
        if (s == null) { s = new OwnerSettings(); s.ownerId = owner.id; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = owner.id; t.name = "Confirm Type"; t.slug = "confirm-type"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; // auto type, Meet link
        t.persist();

        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }

        BookingField f = new BookingField();
        f.meetingTypeId = t.id; f.fieldKey = "company"; f.label = "Company Name";
        f.type = FieldType.SHORT_TEXT; f.required = true; f.position = 0;
        f.persist();
    }
```

(c) Replace `seedApprovalType()` with:

```java
    @Transactional
    void seedApprovalType() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) {
            owner = new AppUser(); owner.username = "bob"; owner.passwordHash = "x"; owner.persist();
        }
        com.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)",
                "approval-confirm", owner.id);
        MeetingType.delete("ownerId = ?1 and slug = ?2", owner.id, "approval-confirm");
        OwnerSettings s = OwnerSettings.forOwner(owner.id);
        if (s == null) { s = new OwnerSettings(); s.ownerId = owner.id; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = owner.id; t.name = "Approval Confirm Type"; t.slug = "approval-confirm"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }
```

(d) Update `firstSlot(...)` to the new URL:

```java
    private String firstSlot(String slug) {
        String html = given().when().get("/bob/" + slug).then().statusCode(200)
                .extract().asString();
        String startUtc = html.substring(
                html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return startUtc.substring(0, startUtc.indexOf('"'));
    }
```

(e) Replace every `.post("/book/confirm-type")` with `.post("/bob/confirm-type")` and `.post("/book/approval-confirm")` with `.post("/bob/approval-confirm")` (4 occurrences total).

(f) Add a new test asserting the resolved owner is written onto the booking (work item 7):

```java
    @Test
    void postSetsBookingOwnerIdFromResolvedUser() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new com.calit.google.CreatedEvent("evt-owner", "https://meet.google.com/owned",
                                                          "https://calendar.google.com/evt-owner"));
        seed();

        String chosen = firstSlot("confirm-type");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", chosen)
            .formParam("inviteeName", "Owned Booking")
            .formParam("inviteeEmail", "owned@example.com")
            .formParam("answers.company", "Acme Corp")
            .formParam("website", "")
            .when().post("/bob/confirm-type")
            .then().statusCode(200);

        Booking b = Booking.find("inviteeEmail = ?1 and status <> ?2",
                "owned@example.com", BookingStatus.CANCELLED).firstResult();
        org.junit.jupiter.api.Assertions.assertNotNull(b, "booking must be created");
        org.junit.jupiter.api.Assertions.assertEquals(seededOwnerId, b.ownerId,
                "Booking.ownerId must be the resolved /{user} owner");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BookingPostTest`
Expected: FAIL — no `/{user}/{slug}` POST handler; the POST 404s and `postSetsBookingOwnerIdFromResolvedUser` finds no booking.

- [ ] **Step 3: Rewrite the `submitBooking` POST handler**

In `src/main/java/com/calit/web/PublicResource.java`, replace the whole `submitBooking(...)` handler:

```java
    @POST
    @Path("/book/{slug}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance submitBooking(@PathParam("slug") String slug,
                                          @RestForm String startUtc,
                                          @RestForm String inviteeName,
                                          @RestForm String inviteeEmail,
                                          @RestForm String website,                 // honeypot
                                          @RestForm("cf-turnstile-response") String turnstileToken,
                                          MultivaluedMap<String, String> form) {
        MeetingType type = MeetingType.findBySlug(slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        if (OwnerSettings.get() == null) {
            return Templates.notReady();
        }

        // Collect every "answers.<fieldKey>" form param into the answers map (strip the prefix).
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : form.entrySet()) {
            if (e.getKey().startsWith("answers.")) {
                answers.put(e.getKey().substring("answers.".length()),
                            e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
        }

        Booking booking;
        try {
            // book(...) enforces required fields AND the abuse guards (Turnstile + honeypot +
            // per-email/day cap) server-side; the handler just forwards the two raw inputs.
            booking = bookingService.book(
                    slug, Instant.parse(startUtc), inviteeName, inviteeEmail, answers,
                    turnstileToken, website);
        } catch (BookingValidationException | AbuseException | RateLimitException
                 | BookingConflictException be) {
            // Required-field 422 OR an abuse-guard rejection (filled honeypot / failed Turnstile /
            // per-email cap) / slot conflict. Re-render the form inline with the message; do NOT
            // 500, NOT confirm. (Plan 3 has no common BookingException superclass, so catch each.)
            return Templates.book(type, daySlots(type), BookingField.formFor(type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                                  OwnerSettings.get().ownerName);
        }
        return confirmationPage(booking, type);
    }
```

with:

```java
    @POST
    @Path("/{user}/{slug}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance submitBooking(@PathParam("user") String user,
                                          @PathParam("slug") String slug,
                                          @RestForm String startUtc,
                                          @RestForm String inviteeName,
                                          @RestForm String inviteeEmail,
                                          @RestForm String website,                 // honeypot
                                          @RestForm("cf-turnstile-response") String turnstileToken,
                                          MultivaluedMap<String, String> form) {
        AppUser owner = resolveOwner(user); // 404 if unknown; binds CurrentOwner for this request
        MeetingType type = MeetingType.findBySlug(owner.id, slug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        OwnerSettings settings = OwnerSettings.forOwner(owner.id);
        if (settings == null) {
            return Templates.notReady();
        }

        // Collect every "answers.<fieldKey>" form param into the answers map (strip the prefix).
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : form.entrySet()) {
            if (e.getKey().startsWith("answers.")) {
                answers.put(e.getKey().substring("answers.".length()),
                            e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
        }

        Booking booking;
        try {
            // book(...) enforces required fields AND the abuse guards (Turnstile + honeypot +
            // per-email/day cap) server-side; the handler just forwards the two raw inputs.
            // CurrentOwner is bound above, so book(...) scopes the meeting type and stamps
            // Booking.ownerId for the resolved owner (Phase 2 behaviour via the meeting type).
            booking = bookingService.book(
                    slug, Instant.parse(startUtc), inviteeName, inviteeEmail, answers,
                    turnstileToken, website);
        } catch (BookingValidationException | AbuseException | RateLimitException
                 | BookingConflictException be) {
            // Required-field 422 OR an abuse-guard rejection (filled honeypot / failed Turnstile /
            // per-email cap) / slot conflict. Re-render the form inline with the message; do NOT
            // 500, NOT confirm. (Plan 3 has no common BookingException superclass, so catch each.)
            return Templates.book(owner.username, type, daySlots(type), BookingField.formFor(type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(),
                                  settings.ownerName);
        }
        return confirmationPage(booking, type);
    }
```

> Note: `bookingService.book(slug, ...)` keeps its existing signature; it scopes by the bound `CurrentOwner` (Phase 2). `confirmationPage(...)` and `daySlots(...)` read `OwnerSettings.forOwner` via `CurrentOwner` (already updated in Phase 2) — no change needed here.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BookingPostTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/web/PublicResource.java \
        src/test/java/com/calit/web/BookingPostTest.java
git commit -m "feat: per-user booking POST /{user}/{slug}, assert Booking.ownerId

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Reserved-route / literal-precedence guard

**Files:**
- Test: `src/test/java/com/calit/web/ReservedRouteTest.java`

This task proves that templated `/{user}` and `/{user}/{slug}` never shadow literal routes. RESTEasy Reactive resolves literal path segments before path-param templates, so no production change is needed — this task is a regression guard. If any assertion fails, the fix is to ensure the corresponding literal resource exists / is mapped (it already does from earlier phases).

- [ ] **Step 1: Write the failing/guard test**

Create `src/test/java/com/calit/web/ReservedRouteTest.java`:

```java
package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Literal path segments must win over the /{user} and /{user}/{slug} templates. None of these
 * literals may be captured as a username. The Usernames reserved set is defence-in-depth; signup
 * /create in Phase 4 rejects reserved words. RESTEasy Reactive resolves literals first.
 */
@QuarkusTest
class ReservedRouteTest {

    @Test
    void loginIsTheLoginPageNotAUserLanding() {
        given().when().get("/login")
            .then().statusCode(200)
                // The login form, NOT a "Book a meeting" owner landing.
                .body(containsString("j_security_check"))
                .body(not(containsString("Book a meeting")));
    }

    @Test
    void logoutIsHandledByItsLiteralResource() {
        // /logout clears the session and redirects (302/303) — it is not a 404 "unknown user".
        given().redirects().follow(false)
            .when().get("/logout")
            .then().statusCode(org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(303), org.hamcrest.Matchers.is(200)));
    }

    @Test
    void quarkusHealthEndpointIsNotCapturedAsUser() {
        // SmallRye Health lives at /q/health; "q" must not be swallowed by /{user}.
        given().when().get("/q/health")
            .then().statusCode(200)
                .body(containsString("UP"));
    }

    @Test
    void googleConnectIsAuthGuardedNotAUserLanding() {
        // /api/google/connect requires auth → anonymous gets a 302 to /login or 401, never a
        // 404 "unknown user 'api'" and never a meeting-type landing.
        given().redirects().follow(false)
            .when().get("/api/google/connect")
            .then().statusCode(org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(401),
                    org.hamcrest.Matchers.is(303)));
    }

    @Test
    void bookingManageTokenRouteIsNotCapturedAsUser() {
        // /booking/{token}/manage is token-based and literal-prefixed by "booking"; an unknown
        // token yields the manage-resource 404, proving "booking" was not read as a username
        // (a username 404 would also be 404, so additionally assert the SmallRye health + login
        // cases above pin literal precedence; here we only assert the route resolves, not to a
        // user landing body).
        given().when().get("/booking/no-such-token/manage")
            .then().statusCode(404)
                .body(not(containsString("Book a meeting")));
    }

    @Test
    void meRequiresAuthAndIsNotAUserLanding() {
        // /me is the authenticated owner UI; anonymous is redirected to login, never treated as
        // user "me".
        given().redirects().follow(false)
            .when().get("/me")
            .then().statusCode(org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(303),
                    org.hamcrest.Matchers.is(401)));
    }
}
```

- [ ] **Step 2: Run test to verify it passes (this is a guard — it should pass once Tasks 1–4 are in)**

Run: `./mvnw test -Dtest=ReservedRouteTest`
Expected: PASS — literal routes resolve to their own resources, not `/{user}`.

If any case FAILS: confirm the literal resource exists (`LoginResource` `/login`, `LogoutResource` `/logout`, `GoogleOAuthResource` `/api/google/*`, the `/me` UI from Phase 2, the `/booking/{token}/manage` handler in `PublicResource`, and `quarkus-smallrye-health` on the classpath for `/q/health`). Do not weaken the test; fix the wiring.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/calit/web/ReservedRouteTest.java
git commit -m "test: guard literal-route precedence over /{user} template

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Per-user Google OAuth state (carry owner id)

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java`
- Test: `src/test/java/com/calit/google/PerUserOAuthStateTest.java`

The OAuth `state` must round-trip the initiating owner id so the callback associates the new credential with the right user. State layout becomes:
`base64url(nonce) + ":" + ownerId + ":" + issuedAtEpochSec + "." + base64url(HMAC)`.
`validateState` returns the decoded owner id (or `null` for any malformed/forged/expired value).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/calit/google/PerUserOAuthStateTest.java`:

```java
package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PerUserOAuthStateTest {

    @Inject
    GoogleTokenService tokenService;

    @Test
    void stateRoundTripsTheOwnerId() {
        Instant now = Instant.now();
        String state = tokenService.issueState(42L, now);
        assertEquals(42L, tokenService.validateState(state, now),
                "callback must recover the owner id that initiated /connect");
    }

    @Test
    void forgedOrTamperedStateRejected() {
        Instant now = Instant.now();
        String state = tokenService.issueState(42L, now);
        // Flip the owner id in the signed payload → signature no longer matches → null.
        String tampered = state.replace(":42:", ":99:");
        assertNull(tokenService.validateState(tampered, now), "tampered state must be rejected");
        assertNull(tokenService.validateState("garbage.value", now), "malformed state must be rejected");
        assertNull(tokenService.validateState(null, now), "null state must be rejected");
    }

    @Test
    void expiredStateRejected() {
        Instant issued = Instant.now().minus(GoogleTokenService.STATE_TTL).minusSeconds(60);
        String state = tokenService.issueState(7L, issued);
        assertNull(tokenService.validateState(state, Instant.now()), "expired state must be rejected");
    }

    @Test
    void consentUrlCarriesSignedStateForOwner() {
        String url = tokenService.buildConsentUrl(7L, Instant.now());
        assertTrue(url.contains("state="), "consent URL must include a state param");
        assertTrue(url.startsWith("https://accounts.google.com/"), "consent URL points at Google");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PerUserOAuthStateTest`
Expected: FAIL — `issueState(long, Instant)` and `buildConsentUrl(long, Instant)` don't exist, and `validateState` currently returns `boolean`, not `Long`.

- [ ] **Step 3: Rewrite state issue/validate to carry the owner id**

In `src/main/java/com/calit/google/GoogleTokenService.java`:

(a) Replace `buildConsentUrl()` and `buildConsentUrl(Instant now)`:

```java
    /** The Google consent URL the owner is redirected to. Pure string building — no network. */
    public String buildConsentUrl() {
        return buildConsentUrl(Instant.now());
    }
```
```java
    public String buildConsentUrl(Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().redirectUri())
                + "&response_type=code"
                + "&scope=" + enc(config.oauth().scope())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(issueState(now));
    }
```

with (owner-aware; drop the no-arg/no-owner forms — every caller now passes an owner):

```java
    /**
     * The Google consent URL, carrying a stateless, signed CSRF {@code state} bound to {@code ownerId}.
     * Pure string building — no network.
     *
     * <p>Horizontal scalability: {@code /connect} and {@code /callback} can be served by different
     * replicas, so the state is self-describing — no HttpSession. Layout:
     * {@code base64url(nonce) + ":" + ownerId + ":" + issuedAtEpochSec + "." + base64url(HMAC-SHA256(payload))}
     * signed with the shared {@code google.oauth.state-secret}. Any replica validates it at
     * {@code /callback} by recomputing the HMAC and checking the {@link #STATE_TTL} window.
     */
    public String buildConsentUrl(long ownerId, Instant now) {
        return AUTH_ENDPOINT + "?"
                + "client_id=" + enc(config.oauth().clientId())
                + "&redirect_uri=" + enc(config.oauth().redirectUri())
                + "&response_type=code"
                + "&scope=" + enc(config.oauth().scope())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(issueState(ownerId, now));
    }
```

(b) Replace `issueState(Instant now)`:

```java
    /** Mint a signed, time-stamped state value. Stateless: nothing is stored server-side. */
    public String issueState(Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }
```

with:

```java
    /** Mint a signed, time-stamped state value bound to {@code ownerId}. Stateless: nothing stored. */
    public String issueState(long ownerId, Instant now) {
        String payload = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                + ":" + ownerId
                + ":" + now.getEpochSecond();
        return payload + "." + b64(hmac(payload));
    }
```

(c) Replace the entire `validateState(...)` method:

```java
    public boolean validateState(String state, Instant now) {
        if (state == null || state.isBlank()) {
            return false;
        }
        int dot = state.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String payload = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            return false;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        try {
            long issuedAt = Long.parseLong(payload.substring(colon + 1));
            Instant issued = Instant.ofEpochSecond(issuedAt);
            return !issued.isAfter(now) && !issued.plus(STATE_TTL).isBefore(now);
        } catch (NumberFormatException e) {
            return false;
        }
    }
```

with (returns the owner id on success, `null` otherwise):

```java
    /**
     * Validate a state returned on the callback and recover the owner id it was issued for. The
     * signature must verify and the issue time must be within {@link #STATE_TTL} of {@code now}.
     * No server-side session or lock — any replica validates with only the shared secret. Returns
     * {@code null} for any malformed, forged, or expired value.
     */
    public Long validateState(String state, Instant now) {
        if (state == null || state.isBlank()) {
            return null;
        }
        int dot = state.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String payload = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        // payload = base64url(nonce) ":" ownerId ":" issuedAtEpochSec
        int lastColon = payload.lastIndexOf(':');
        if (lastColon <= 0) {
            return null;
        }
        int prevColon = payload.lastIndexOf(':', lastColon - 1);
        if (prevColon <= 0) {
            return null;
        }
        try {
            long ownerId = Long.parseLong(payload.substring(prevColon + 1, lastColon));
            long issuedAt = Long.parseLong(payload.substring(lastColon + 1));
            Instant issued = Instant.ofEpochSecond(issuedAt);
            if (issued.isAfter(now) || issued.plus(STATE_TTL).isBefore(now)) {
                return null;
            }
            return ownerId;
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PerUserOAuthStateTest`
Expected: PASS

> The `GoogleOAuthResource` callback still calls the old `validateState(...)` as a `boolean` and `buildConsentUrl()` with no owner — that resource is rewritten in Task 7, which runs next. The project may not compile cleanly between Task 6 and Task 7; run the combined verification in Task 7. (`exchangeCode(String, Instant)` is also still owner-unaware until Task 7.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/google/GoogleTokenService.java \
        src/test/java/com/calit/google/PerUserOAuthStateTest.java
git commit -m "feat: OAuth state carries owner id (signed), validateState returns owner

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Per-user OAuth connect/callback + per-owner credential write

**Files:**
- Modify: `src/main/java/com/calit/google/GoogleTokenService.java` (`exchangeCode`)
- Modify: `src/main/java/com/calit/google/GoogleOAuthResource.java`
- Test: `src/test/java/com/calit/google/PerUserOAuthStateTest.java` (add `exchangeCode` write test)

- [ ] **Step 1: Add the failing per-owner credential-write test**

Append to `src/test/java/com/calit/google/PerUserOAuthStateTest.java` (inside the class):

```java
    @Inject
    com.calit.user.AppUser noop; // placeholder removed below — see actual injection

    @org.junit.jupiter.api.Test
    @jakarta.transaction.Transactional
    void exchangeCodeWritesCredentialForTheGivenOwner() {
        // Seed two owners; exchange a code for owner B and assert the credential lands on B, not A.
        com.calit.user.AppUser a = new com.calit.user.AppUser();
        a.username = "oauth-a"; a.passwordHash = "x"; a.persist();
        com.calit.user.AppUser b = new com.calit.user.AppUser();
        b.username = "oauth-b"; b.passwordHash = "x"; b.persist();

        // A subclass overrides the network round-trip so no real Google call happens.
        GoogleTokenService stub = new GoogleTokenService(
                com.google.inject.util.Providers.class == null ? null : null) {};
        // (Do NOT use the above — replaced by the StubTokenService approach below.)
    }
```

Then DELETE the placeholder block above and use this concrete, self-contained test instead — replace the two snippets just added with the following single method and helper (the network seam is `requestToken`, which is `protected`, so subclass it in-test):

```java
    /** Subclass that stubs the one network method so exchangeCode does no real Google call. */
    static final class StubTokenService extends GoogleTokenService {
        StubTokenService(GoogleOAuthConfig config) { super(config); }
        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("access-tok", "refresh-tok", now.plusSeconds(3600));
        }
    }

    @Inject
    GoogleOAuthConfig oauthConfig;

    @org.junit.jupiter.api.Test
    @jakarta.transaction.Transactional
    void exchangeCodeWritesCredentialForTheGivenOwner() {
        com.calit.user.AppUser a = new com.calit.user.AppUser();
        a.username = "oauth-a"; a.passwordHash = "x"; a.persist();
        com.calit.user.AppUser b = new com.calit.user.AppUser();
        b.username = "oauth-b"; b.passwordHash = "x"; b.persist();

        StubTokenService stub = new StubTokenService(oauthConfig);
        stub.exchangeCode("any-code", b.id, Instant.now());

        com.calit.google.GoogleCredential credB = com.calit.google.GoogleCredential.forOwner(b.id);
        org.junit.jupiter.api.Assertions.assertNotNull(credB, "credential must be written for owner B");
        org.junit.jupiter.api.Assertions.assertEquals(b.id, credB.ownerId);
        org.junit.jupiter.api.Assertions.assertEquals("access-tok", credB.accessToken);
        org.junit.jupiter.api.Assertions.assertNull(com.calit.google.GoogleCredential.forOwner(a.id),
                "owner A must have no credential");
    }
```

Also remove the now-unused `@Inject com.calit.user.AppUser noop;` placeholder field if you added it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PerUserOAuthStateTest`
Expected: FAIL/compile error — `exchangeCode(String, Long, Instant)` does not exist yet (current signature is `exchangeCode(String, Instant)`).

- [ ] **Step 3: Rewrite `exchangeCode` to write the owner's credential**

In `src/main/java/com/calit/google/GoogleTokenService.java`, replace:

```java
    /** Exchange the callback {@code code} for tokens and persist the singleton credential. */
    @Transactional
    public void exchangeCode(String code, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        GoogleCredential c = GoogleCredential.get();
        if (c == null) {
            c = new GoogleCredential();
            c.id = GoogleCredential.SINGLETON_ID;
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.persist();
    }
```

with:

```java
    /** Exchange the callback {@code code} for tokens and persist {@code ownerId}'s credential row. */
    @Transactional
    public void exchangeCode(String code, Long ownerId, Instant now) {
        TokenResponse resp = requestToken("authorization_code", code, now);
        GoogleCredential c = GoogleCredential.forOwner(ownerId);
        if (c == null) {
            c = new GoogleCredential();
            c.ownerId = ownerId;
        }
        if (resp.refreshToken() != null) {
            c.refreshToken = resp.refreshToken();
        }
        c.accessToken = resp.accessToken();
        c.accessTokenExpiry = resp.expiry();
        c.persist();
    }
```

- [ ] **Step 4: Rewrite `GoogleOAuthResource` to be owner-aware and authenticated**

Replace the entire contents of `src/main/java/com/calit/google/GoogleOAuthResource.java` with:

```java
package com.calit.google;

import com.calit.user.CurrentOwner;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Instant;

/**
 * Per-user Google OAuth. {@code /connect} signs the consent {@code state} with the logged-in owner
 * id (via {@link CurrentOwner}); {@code /callback} recovers that owner id from the verified state
 * and writes the credential for the right user. Both are {@code @Authenticated} — the owner must be
 * logged in to connect a Google account.
 */
@Path("/api/google")
@Authenticated
public class GoogleOAuthResource {

    private final GoogleTokenService tokenService;
    private final CurrentOwner currentOwner;

    @Inject
    public GoogleOAuthResource(GoogleTokenService tokenService, CurrentOwner currentOwner) {
        this.tokenService = tokenService;
        this.currentOwner = currentOwner;
    }

    /** Kick off the owner consent flow: 302 to Google, state bound to the logged-in owner. */
    @GET
    @Path("/connect")
    public Response connect() {
        long ownerId = currentOwner.id();
        return Response.status(Response.Status.FOUND)
                .location(URI.create(tokenService.buildConsentUrl(ownerId, Instant.now())))
                .build();
    }

    /** Google redirects back here with ?code=...&state=... (or ?error=...). */
    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_PLAIN)
    public Response callback(@QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Google authorization failed: " + error)
                    .build();
        }
        Instant now = Instant.now();
        // Stateless CSRF check: validate the signed state with no session and recover the owner id
        // that initiated /connect. /connect and /callback may be served by different replicas, so
        // verification uses only the shared signing secret.
        Long ownerId = tokenService.validateState(state, now);
        if (ownerId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid or expired OAuth state")
                    .build();
        }
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing authorization code")
                    .build();
        }
        tokenService.exchangeCode(code, ownerId, now);
        return Response.status(Response.Status.FOUND)
                .location(URI.create("/me/google"))
                .build();
    }
}
```

> The callback derives the owner from the signed `state`, not from `CurrentOwner`, because Google's redirect arrives without the owner's session guaranteed; the state is the trusted carrier. `@Authenticated` on the class still requires a logged-in session for both endpoints (Phase 1 form login).

- [ ] **Step 5: Run the full OAuth + public suite to verify it passes**

Run: `./mvnw test -Dtest=PerUserOAuthStateTest,PublicLandingTest,PublicUserRoutingTest,BookPageTest,BookingPostTest,ReservedRouteTest`
Expected: PASS (all)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/google/GoogleTokenService.java \
        src/main/java/com/calit/google/GoogleOAuthResource.java \
        src/test/java/com/calit/google/PerUserOAuthStateTest.java
git commit -m "feat: per-user Google OAuth connect/callback, owner-scoped credential write

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Full regression sweep

**Files:** none (verification only)

- [ ] **Step 1: Run the whole suite**

Run: `./mvnw test`
Expected: PASS. Watch specifically for any leftover `/book/` reference (none should remain in `src/main`) and for any `validateState`/`buildConsentUrl`/`exchangeCode` callers in other modules that still use the old signatures.

- [ ] **Step 2: Confirm no stale `/book/` links remain**

Run: `git grep -n "/book/" src/main`
Expected: no output (all migrated to `/{user}/{slug}`). Invitee manage links (`/booking/{token}/manage`) and email `manageUrl` are token-based and intentionally unchanged — they must NOT appear in this grep.

- [ ] **Step 3: Commit (only if Steps 1–2 surfaced a fix)**

```bash
git add -A
git commit -m "chore: phase 3 public routing regression sweep

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (performed against the spec & work items)

**Spec coverage:**
- Work item 1 (rewrite booking GET/POST to `/{user}/{slug}`, owner resolve + scoped lookups, logic intact) → Tasks 3 & 4 (full method bodies shown).
- Work item 2 (`/{user}` landing, `listPublic(owner.id)`, `notReady` when settings null) → Task 2.
- Work item 3 (`/` becomes generic index, update `PublicLandingTest`) → Task 1.
- Work item 4 (reserved/literal precedence guard) → Task 5.
- Work item 5 (booking links migrated; `git grep /book/`) → Tasks 2, 3, 4 (landing.html, book.html) + Task 8 grep. Confirmation template uses `/booking/{manageToken}/manage` (token-based) and email `manageUrl` — both unchanged, verified in spec read.
- Work item 6 (per-user OAuth state + connect/callback, `@Authenticated`, concrete encode/decode) → Tasks 6 & 7.
- Work item 7 (`Booking.ownerId` set from resolved owner) → Task 4 `postSetsBookingOwnerIdFromResolvedUser`.

**Placeholder scan:** the only placeholder-shaped content was an intentionally-removed scaffold in Task 7 Step 1 (the `noop`/`Providers` lines), which the step explicitly instructs to delete and replaces with the concrete `StubTokenService` test. No "TBD/TODO/handle edge cases" remain; every code step shows full code.

**Type consistency:** `validateState` returns `Long` everywhere after Task 6 (used as `boolean`→`Long` in Task 7 callback). `buildConsentUrl(long, Instant)` / `issueState(long, Instant)` / `exchangeCode(String, Long, Instant)` signatures match between definition (Tasks 6/7) and call sites (Task 7 resource). `Templates.book(...)` gains a leading `String user` param consistently in handler, error re-render, and `book.html` declaration. `Templates.landing(types, user, ownerName)` matches `landing.html`'s three `{@...}` decls. `resolveOwner(String)` is defined once (Task 2) and reused by `book`/`submitBooking` (Tasks 3/4).
