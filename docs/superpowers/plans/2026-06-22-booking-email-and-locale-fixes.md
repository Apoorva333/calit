# Booking Email + Locale Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix four owner-facing booking defects and make every booking email role-appropriate: (1) Gmail "Unable to load event" on the `.ics`, (2) emails greet/word/link for the invitee even on the owner copy, (3) owner cannot approve/decline a pending request from email, (4) changing the admin locale does not apply until two navigations later — plus add an in-letter cancellation link for invitees.

**Architecture:** calit sends booking emails from `EmailService` (CDI observers, rendered per recipient role) with a hand-rolled `.ics` from `IcsBuilder`. Today owner and invitee copies are byte-identical except greeting name, locale, and formatted time — including the owner being handed the *invitee's* manage link. Fixes: (a) make the `.ics` a valid iTIP REQUEST; (b) branch every template on `recipientRole` so the owner gets owner-worded body text and owner-relevant links and the invitee gets manage + cancel links; (c) **owner approve/decline from email are authenticated one-click links** — `GET /me/bookings/{id}/approve?t={approvalToken}` behind `@RolesAllowed("user")`, so the owner logs in (form-auth redirects back to the link) and the action runs; the `approvalToken` is a CSRF nonce, auth + owner-scoping is the real authority; the **invitee cancel** link points to a public token-keyed confirmation page (invitees have no account); (d) refresh the request-scoped `ActiveLocale` after the settings save.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache entities, Qute `@CheckedTemplate`, Flyway migrations, quarkus-rest-csrf, Quarkus form authentication (`/j_security_check`, custom `AppUserIdentityProvider`), quarkus-qute message bundles (`AppMessages` = `msg` namespace, `AdminMessages` = `adm`), RestAssured + JUnit (Docker-backed Postgres).

## Global Constraints

- **Owner scoping:** every tenant query filters by `currentOwner.id()`. The owner approve/decline routes live under `/me` (`@RolesAllowed("user")`) and use `requireOwnedBooking(id)` (404 if the booking is not the current owner's). The public invitee manage/cancel routes are keyed by an unguessable `manageToken` (the token is the authority — invitees have no account). Never expose an `id`-keyed approve/decline/cancel to an unauthenticated caller.
- **Owner approve/decline from email = authenticated one-click GET:** the email link is `GET /me/bookings/{id}/approve?t={approvalToken}` (and `/decline`). It is behind form auth: an unauthenticated click is redirected to `/login` and, after login, back to the link (Quarkus form auth stores the original URL in the `quarkus-redirect-location` cookie and `redirect-after-login` defaults to true; `landing-page=/me` is only the fallback for direct `/login` visits). The handler runs only when authenticated. **`quarkus-rest-csrf` does not guard GET**, so the `approvalToken` query param is the CSRF nonce: a tricked top-level navigation can't guess it, and `SameSite=Lax` already blocks embedded cross-site GETs from carrying the session cookie. The handler verifies `t` equals `booking.approvalToken` (404 otherwise) **in addition to** `requireOwnedBooking`.
- **Email link safety (invitee cancel):** never let a bare unauthenticated GET mutate state — mail scanners prefetch GETs. The invitee cancel email link points to a GET confirmation page that renders a CSRF-protected POST form (the existing `POST /booking/{manageToken}/cancel`).
- **Flyway:** never edit an applied migration. Add a new `V*.sql` only. Next free number is `V17`.
- **CSRF:** quarkus-rest-csrf is ON in prod, OFF in `%test`. Every POST form must include `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">`. Tests need no token (extension disabled in `%test`).
- **i18n:** English is the default and lives in `@Message(...)` annotations on the bundle interface (there is no `msg_en.properties` / `adm_en.properties`). Every new key MUST also get a line in the matching `*_de.properties` and `*_he.properties`. Supported locales: `en`, `de`, `he`. `he` is RTL — templates switch `dir` on `lang == 'he'`.
- **No runtime JavaScript** beyond the existing inline TZ-reformat snippet (`Layout.TZ_SCRIPT`). Tests assert on server-rendered HTML, never by executing JS.
- **Qute checked templates:** every template variable is one parameter on the `static native TemplateInstance` method; a variable referenced in a template but never passed is a build-time error. Variables referenced only inside an untaken `{#if}` branch must still be passed on every render that includes that template.
- Run the full suite with `mvn test` (Docker must be running). Single class: `mvn test -Dtest=ClassName`.

---

### Task 1: Valid iTIP `.ics` (fix Gmail "Unable to load event")  — DONE (commit d5e1e71)

**Root cause:** `IcsBuilder` emitted `METHOD:REQUEST` with an `ORGANIZER` but no `ATTENDEE`. An iTIP REQUEST with zero attendees is invalid, so Gmail refused to render it. Fixed by adding `ATTENDEE` (the invitee), `CN` on organizer + attendee, `SEQUENCE:0`, and `STATUS:CONFIRMED`. New 9-arg `IcsBuilder.build(uid, summary, location, organizerEmail, organizerName, attendeeEmail, attendeeName, start, end)`; call site updated. Tests: `IcsBuilderTest#requestHasAttendeeAndOrganizer` + existing `IcsBuilderEscapeTest`, all green. This task is complete — do not re-implement.

---

### Task 2: Add `approvalToken` to bookings (DB + entity + service)

**Goal:** mint an unguessable per-booking token for approval-required bookings, used later as the CSRF nonce on the owner's email approve/decline links.

**Files:**
- Create: `src/main/resources/db/migration/V17__booking_approval_token.sql`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (the `book(...)` method, right after the status-assignment line ~line 180)
- Test: `src/test/java/site/asm0dey/calit/booking/ApprovalTokenTest.java`

**Interfaces:**
- Produces: `Booking.approvalToken` (nullable `String`, length 36, unique). Set to a random UUID on creation **only** when `meetingType.requiresApproval`; null otherwise. (No finder needed — the approve/decline routes look the booking up by `id` and compare the token.)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/booking/ApprovalTokenTest.java`. Copy the `seedSettings()` / `approvalType(...)` / `autoType(...)` seed helpers from the existing `ApproveDeclineTest` in the same package (admin owner is always id 1). If `ApproveDeclineTest` has no `autoType`, adapt its `approvalType` helper to set `requiresApproval = false`.

```java
package site.asm0dey.calit.booking;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.CalendarPort;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ApprovalTokenTest {

    private static final Instant SLOT = Instant.parse("2026-07-01T09:00:00Z");

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // Copy seedSettings()/approvalType()/autoType() from ApproveDeclineTest in this package.

    @Test
    @Transactional
    void approvalBookingGetsToken() {
        seedSettings();
        approvalType("approve"); // requiresApproval = true
        Booking b = bookingService.book(1L, "approve", SLOT, "Sam", "sam@example.com",
                Map.of(), "tok", "", "en");
        assertNotNull(b.approvalToken, "approval-required booking must mint an approvalToken");
    }

    @Test
    @Transactional
    void autoBookingHasNoToken() {
        seedSettings();
        autoType("auto"); // requiresApproval = false
        Booking b = bookingService.book(1L, "auto", SLOT, "Sam", "sam@example.com",
                Map.of(), "tok", "", "en");
        assertNull(b.approvalToken, "auto-confirmed booking needs no approvalToken");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ApprovalTokenTest`
Expected: COMPILE FAILURE — `Booking.approvalToken` does not exist yet.

- [ ] **Step 3: Add the migration**

Create `src/main/resources/db/migration/V17__booking_approval_token.sql`:

```sql
-- Per-booking owner approval token: unguessable nonce for the email approve/decline link.
-- Nullable: only approval-required bookings get one. Unique so it can't collide.
ALTER TABLE booking ADD COLUMN approval_token VARCHAR(36) UNIQUE;
```

- [ ] **Step 4: Add the entity field**

In `src/main/java/site/asm0dey/calit/booking/Booking.java`, after the `manageToken` field block (line 57) add:

```java
    /**
     * Feature 14 owner-approval nonce: an unguessable random UUID, set only when the meeting type
     * requires approval. Emailed to the owner inside the authenticated approve/decline links
     * ({app.base-url}/me/bookings/{id}/approve?t={approvalToken}) as a CSRF nonce. Null otherwise.
     */
    @Column(name = "approval_token", length = 36, unique = true)
    public String approvalToken;
```

- [ ] **Step 5: Mint the token in `BookingService.book`**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`, right after the line `booking.status = type.requiresApproval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;` (~line 180) add:

```java
        // Approval-required bookings carry an unguessable token used as a CSRF nonce on the owner's
        // email approve/decline links.
        if (type.requiresApproval) {
            booking.approvalToken = UUID.randomUUID().toString();
        }
```

(`java.util.UUID` is already imported — `manageToken` uses it.)

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=ApprovalTokenTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V17__booking_approval_token.sql \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/ApprovalTokenTest.java
git commit -m "feat(booking): mint per-booking approvalToken for approval-required bookings"
```

---

### Task 3: Authenticated approve/decline from email (one-click GET)

**Goal:** two authenticated one-click routes the owner reaches from email — `GET /me/bookings/{id}/approve?t={token}` and `/decline?t={token}` — that run the existing `BookingService.approve/decline` and render a result page. Unauthenticated clicks go through login and back (form auth). The token must match `booking.approvalToken`; the booking must belong to the current owner.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (1 `Templates` native method + 2 GET routes)
- Create: `src/main/resources/templates/AdminResource/approvalResult.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java` (result-page keys)
- Modify: `src/main/resources/messages/adm_de.properties`, `src/main/resources/messages/adm_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/ApprovalLinkTest.java`

**Interfaces:**
- Consumes: `requireOwnedBooking(Long)` (existing private helper, 404 if not owner), `BookingService.approve(Long)`, `BookingService.decline(Long)`, `BookingStatus.PENDING`, `pendingCount()`, `isAdmin()`, `m()` (returns `AdminMessages` for the active locale), `Booking.approvalToken` (Task 2).
- Produces: routes `GET /me/bookings/{id}/approve`, `GET /me/bookings/{id}/decline` (both take `@QueryParam("t") String token`).

- [ ] **Step 1: Add the result-page message keys to `AdminMessages`**

In `src/main/java/site/asm0dey/calit/i18n/AdminMessages.java`, near the `adm_pending_*` keys add:

```java
    // ---- Approve/decline from email — result page ----

    @Message("Booking request")
    String adm_approve_result_title();

    @Message("Booking approved")
    String adm_approve_approved_h1();

    @Message("The booking is confirmed and the invitee has been notified.")
    String adm_approve_approved_desc();

    @Message("Booking declined")
    String adm_approve_declined_h1();

    @Message("The request was declined and the invitee has been notified.")
    String adm_approve_declined_desc();

    @Message("Already handled")
    String adm_approve_gone_h1();

    @Message("This request was already approved, declined, or has expired.")
    String adm_approve_gone_desc();

    @Message("Back to pending requests")
    String adm_approve_back();
```

- [ ] **Step 2: Add German + Hebrew translations**

Append to `src/main/resources/messages/adm_de.properties`:

```properties
adm_approve_result_title=Buchungsanfrage
adm_approve_approved_h1=Buchung angenommen
adm_approve_approved_desc=Die Buchung ist bestätigt und der Eingeladene wurde benachrichtigt.
adm_approve_declined_h1=Buchung abgelehnt
adm_approve_declined_desc=Die Anfrage wurde abgelehnt und der Eingeladene wurde benachrichtigt.
adm_approve_gone_h1=Bereits bearbeitet
adm_approve_gone_desc=Diese Anfrage wurde bereits angenommen, abgelehnt oder ist abgelaufen.
adm_approve_back=Zurück zu offenen Anfragen
```

Append to `src/main/resources/messages/adm_he.properties`:

```properties
adm_approve_result_title=בקשת הזמנה
adm_approve_approved_h1=ההזמנה אושרה
adm_approve_approved_desc=ההזמנה מאושרת וההמוזמן קיבל הודעה.
adm_approve_declined_h1=ההזמנה נדחתה
adm_approve_declined_desc=הבקשה נדחתה וההמוזמן קיבל הודעה.
adm_approve_gone_h1=כבר טופל
adm_approve_gone_desc=בקשה זו כבר אושרה, נדחתה או שפג תוקפה.
adm_approve_back=חזרה לבקשות ממתינות
```

- [ ] **Step 3: Create the result template**

Create `src/main/resources/templates/AdminResource/approvalResult.html` (mirrors `pending.html`'s `adminBase` include):

```html
{@java.lang.Long pendingCount}
{@java.lang.Boolean isAdmin}
{@java.lang.String title}
{@java.lang.String h1}
{@java.lang.String desc}
{#include adminBase title=title pendingCount=pendingCount active="pending" isAdmin=isAdmin}
  <div class="card bg-base-100 border border-base-300 max-w-xl">
    <div class="card-body items-start">
      <h1 class="text-2xl font-bold">{h1}</h1>
      <p class="text-base-content/70">{desc}</p>
      <a class="btn btn-primary mt-2" href="/me/pending">{adm:adm_approve_back}</a>
    </div>
  </div>
{/include}
```

- [ ] **Step 4: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/ApprovalLinkTest.java`. Reuse this package's `/me` login helper (the one other admin tests use to get an authenticated RestAssured spec) and the booking-seed helper to create a PENDING approval booking owned by admin (id 1) and return its `id` + `approvalToken`.

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class ApprovalLinkTest {

    // Helpers: authedAdmin() -> RestAssured spec with the admin session cookie.
    //          newPendingBooking() -> creates a PENDING approval booking, returns {id, approvalToken}.

    @Test
    void unauthenticatedApproveRedirectsToLogin() {
        var b = newPendingBooking();
        given().redirects().follow(false)
                .when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    void authedApproveWithCorrectTokenConfirms() {
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking approved"));
    }

    @Test
    void authedApproveWithWrongTokenIs404() {
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/approve?t=wrong")
                .then().statusCode(404);
    }

    @Test
    void authedDeclineWithCorrectTokenDeclines() {
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/decline?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking declined"));
    }

    @Test
    void secondApproveShowsAlreadyHandled() {
        var b = newPendingBooking();
        given().spec(authedAdmin()).when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking approved"));
        given().spec(authedAdmin()).when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Already handled"));
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn test -Dtest=ApprovalLinkTest`
Expected: FAIL/404 — the GET routes don't exist yet (every authed call 404s; "Booking approved" never appears).

- [ ] **Step 6: Add the `Templates` method + GET routes in `AdminResource`**

In the `Templates` inner class of `src/main/java/site/asm0dey/calit/web/AdminResource.java` add:

```java
        public static native TemplateInstance approvalResult(
                Long pendingCount, boolean isAdmin, String title, String h1, String desc);
```

Add the two GET routes next to the existing POST `approveBooking`/`declineBooking` handlers (around line 753). They are covered by the class-level `@RolesAllowed("user")`.

```java
    @GET
    @Path("/bookings/{id}/approve")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance approveFromEmail(@PathParam("id") Long id, @QueryParam("t") String token) {
        Booking b = requireOwnedBooking(id); // 404 if not the current owner's
        if (token == null || !token.equals(b.approvalToken)) {
            // CSRF nonce mismatch (GET is not guarded by quarkus-rest-csrf) -> 404, no info leak.
            throw new jakarta.ws.rs.NotFoundException("No booking " + id);
        }
        String h1;
        String desc;
        if (b.status == site.asm0dey.calit.booking.BookingStatus.PENDING) {
            bookingService.approve(id); // PENDING -> CONFIRMED (+ Google event if connected)
            h1 = m().adm_approve_approved_h1();
            desc = m().adm_approve_approved_desc();
        } else {
            h1 = m().adm_approve_gone_h1();
            desc = m().adm_approve_gone_desc();
        }
        return Templates.approvalResult(pendingCount(), isAdmin(), m().adm_approve_result_title(), h1, desc);
    }

    @GET
    @Path("/bookings/{id}/decline")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance declineFromEmail(@PathParam("id") Long id, @QueryParam("t") String token) {
        Booking b = requireOwnedBooking(id);
        if (token == null || !token.equals(b.approvalToken)) {
            throw new jakarta.ws.rs.NotFoundException("No booking " + id);
        }
        String h1;
        String desc;
        if (b.status == site.asm0dey.calit.booking.BookingStatus.PENDING) {
            bookingService.decline(id); // PENDING -> DECLINED (frees the slot)
            h1 = m().adm_approve_declined_h1();
            desc = m().adm_approve_declined_desc();
        } else {
            h1 = m().adm_approve_gone_h1();
            desc = m().adm_approve_gone_desc();
        }
        return Templates.approvalResult(pendingCount(), isAdmin(), m().adm_approve_result_title(), h1, desc);
    }
```

(`@QueryParam` resolves via the existing `import jakarta.ws.rs.*;`.)

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn test -Dtest=ApprovalLinkTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/main/resources/templates/AdminResource/approvalResult.html \
        src/main/java/site/asm0dey/calit/i18n/AdminMessages.java \
        src/main/resources/messages/adm_de.properties \
        src/main/resources/messages/adm_he.properties \
        src/test/java/site/asm0dey/calit/web/ApprovalLinkTest.java
git commit -m "feat(booking): authenticated one-click approve/decline-from-email links (token nonce + owner scoping)"
```

---

### Task 4: Invitee cancel-confirmation page (public, token-keyed)

**Goal:** a GET confirmation page the invitee reaches from their email's "Cancel this booking" link, rendering a CSRF-protected POST form to the existing `POST /booking/{manageToken}/cancel`. (The POST handler and `Templates.cancelled(...)` already exist.)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java` (1 `Templates` native method + 1 GET route)
- Create: `src/main/resources/templates/PublicResource/cancelConfirm.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` (cancel-page `msg` keys)
- Modify: `src/main/resources/messages/msg_de.properties`, `src/main/resources/messages/msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/web/CancelConfirmTest.java`

**Interfaces:**
- Consumes: `Booking.findByManageToken(String)`, `BookingStatus.CANCELLED/DECLINED`, `Templates.cancelled(String)` (existing), `Layout.TZ_SCRIPT`, `messages.forLocale(activeLocale.current())`.
- Produces: route `GET /booking/{manageToken}/cancel` (the matching POST already exists).

- [ ] **Step 1: Add the message keys to `AppMessages`**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, near the other `pub_*` keys add:

```java
    // ---- Public — booking-summary labels + invitee cancel confirmation ----

    @Message("Meeting:")
    String pub_booking_meeting_label();

    @Message("When:")
    String pub_booking_when_label();

    @Message("Cancel booking")
    String pub_cancel_confirm_title();

    @Message("Cancel this booking?")
    String pub_cancel_confirm_h1();

    @Message("This frees the slot and notifies everyone. It can't be undone.")
    String pub_cancel_confirm_desc();

    @Message("Confirm cancellation")
    String pub_cancel_confirm_btn();

    @Message("Keep booking")
    String pub_cancel_keep_btn();
```

- [ ] **Step 2: Add German + Hebrew translations**

Append to `src/main/resources/messages/msg_de.properties`:

```properties
pub_booking_meeting_label=Termin:
pub_booking_when_label=Wann:
pub_cancel_confirm_title=Buchung stornieren
pub_cancel_confirm_h1=Diese Buchung stornieren?
pub_cancel_confirm_desc=Dadurch wird der Termin freigegeben und alle Beteiligten benachrichtigt. Dies kann nicht rückgängig gemacht werden.
pub_cancel_confirm_btn=Stornierung bestätigen
pub_cancel_keep_btn=Buchung behalten
```

Append to `src/main/resources/messages/msg_he.properties`:

```properties
pub_booking_meeting_label=פגישה:
pub_booking_when_label=מתי:
pub_cancel_confirm_title=ביטול הזמנה
pub_cancel_confirm_h1=לבטל הזמנה זו?
pub_cancel_confirm_desc=פעולה זו תפנה את המועד ותודיע לכולם. לא ניתן לבטל אותה.
pub_cancel_confirm_btn=אשר ביטול
pub_cancel_keep_btn=השאר הזמנה
```

- [ ] **Step 3: Create the template**

Create `src/main/resources/templates/PublicResource/cancelConfirm.html`:

```html
{@java.lang.String title}
{@site.asm0dey.calit.booking.Booking booking}
{@site.asm0dey.calit.domain.MeetingType type}
{@java.lang.String tzScript}
{#include base title=title}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <div class="card-body items-start gap-3">
      <h1 class="text-2xl font-bold">{msg:pub_cancel_confirm_h1}</h1>
      <p class="text-base-content/70">{msg:pub_cancel_confirm_desc}</p>
      <ul class="list-disc ms-5">
        <li><strong>{msg:pub_booking_meeting_label}</strong> {type.name}</li>
        <li><strong>{msg:pub_booking_when_label}</strong> <time data-utc="{booking.startUtc}">{booking.startUtc} UTC</time></li>
      </ul>
      <div class="flex gap-2">
        <form method="post" action="/booking/{booking.manageToken}/cancel"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button type="submit" class="btn btn-error">{msg:pub_cancel_confirm_btn}</button></form>
        <a class="btn btn-ghost" href="/booking/{booking.manageToken}/manage">{msg:pub_cancel_keep_btn}</a>
      </div>
    </div>
  </div>
  {tzScript.raw}
{/include}
```

- [ ] **Step 4: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/CancelConfirmTest.java`. Seed a confirmed booking and return its `manageToken` (reuse the seed pattern other public tests use).

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CancelConfirmTest {

    // Helper: newConfirmedBooking() -> creates a CONFIRMED booking, returns its manageToken.

    @Test
    void cancelConfirmPageShowsConfirmForm() {
        String manageToken = newConfirmedBooking();
        given().when().get("/booking/" + manageToken + "/cancel")
                .then().statusCode(200)
                .body(containsString("/booking/" + manageToken + "/cancel")) // the POST form action
                .body(containsString("Confirm cancellation"));
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/cancel")
                .then().statusCode(404);
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn test -Dtest=CancelConfirmTest`
Expected: FAIL — no GET `/booking/{manageToken}/cancel` route yet (only POST exists; GET returns 405 or the wrong handler).

- [ ] **Step 6: Add the `Templates` method + GET route in `PublicResource`**

In the `Templates` inner class of `src/main/java/site/asm0dey/calit/web/PublicResource.java` add:

```java
        public static native TemplateInstance cancelConfirm(
                String title, site.asm0dey.calit.booking.Booking booking,
                site.asm0dey.calit.domain.MeetingType type, String tzScript);
```

Add the GET route next to the existing `/booking/{manageToken}/manage` and POST `/booking/{manageToken}/cancel` handlers. `messages`, `activeLocale` are already injected.

```java
    @GET
    @Path("/booking/{manageToken}/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelConfirmPage(@PathParam("manageToken") String manageToken) {
        var m = messages.forLocale(activeLocale.current());
        Booking booking = Booking.findByManageToken(manageToken); // unguessable key, not id
        if (booking == null) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        if (booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.DECLINED) {
            // Already gone -> the same terminal page the POST cancel renders.
            return Templates.cancelled(m.pub_cancelled_title());
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return Templates.cancelConfirm(m.pub_cancel_confirm_title(), booking, type, Layout.TZ_SCRIPT);
    }
```

If `BookingStatus` is not already imported, the package wildcard `import site.asm0dey.calit.booking.*;` covers it; otherwise add `import site.asm0dey.calit.booking.BookingStatus;`.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn test -Dtest=CancelConfirmTest`
Expected: PASS.

- [ ] **Step 8: Verify CSRF enforcement still holds**

Run: `mvn test -Dtest=CsrfEnforcementTest`
Expected: PASS — the new cancel form carries `{inject:csrf.token}`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/resources/templates/PublicResource/cancelConfirm.html \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/web/CancelConfirmTest.java
git commit -m "feat(booking): invitee cancel-confirmation page (public, token-keyed) behind the email cancel link"
```

---

### Task 5: Role-asymmetric email copy + links

**Root cause:** every booking template greets `inviteeName`, renders one invitee-worded body line for both recipients, and links the **invitee's** manage page even on the owner copy. `EmailService` passes the same `inviteeName`/`manageUrl` to both roles. Fix: pass role-aware data (`greetingName`, `approveUrl`, `declineUrl`, `cancelUrl`) and branch each template on the already-passed `recipientRole`:
- **Owner copy:** greeted by owner name; body names the invitee ("Sam booked a meeting with you"); for a pending request, **Approve** + **Decline** links (to the Task 3 routes); no invitee manage/cancel links.
- **Invitee copy:** greeted by invitee name; current invitee-worded body; **Manage** + **Cancel** links on active bookings.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Modify all six booking templates: `requested.html`, `confirmation.html`, `declined.html`, `reschedule.html`, `cancellation.html`, `reminder.html`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` (owner body variants + 3 link texts)
- Modify: `src/main/resources/messages/msg_de.properties`, `src/main/resources/messages/msg_he.properties`
- Test: `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`

**Interfaces:**
- Consumes: `OwnerSettings.ownerName`, `Booking.inviteeName`, `Booking.id`, `Booking.manageToken`, `Booking.approvalToken`, `baseUrl`, `recipientRole` (already a template var).
- Produces: `EmailService` constants `GREETING_NAME="greetingName"`, `APPROVE_URL="approveUrl"`, `DECLINE_URL="declineUrl"`, `CANCEL_URL="cancelUrl"`; template vars `greetingName`, `approveUrl`, `declineUrl`, `cancelUrl`; message keys `email_*_body_owner(name)` (6), `email_body_cancel_link_text`, `email_body_approve_link_text`, `email_body_decline_link_text`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java`:

```java
package site.asm0dey.calit.email;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmailRoleCopyTest {

    @Inject @Location("email/requested.html") Template requested;
    @Inject @Location("email/confirmation.html") Template confirmation;

    private static TemplateInstance base(Template t, String role) {
        return t.instance().setLocale(Locale.ENGLISH)
                .data("lang", "en")
                .data("recipientRole", role)
                .data("recipientRoleDisplay", role)
                .data("greetingName", "invitee".equals(role) ? "Sam Invitee" : "Olivia Owner")
                .data("inviteeName", "Sam Invitee")
                .data("meetingTypeName", "Intro call")
                .data("startTime", "Wed, 1 Jul 2026, 09:00")
                .data("oldStartTime", "Tue, 30 Jun 2026, 09:00")
                .data("durationMinutes", 30)
                .data("location", null)
                .data("isMeetLink", false)
                .data("manageUrl", "https://calit.example/booking/tok/manage")
                .data("cancelUrl", "https://calit.example/booking/tok/cancel")
                .data("approveUrl", "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/approve?t=abc")
                .data("declineUrl", "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/decline?t=abc")
                .data("answers", List.of());
    }

    @Test
    void requestedOwnerCopyGreetsOwnerNamesInviteeAndLinksApproveDecline() {
        String body = base(requested, "owner").render();
        assertTrue(body.contains("Hi Olivia Owner,"), "owner greeted by name");
        assertTrue(body.contains("Sam Invitee requested"), "owner body names the invitee");
        assertTrue(body.contains("/me/bookings/42/approve?t=abc"), "owner gets the approve link");
        assertTrue(body.contains("/me/bookings/42/decline?t=abc"), "owner gets the decline link");
        assertFalse(body.contains("/booking/tok/cancel"), "owner copy has no invitee cancel link");
    }

    @Test
    void requestedInviteeCopyGreetsInviteeAndLinksManageAndCancel() {
        String body = base(requested, "invitee").render();
        assertTrue(body.contains("Hi Sam Invitee,"), "invitee greeted by name");
        assertTrue(body.contains("/booking/tok/manage"), "invitee gets the manage link");
        assertTrue(body.contains("/booking/tok/cancel"), "invitee gets the cancel link");
        assertFalse(body.contains("/approve"), "invitee copy has no approve link");
    }

    @Test
    void confirmationOwnerCopyNamesInviteeNoManageLink() {
        String body = base(confirmation, "owner").render();
        assertTrue(body.contains("Sam Invitee booked"), "owner body names the invitee");
        assertFalse(body.contains("/manage"), "owner copy has no invitee manage link");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EmailRoleCopyTest`
Expected: FAIL — templates still render the shared invitee-worded body and the same links for both roles.

- [ ] **Step 3: Add the owner body + link message keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, add near the email body keys:

```java
    // ---- Email body — owner-copy variants (name the invitee) ----

    @Message("{name} requested a booking. Review it to approve or decline.")
    String email_requested_body_owner(String name);

    @Message("{name} booked a meeting with you.")
    String email_confirmation_body_owner(String name);

    @Message("You declined {name}'s booking request.")
    String email_declined_body_owner(String name);

    @Message("{name} rescheduled their booking.")
    String email_reschedule_body_owner(String name);

    @Message("{name}'s booking was cancelled.")
    String email_cancellation_body_owner(String name);

    @Message("Reminder: upcoming meeting with {name}.")
    String email_reminder_body_owner(String name);

    @Message("Cancel this booking")
    String email_body_cancel_link_text();

    @Message("Approve")
    String email_body_approve_link_text();

    @Message("Decline")
    String email_body_decline_link_text();
```

- [ ] **Step 4: Add German + Hebrew translations**

Append to `src/main/resources/messages/msg_de.properties`:

```properties
email_requested_body_owner={name} hat eine Buchung angefragt. Bitte annehmen oder ablehnen.
email_confirmation_body_owner={name} hat einen Termin mit Ihnen gebucht.
email_declined_body_owner=Sie haben die Buchungsanfrage von {name} abgelehnt.
email_reschedule_body_owner={name} hat die Buchung verschoben.
email_cancellation_body_owner=Die Buchung von {name} wurde storniert.
email_reminder_body_owner=Erinnerung: bevorstehender Termin mit {name}.
email_body_cancel_link_text=Diese Buchung stornieren
email_body_approve_link_text=Annehmen
email_body_decline_link_text=Ablehnen
```

Append to `src/main/resources/messages/msg_he.properties`:

```properties
email_requested_body_owner={name} ביקש/ה הזמנה. אשר או דחה.
email_confirmation_body_owner={name} קבע/ה איתך פגישה.
email_declined_body_owner=דחית את בקשת ההזמנה של {name}.
email_reschedule_body_owner={name} שינה/תה את מועד ההזמנה.
email_cancellation_body_owner=ההזמנה של {name} בוטלה.
email_reminder_body_owner=תזכורת: פגישה קרובה עם {name}.
email_body_cancel_link_text=בטל הזמנה זו
email_body_approve_link_text=אשר
email_body_decline_link_text=דחה
```

- [ ] **Step 5: Add `EmailService` data plumbing**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`, add constants after `ANSWERS` (line 47):

```java
    /** Role-aware greeting name: invitee name for the invitee copy, owner name for the owner copy. */
    public static final String GREETING_NAME = "greetingName";
    /** Owner-only authenticated approve/decline links (requested email); null for the invitee copy. */
    public static final String APPROVE_URL = "approveUrl";
    public static final String DECLINE_URL = "declineUrl";
    /** Invitee cancel-confirmation link. */
    public static final String CANCEL_URL = "cancelUrl";
```

Add helpers next to `manageUrl(...)` (line 454):

```java
    /** Owner authenticated approve link with the token nonce; null when no approval token exists. */
    private String approveUrl(Booking b) {
        return b.approvalToken == null ? null : baseUrl + "/me/bookings/" + b.id + "/approve?t=" + b.approvalToken;
    }

    private String declineUrl(Booking b) {
        return b.approvalToken == null ? null : baseUrl + "/me/bookings/" + b.id + "/decline?t=" + b.approvalToken;
    }

    private String cancelUrl(Booking b) {
        return baseUrl + "/booking/" + b.manageToken + "/cancel";
    }
```

In **every** per-role body lambda, after the existing `.data(INVITEE_NAME, l.booking.inviteeName)` line add the greeting (all 7 sites: `handleRequested`, `handleConfirmed`, `handleApproved`, `deliverDeclined`, `handleRescheduled`, `handleCancelled`, `deliverReminder`):

```java
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
```

On the **active-booking** sites that already pass `MANAGE_URL` (`handleRequested`, `handleConfirmed`, `handleApproved`, `handleRescheduled`, `deliverReminder`), add the cancel link after the `.data(MANAGE_URL, manageUrl(l.booking))` line:

```java
                            .data(CANCEL_URL, cancelUrl(l.booking))
```

In `handleRequested` only, after that `.data(CANCEL_URL, ...)` line, add the owner approve/decline links:

```java
                            .data(APPROVE_URL, approveUrl(l.booking))
                            .data(DECLINE_URL, declineUrl(l.booking))
```

(`deliverDeclined` and `handleCancelled` keep their current data — their templates render no links.)

- [ ] **Step 6: Rewrite `requested.html`**

Replace `src/main/resources/templates/email/requested.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
{@java.lang.String approveUrl}
{@java.lang.String declineUrl}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_requested_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_requested_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_requested_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_requested_time_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#if location}
    {#if isMeetLink}<li><strong>{msg:email_body_meet_label}</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>{msg:email_body_location_label}</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>{msg:email_body_your_answers_label}</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
{#if recipientRole == 'owner'}
  {#if approveUrl}<p><a href="{approveUrl}">{msg:email_body_approve_link_text}</a> &middot; <a href="{declineUrl}">{msg:email_body_decline_link_text}</a></p>{/if}
{#else}
  <p><a href="{manageUrl}">{msg:email_body_manage_link_text}</a></p>
  <p><a href="{cancelUrl}">{msg:email_body_cancel_link_text}</a></p>
{/if}
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

- [ ] **Step 7: Rewrite `confirmation.html`**

Replace `src/main/resources/templates/email/confirmation.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_confirmation_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_confirmation_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_confirmation_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#if location}
    {#if isMeetLink}<li><strong>{msg:email_body_meet_label}</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>{msg:email_body_location_label}</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>{msg:email_body_your_answers_label}</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
{#if recipientRole != 'owner'}
  <p><a href="{manageUrl}">{msg:email_body_manage_link_text}</a></p>
  <p><a href="{cancelUrl}">{msg:email_body_cancel_link_text}</a></p>
{/if}
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

(This template also serves the `approved` email — `handleApproved` renders `confirmation.html`. "{name} booked a meeting with you." is correct for the owner-approved copy.)

- [ ] **Step 8: Rewrite `reschedule.html`**

Replace `src/main/resources/templates/email/reschedule.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_reschedule_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_reschedule_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_reschedule_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_reschedule_previous_time}</strong> {oldStartTime}</li>
  <li><strong>{msg:email_reschedule_new_time}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#if location}
    {#if isMeetLink}<li><strong>{msg:email_body_meet_label}</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>{msg:email_body_location_label}</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>{msg:email_body_your_answers_label}</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
{#if recipientRole != 'owner'}
  <p><a href="{manageUrl}">{msg:email_body_manage_link_text}</a></p>
  <p><a href="{cancelUrl}">{msg:email_body_cancel_link_text}</a></p>
{/if}
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

- [ ] **Step 9: Rewrite `reminder.html`**

Replace `src/main/resources/templates/email/reminder.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_reminder_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_reminder_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_reminder_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#if location}
    {#if isMeetLink}<li><strong>{msg:email_body_meet_label}</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>{msg:email_body_location_label}</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>{msg:email_body_your_answers_label}</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
{#if recipientRole != 'owner'}
  <p><a href="{manageUrl}">{msg:email_body_manage_link_text}</a></p>
  <p><a href="{cancelUrl}">{msg:email_body_cancel_link_text}</a></p>
{/if}
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

- [ ] **Step 10: Rewrite `declined.html` (greeting + owner body; no links)**

Replace `src/main/resources/templates/email/declined.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_declined_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_declined_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_declined_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_requested_time_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
</ul>
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

- [ ] **Step 11: Rewrite `cancellation.html` (greeting + owner body; no links)**

Replace `src/main/resources/templates/email/cancellation.html` with:

```html
{@java.lang.String lang}
{@java.lang.String recipientRole}
<!DOCTYPE html>
<html lang="{lang}" dir="{#if lang == 'he'}rtl{#else}ltr{/if}">
<head><title>{msg:email_cancellation_title}</title></head>
<body>
<p>{msg:email_body_greeting(greetingName)}</p>
{#if recipientRole == 'owner'}
<p><strong>{msg:email_cancellation_body_owner(inviteeName)}</strong></p>
{#else}
<p><strong>{msg:email_cancellation_body}</strong></p>
{/if}
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_cancellation_was_scheduled}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
</ul>
<p>{msg:email_body_recipient_note(recipientRoleDisplay)}</p>
</body>
</html>
```

- [ ] **Step 12: Run the role-copy test**

Run: `mvn test -Dtest=EmailRoleCopyTest`
Expected: PASS.

- [ ] **Step 13: Run the email suite for template-var regressions**

Run: `mvn test -Dtest='*Email*,IcsBuilderTest'`
Expected: PASS. (Qute fails the build if a template references a variable never passed — catches a missed `.data(...)`.)

- [ ] **Step 14: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/main/resources/templates/email/requested.html \
        src/main/resources/templates/email/confirmation.html \
        src/main/resources/templates/email/reschedule.html \
        src/main/resources/templates/email/reminder.html \
        src/main/resources/templates/email/declined.html \
        src/main/resources/templates/email/cancellation.html \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties \
        src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/email/EmailRoleCopyTest.java
git commit -m "feat(email): role-specific copy + links (owner names invitee & gets approve/decline; invitee gets manage+cancel)"
```

---

### Task 6: Apply locale change immediately in settings

**Root cause:** `LocaleResolutionFilter` (priority 5100) resolves `ActiveLocale` from the DB **before** the `POST /me/settings` handler runs. The handler saves the new locale, then re-renders using the already-set (old) `ActiveLocale` — so the title, the `{adm:...}` keys, and the language-dropdown "selected" marker all show the old language until a fresh request reloads them. Fix: after saving, set `ActiveLocale` to the just-saved locale so the same response renders correctly.

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (`updateSettings`, lines 600-620)
- Test: `src/test/java/site/asm0dey/calit/web/SettingsLocaleTest.java`

**Interfaces:**
- Consumes: `ActiveLocale.set(Locale)` (already injected as `activeLocale`), `AppLocales.pick(String)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/site/asm0dey/calit/web/SettingsLocaleTest.java`. Authenticate as the admin (owner id 1) using this package's existing `/me` login helper, POST a German locale, and assert the **same** response is already German (`<option value="de" selected>`):

```java
package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SettingsLocaleTest {

    // Reuse the existing /me login helper used by other admin tests to get an authenticated spec.

    @Test
    void changingLocaleAppliesInSameResponse() {
        given().spec(authedAdmin())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .formParam("ownerNotificationsEnabled", "on")
                .when().post("/me/settings")
                .then().statusCode(200)
                .body(containsString("value=\"de\" selected"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SettingsLocaleTest`
Expected: FAIL — response renders with the pre-save (English) locale, so `value="de"` is not marked `selected`.

- [ ] **Step 3: Refresh `ActiveLocale` after the save**

In `src/main/java/site/asm0dey/calit/web/AdminResource.java`, in `updateSettings`, after `s.persist();` and before the `return Templates.settings(...)` line, add:

```java
        // The locale filter already ran (before this handler) with the OLD value; refresh the
        // request-scoped locale so THIS response (title, {adm:} keys, language dropdown) is in the new language.
        activeLocale.set(site.asm0dey.calit.i18n.AppLocales.pick(s.locale));
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SettingsLocaleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/web/AdminResource.java \
        src/test/java/site/asm0dey/calit/web/SettingsLocaleTest.java
git commit -m "fix(i18n): apply the new admin locale in the same settings response"
```

---

### Task 7: Full suite + docs

**Files:**
- Modify (on `docs-site` branch): usage/booking docs + `docs-site/src/content/docs/releases/changelog.md`

- [ ] **Step 1: Run the whole suite**

Run: `mvn test`
Expected: PASS (all existing tests plus the new ones). Docker must be running.

- [ ] **Step 2: Manual smoke (optional, dev server)**

```bash
bun run css:build
mvn quarkus:dev -Dgoogle-oauth-client-id=x -Dgoogle-oauth-client-secret=x
```
- Create an approval-required meeting type, book it as a visitor.
- Owner email: greeted by owner name, body names the invitee, has **Approve** / **Decline** links. Click Approve while logged out → login → lands back on the link → "Booking approved"; booking CONFIRMED in `/me/pending`.
- Invitee email: greeted by invitee name, has **Manage** + **Cancel** links → cancel link → confirmation page → confirm → cancelled.
- Open the `.ics` in Gmail → the event card renders (no "Unable to load event").
- `/me/settings` → change language to Deutsch → page is German immediately and the dropdown shows "Deutsch" selected.

- [ ] **Step 3: Update docs on the `docs-site` branch**

Per CLAUDE.md, user-facing changes land on `docs-site` in the same effort. Document: owners approve/decline approval-required bookings via one-click links in the request email (they log in first; the link works until the request is handled or expires); invitees cancel via a link in their email. Add a changelog entry at the top of `docs-site/src/content/docs/releases/changelog.md` summarizing: valid iTIP `.ics` (Gmail event card), role-specific owner/invitee emails, approve/decline & cancel from email, and immediate locale switching. Commit on `docs-site`.

---

## Self-Review

**Spec coverage:**
1. Gmail "Unable to load event" → Task 1 (DONE). ✓
2. Emails contain invitee name not owner → Task 5 (role-aware greeting + owner body names the invitee + owner-relevant links). ✓
3. Owner can't approve from email → Tasks 2, 3, 5 (`approvalToken` nonce, authenticated one-click `/me/bookings/{id}/approve|decline?t=` routes, owner email links). ✓
4. Locale not applied immediately → Task 6 (refresh `ActiveLocale` after save). ✓
5. (User add) Check all messages for owner/invitee asymmetry → Task 5 rewrites all six booking templates with per-role body + per-role links. ✓
6. (User add) Cancellation link in the letter → Task 4 (public token cancel-confirm page) + Task 5 (invitee `cancelUrl` link on active-booking emails). ✓
7. (User add) Approval gated behind authentication, one-click for both approve and decline, redirect through login → Task 3 (auth-gated GET routes; form auth redirect-back is the Quarkus default via the `quarkus-redirect-location` cookie). ✓

**Type consistency:** `Booking.approvalToken` defined Task 2, consumed Tasks 3 (handler token check) and 5 (link building). `requireOwnedBooking`, `BookingService.approve/decline`, `pendingCount()`, `isAdmin()`, `m()` (AdminMessages), `Templates.pending`/`cancelled`, `Booking.findByManageToken`, `Layout.TZ_SCRIPT` are pre-existing (verified). `GREETING_NAME`/`APPROVE_URL`/`DECLINE_URL`/`CANCEL_URL` + template vars `greetingName`/`approveUrl`/`declineUrl`/`cancelUrl` defined Task 5 Java side and consumed Task 5 templates. `recipientRole` is pre-existing (passed at every render site). Message keys are added to the interface + `_de` + `_he` together (`adm_*` for the result page, `msg_*` for emails/public pages).

**Link/data coverage (Task 5):** `manageUrl` + `cancelUrl` are referenced only inside the invitee branch of `requested/confirmation/reschedule/reminder`, and `EmailService` passes both at exactly those render sites. `approveUrl`/`declineUrl` are referenced only in `requested.html` and passed only by `handleRequested`. `declined.html`/`cancellation.html` reference no link vars and their sites pass none. `approved` reuses `confirmation.html` and `handleApproved` passes `manageUrl`+`cancelUrl`. This satisfies the Qute "every referenced var must be passed" rule.

**Security review (Task 3):** GET that mutates is acceptable here because (a) the route is behind `@RolesAllowed("user")` — unauthenticated callers (mail prefetch, link scanners) only ever reach `/login`; (b) `requireOwnedBooking` blocks cross-owner action; (c) the `approvalToken` query param is an unguessable nonce checked against `booking.approvalToken`, defeating a tricked top-level navigation, and `SameSite=Lax` already prevents embedded cross-site GETs from sending the session cookie. quarkus-rest-csrf intentionally does not cover GET, which is why the nonce is required.

**Placeholder scan:** no TBD/TODO; every code step shows complete code. Test seed/login helpers are explicitly directed to be copied from named existing classes (`ApproveDeclineTest`, this package's `/me` login helper) — these exist in the codebase.

---

## Execution

In progress via superpowers:subagent-driven-development. Task 1 complete (commit d5e1e71). Continue at Task 2.
