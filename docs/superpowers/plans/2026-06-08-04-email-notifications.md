# Plan 4 — Email Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Email the right participants on every booking lifecycle transition. The booking lifecycle is owned by Plan 3, which fires CDI events on each transition; Plan 6 fires a reminder event. This plan adds nothing to the booking flow itself: it only *observes* those events and sends mail. No new schema, no Flyway migration.

**The authoritative email model (read this first — everything below implements it):**

- **INVITEE emails** (confirmed / approved / rescheduled / cancelled / reminder) are sent by the app **only as a fallback when Google is NOT connected** (`!CalendarPort.isConnected()`). When Google is connected, Google itself emails the invitee — Plan 2 calls `createEvent` / `updateEvent` / `deleteEvent` with `sendUpdates=all`, so the calendar invite, update, and cancellation reach the invitee from Google. Sending an app email too would be a duplicate, so we suppress it.
- **Two always-send exceptions to the invitee** (Google has **no event** in these states, so it cannot notify anyone): **`BookingRequested`** (PENDING — approval-type booking, no Google event created yet) and **`BookingDeclined`** (PENDING→DECLINED — the event never existed). For these two kinds the invitee **always** gets an app email regardless of `isConnected()`.
- **OWNER emails** are sent by the app for **every** booking event (all six kinds + reminder), **always**, but gated by the opt-out flag `OwnerSettings.ownerNotificationsEnabled` (default `true`). When `ownerNotificationsEnabled == false`, the owner gets nothing. (When Google is connected the owner — as calendar organizer — *also* gets Google's organizer notifications; this redundancy is accepted, because the owner email carries app-specific context like the answers and is the only owner notification in degraded/no-Google mode.)
- **Every app-sent email carries an `.ics` calendar attachment** (a `VEVENT`: start/end, summary, location/Meet link if any, organizer) so the recipient can add it to any calendar. We build the ICS by hand as VCALENDAR text (full code in Task 2).

The decision table the code implements:

| Event | Invitee gets app mail? | Owner gets app mail? |
|---|---|---|
| `BookingRequested` (PENDING) | **Always** (no Google event exists) | Always, if `ownerNotificationsEnabled` |
| `BookingDeclined` | **Always** (no Google event exists) | Always, if `ownerNotificationsEnabled` |
| `BookingConfirmed` | Only if `!isConnected()` | Always, if `ownerNotificationsEnabled` |
| `BookingApproved` | Only if `!isConnected()` | Always, if `ownerNotificationsEnabled` |
| `BookingRescheduled` | Only if `!isConnected()` | Always, if `ownerNotificationsEnabled` |
| `BookingCancelled` | Only if `!isConnected()` | Always, if `ownerNotificationsEnabled` |
| `ReminderDue` | Only if `!isConnected()` | Always, if `ownerNotificationsEnabled` |

**Architecture:** A single `@ApplicationScoped` `EmailService` declares one CDI observer method per event (`BookingRequested`, `BookingConfirmed`, `BookingApproved`, `BookingDeclined`, `BookingRescheduled`, `BookingCancelled`, `ReminderDue`). Each observer is wired with `@Observes(during = TransactionPhase.AFTER_SUCCESS)` so mail is sent only after the booking's own transaction has committed — never on a transaction that later rolls back. Because `AFTER_SUCCESS` observers run *after* the original transaction has ended, there is no active transaction/persistence context when they fire, so loading the `Booking` (plus its `MeetingType`, the `OwnerSettings` singleton, and `BookingField.formFor(...)`) is done inside a fresh transaction opened explicitly with `QuarkusTransaction.requiringNew()`. Recipient selection (invitee fallback vs. always-send; owner opt-out) is decided per event kind using `CalendarPort.isConnected()` and `OwnerSettings.ownerNotificationsEnabled`. Subject and body are rendered with injected Qute `Template`s; all times are formatted in the owner's IANA timezone (`OwnerSettings.timezone`). Bodies are sent via the programmatic `Mailer` API (`Mailer.send(Mail.withHtml(...))` plus an inline `.ics` attachment) for maximum testability with the `MockMailbox`.

**Tech Stack:** Java 25, Quarkus 3.35.3, `quarkus-mailer`, Qute (already on the classpath transitively via mailer; we depend on it explicitly), `QuarkusTransaction`, JUnit 5, `io.quarkus.test.junit.mockito.InjectMock`. DB-touching tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**. Mail is verified with the in-memory `MockMailbox` (no real SMTP in dev/test).

**Consumes from earlier plans (exact contract — do not redefine):**
- Plan 3 `com.calit.booking.Booking` (Panache entity): `Long id`, `Long meetingTypeId`, `String inviteeName`, `String inviteeEmail`, `Instant startUtc`, `Instant endUtc`, `String googleEventId` (nullable), `String meetLink` (nullable), `BookingStatus status` (`PENDING`/`CONFIRMED`/`CANCELLED`/`DECLINED`), `Instant createdAt`, **`Map<String,String> answers`** (JSONB — custom field values keyed by `BookingField.fieldKey`), **`String manageToken`** (UUID, unique — invitee manage/reschedule/cancel key); static `Booking.findById(id)`.
- Plan 3 CDI events in `com.calit.booking.events`, all Java records carrying `Long bookingId`: `BookingRequested(Long bookingId)`, `BookingConfirmed(Long bookingId)`, `BookingApproved(Long bookingId)`, `BookingDeclined(Long bookingId)`, `BookingRescheduled(Long bookingId, Instant oldStartUtc)`, `BookingCancelled(Long bookingId)`.
- Plan 6 CDI event in `com.calit.booking.events`: `ReminderDue(Long bookingId)` — fired by the reminder-send tick. (Plan 6 owns the firing; Plan 4 only observes. Listed here as the consumed contract.)
- Plan 2 `com.calit.google.CalendarPort`: `boolean isConnected()`. Injected into `EmailService` to decide the invitee-fallback branch. (We call **no** other `CalendarPort` method — Plan 3 already did the Google writes; we only read connection state.)
- Plan 1 `com.calit.domain.OwnerSettings`: `ownerEmail`, `ownerName`, `timezone`, **`ownerNotificationsEnabled`** (boolean, default true — Plan 1b); static `OwnerSettings.get()`, `SINGLETON_ID`.
- Plan 1 / 1b `com.calit.domain.MeetingType`: `name`, `durationMinutes`, **`locationType`** (`LocationType` enum: `GOOGLE_MEET`/`PHONE`/`IN_PERSON`/`CUSTOM`), **`locationDetail`** (String, nullable); static `MeetingType.findById(id)`.
- Plan 1 `com.calit.domain.BookingField`: per-field `String fieldKey`, `String label`, `int position`; static **`BookingField.formFor(Long meetingTypeId)`** returns the resolved field definitions (per-type if any exist, else global), **ordered by `position`**. Used to render `Booking.answers` with human labels in order.

**Config consumed:** `app.base-url` (e.g. `https://book.example.com`) — the public origin used to build the invitee **manage link** `{base-url}/booking/{manageToken}/manage`. Added in Task 1.

**New package:** `com.calit.email`.

---

### Task 1: Add the mailer dependency + mailer config + base URL

**Files:**
- Edit: `pom.xml`
- Edit: `src/main/resources/application.properties`

- [ ] **Step 1: Add `quarkus-mailer` and `quarkus-qute` to `pom.xml`**

In `pom.xml`, inside the existing `<dependencies>` block (after the `quarkus-arc` dependency), add:

```xml
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-mailer</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-qute</artifactId></dependency>
```

> `quarkus-mailer` pulls Qute transitively, but we declare `quarkus-qute` explicitly because we inject `io.quarkus.qute.Template` directly. The `MockMailbox` ships inside `quarkus-mailer` and is available in tests with no extra dependency. `quarkus-junit5-mockito` (for `@InjectMock` on `CalendarPort`) is already on the test classpath from earlier plans; if not, add `<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit5-mockito</artifactId><scope>test</scope></dependency>`.

- [ ] **Step 2: Add mailer config + base URL to `src/main/resources/application.properties`**

Append to `src/main/resources/application.properties`:

```properties
# --- Mailer (Plan 4) ---
# Default "from" address for all outgoing mail.
quarkus.mailer.from=${MAIL_FROM:calit@example.com}

# Public origin used to build invitee manage links in emails.
app.base-url=${APP_BASE_URL:http://localhost:8080}

# In dev & test the mock mailbox captures mail instead of hitting SMTP.
%dev.quarkus.mailer.mock=true
%test.quarkus.mailer.mock=true

# Production SMTP — all values via env.
%prod.quarkus.mailer.mock=false
%prod.quarkus.mailer.from=${MAIL_FROM}
%prod.quarkus.mailer.host=${MAIL_HOST}
%prod.quarkus.mailer.port=${MAIL_PORT:587}
%prod.quarkus.mailer.username=${MAIL_USERNAME}
%prod.quarkus.mailer.password=${MAIL_PASSWORD}
%prod.quarkus.mailer.start-tls=${MAIL_START_TLS:REQUIRED}
%prod.app.base-url=${APP_BASE_URL}
```

> `quarkus.mailer.mock=true` makes the framework store messages in the `MockMailbox` instead of sending them. `app.base-url` is injected into `EmailService` with `@ConfigProperty` and used to build the manage link `{base-url}/booking/{manageToken}/manage`.

- [ ] **Step 3: Confirm the project still builds & existing tests pass**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. The new dependencies resolve; no behavior change yet.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "chore: add quarkus-mailer dependency, mailer config and app.base-url"
```

---

### Task 2: `.ics` builder helper + tests

**Files:**
- Create: `src/main/java/com/calit/email/IcsBuilder.java`
- Test: `src/test/java/com/calit/email/IcsBuilderTest.java`

The helper builds a single-`VEVENT` VCALENDAR string for one booking. It takes the booking's start/end (`Instant`), the summary (meeting type name), an optional location string (the Meet link or `locationDetail`, resolved by the caller per `locationType`), the organizer email (the owner), and a UID (we use the booking's `manageToken` so updates carry a stable UID). Times are emitted as UTC (`...Z`) per RFC 5545. The string is attached to every app-sent mail as `text/calendar`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/email/IcsBuilderTest.java`:

```java
package com.calit.email;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsBuilderTest {

    @Test
    void buildsVeventWithStartEndSummaryLocationOrganizerUid() {
        String ics = IcsBuilder.build(
                "tok-123",
                "Discovery Call",
                "https://meet.google.com/abc-defg-hij",
                "owner@example.com",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"), "must be a VCALENDAR");
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(ics.contains("END:VEVENT"));
        assertTrue(ics.contains("END:VCALENDAR"));
        assertTrue(ics.contains("UID:tok-123"), "uid drives calendar de-dup/updates");
        assertTrue(ics.contains("SUMMARY:Discovery Call"));
        assertTrue(ics.contains("LOCATION:https://meet.google.com/abc-defg-hij"));
        assertTrue(ics.contains("ORGANIZER:mailto:owner@example.com"));
        assertTrue(ics.contains("DTSTART:20260608T090000Z"), "start in UTC basic format");
        assertTrue(ics.contains("DTEND:20260608T093000Z"), "end in UTC basic format");
    }

    @Test
    void omitsLocationLineWhenNull() {
        String ics = IcsBuilder.build(
                "tok-x", "Phone Call", null, "owner@example.com",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(!ics.contains("LOCATION:"), "no LOCATION line when location is null/blank");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=IcsBuilderTest`
Expected: FAIL — compilation error, `IcsBuilder` does not exist.

- [ ] **Step 3: Write `IcsBuilder`**

`src/main/java/com/calit/email/IcsBuilder.java`:

```java
package com.calit.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hand-rolled RFC 5545 VCALENDAR/VEVENT builder for a single booking.
 * Emitted as an .ics attachment on every app-sent email so the recipient
 * can add the meeting to any calendar (independent of Google).
 */
public final class IcsBuilder {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {
    }

    /**
     * @param uid          stable unique id (we pass the booking's manageToken so updates match)
     * @param summary      event title (the meeting type name)
     * @param location     Meet link or locationDetail; null/blank emits no LOCATION line
     * @param organizerEmail the owner's email
     * @param start        meeting start (UTC instant)
     * @param end          meeting end (UTC instant)
     */
    public static String build(String uid, String summary, String location,
                               String organizerEmail, Instant start, Instant end) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:REQUEST\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(uid).append("\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(start)).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(end)).append("\r\n");
        sb.append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (location != null && !location.isBlank()) {
            sb.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        sb.append("ORGANIZER:mailto:").append(organizerEmail).append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    /** RFC 5545 text escaping for SUMMARY/LOCATION values. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=IcsBuilderTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/IcsBuilder.java \
  src/test/java/com/calit/email/IcsBuilderTest.java
git commit -m "feat: add hand-rolled .ics (VEVENT) builder for email attachments"
```

---

### Task 3: Email templates (Qute)

**Files:**
- Create: `src/main/resources/templates/email/requested.html`
- Create: `src/main/resources/templates/email/confirmation.html`
- Create: `src/main/resources/templates/email/declined.html`
- Create: `src/main/resources/templates/email/reschedule.html`
- Create: `src/main/resources/templates/email/cancellation.html`
- Create: `src/main/resources/templates/email/reminder.html`

These templates are rendered by name through injected `Template` instances in Task 4. They share these data keys: `recipientRole` (`"invitee"`/`"owner"`), `inviteeName`, `meetingTypeName`, `startTime` (pre-formatted in the owner's timezone), `durationMinutes`, `location` (the resolved location text — Meet link or `locationDetail`, or null), `isMeetLink` (boolean — true when `locationType == GOOGLE_MEET`, so the template renders it as a clickable link), and `manageUrl` (the invitee manage link `{base-url}/booking/{manageToken}/manage`). `reschedule.html` also receives `oldStartTime`.

The **requested**, **confirmation/approved**, **reschedule**, and **reminder** templates additionally receive `answers` — a pre-built ordered `List<AnswerLine>` (`label`, `value`) built in `EmailService` from `BookingField.formFor(booking.meetingTypeId)` joined to `booking.answers`, ordered by `position`, blanks filtered out. **Cancellation and declined templates list no answers.** A single `confirmation.html` template serves both `BookingConfirmed` and `BookingApproved` (the body text — "confirmed" — is identical; only the subject differs, set in `EmailService`).

The `manageUrl` (manage/reschedule/cancel link from `manageToken`) is rendered in the invitee-facing emails. The owner copy carries it too (harmless — the owner can see it), keeping a single template per kind.

- [ ] **Step 1: Create `requested.html`** (PENDING — awaiting owner approval; always sent)

`src/main/resources/templates/email/requested.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking request has been <strong>received</strong> and is awaiting confirmation.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Requested time:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if location}
    {#if isMeetLink}<li><strong>Google Meet:</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>Location:</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>You can manage this booking here: <a href="{manageUrl}">{manageUrl}</a></p>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 2: Create `confirmation.html`** (used by both `BookingConfirmed` and `BookingApproved`)

`src/main/resources/templates/email/confirmation.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking is <strong>confirmed</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>When:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if location}
    {#if isMeetLink}<li><strong>Google Meet:</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>Location:</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>Need to change it? <a href="{manageUrl}">Manage your booking</a>.</p>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 3: Create `declined.html`** (PENDING→DECLINED; always sent; no answers)

`src/main/resources/templates/email/declined.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Unfortunately your booking request was <strong>declined</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Requested time:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
</ul>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 4: Create `reschedule.html`**

`src/main/resources/templates/email/reschedule.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking has been <strong>rescheduled</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Previous time:</strong> {oldStartTime}</li>
  <li><strong>New time:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if location}
    {#if isMeetLink}<li><strong>Google Meet:</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>Location:</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>Manage this booking: <a href="{manageUrl}">{manageUrl}</a></p>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 5: Create `cancellation.html`** (no answers, no location/Meet link — meeting is gone)

`src/main/resources/templates/email/cancellation.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>Your booking has been <strong>cancelled</strong>.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>Was scheduled for:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
</ul>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 6: Create `reminder.html`** (upcoming meeting; follows fallback rule)

`src/main/resources/templates/email/reminder.html`:

```html
<html>
<body>
<p>Hi {inviteeName},</p>
<p>This is a <strong>reminder</strong> of your upcoming meeting.</p>
<ul>
  <li><strong>Meeting:</strong> {meetingTypeName}</li>
  <li><strong>When:</strong> {startTime}</li>
  <li><strong>Duration:</strong> {durationMinutes} minutes</li>
  {#if location}
    {#if isMeetLink}<li><strong>Google Meet:</strong> <a href="{location}">{location}</a></li>
    {#else}<li><strong>Location:</strong> {location}</li>{/if}
  {/if}
</ul>
{#if answers}
<p><strong>Your answers:</strong></p>
<ul>
  {#for line in answers}
  <li><strong>{line.label}:</strong> {line.value}</li>
  {/for}
</ul>
{/if}
<p>Manage this booking: <a href="{manageUrl}">{manageUrl}</a></p>
<p>This message was sent to the {recipientRole}.</p>
</body>
</html>
```

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/email/
git commit -m "feat: add Qute email templates (requested, confirmation, declined, reschedule, cancellation, reminder)"
```

---

### Task 4: `EmailService` — observe events, decide recipients, send mail with `.ics`

**Files:**
- Create: `src/main/java/com/calit/email/EmailService.java`
- Test: `src/test/java/com/calit/email/EmailServiceTest.java`

**Behavior contract:**
- Seven observer methods, each `void`, each annotated `@Observes(during = TransactionPhase.AFTER_SUCCESS)` on its event record (six Plan 3 events + Plan 6 `ReminderDue`). They run **after** the booking transaction commits.
- Each observer delegates to a package-private helper (`handleRequested`, `handleConfirmed`, `handleApproved`, `handleDeclined`, `handleRescheduled`, `handleCancelled`, `handleReminder`). Helpers own their transaction (via `load(...)` → `QuarkusTransaction.requiringNew()`) and are directly unit-testable, independent of CDI event timing.
- **Load gotcha:** because `AFTER_SUCCESS` observers run with no active transaction/persistence context, `load(...)` opens a fresh `requiringNew()` transaction to read the `Booking`, its `MeetingType`, the `OwnerSettings` singleton, **and** `BookingField.formFor(booking.meetingTypeId)`. Do **not** call `Booking.findById(...)` / `BookingField.formFor(...)` directly in an observer without a surrounding transaction.
- **Recipient selection (the heart of this plan):**
  - **Owner** is added whenever `owner.ownerNotificationsEnabled` is true — for **every** kind. If false, the owner is skipped entirely.
  - **Invitee** is added when the kind is an **always-send exception** (`requested`, `declined`) OR when Google is **not** connected (`!calendarPort.isConnected()`). When Google is connected and the kind is not an exception, the invitee is skipped (Google sent the calendar mail).
  - If the selected recipient set is empty (e.g. connected + owner opted out on a confirmed booking), **no mail is sent**.
- **Location resolution:** `resolveLocation(MeetingType)` returns `booking.meetLink` when `locationType == GOOGLE_MEET` (the Meet URL Plan 2 stored, or null if disconnected), else `meetingType.locationDetail` (phone number / address / custom text). `isMeetLink` is `locationType == GOOGLE_MEET`. The cancellation kind passes no location.
- **`.ics` attachment:** every sent `Mail` gets `.addInlineAttachment(...)` / `.addAttachment(...)` with the ICS bytes from `IcsBuilder.build(booking.manageToken, meetingType.name, resolvedLocation, owner.ownerEmail, booking.startUtc, booking.endUtc)`, content-type `text/calendar; charset=UTF-8; method=REQUEST`, filename `invite.ics`. (Cancellation still attaches an ICS describing the event being removed, with `method=REQUEST`; this is acceptable for v1 — recipients can ignore it.)
- The custom booking-field answers are rendered for `requested`/`confirmed`/`approved`/`reschedule`/`reminder` (not `cancelled`/`declined`). `buildAnswerLines` joins `BookingField.formFor(...)` (ordered by `position`) to `booking.answers` by `fieldKey`, skipping blank/absent.
- The **manage link** is `baseUrl + "/booking/" + booking.manageToken + "/manage"`, passed as `manageUrl`.
- Times are formatted in the owner's timezone (`OwnerSettings.timezone`) with a shared `DateTimeFormatter`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/email/EmailServiceTest.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRequested;
import com.calit.booking.events.BookingRescheduled;
import com.calit.booking.events.ReminderDue;
import com.calit.domain.BookingField;
import com.calit.domain.FieldType;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailServiceTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    // Mock the Google connection state so we can drive the invitee-fallback branch
    // without a real OAuth connection.
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    // ---- Confirmed + Google NOT connected: invitee + owner BOTH get mail ----

    @Test
    void confirmedWhenGoogleDisconnectedSendsToInviteeAndOwnerWithLocationManageLinkAnswersAndIcs() {
        when(calendarPort.isConnected()).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/abc-defg-hij";
            b.answers = Map.of("description", "Pricing tiers", "company", "Acme");
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMessagesSentTo(INVITEE_EMAIL);
        List<Mail> toOwner = mailbox.getMessagesSentTo(OWNER_EMAIL);
        assertEquals(1, toInvitee.size(), "disconnected -> invitee fallback mail");
        assertEquals(1, toOwner.size(), "owner always (enabled)");
        assertEquals(2, mailbox.getTotalMessagesSent());

        Mail m = toInvitee.get(0);
        assertTrue(m.getHtml().contains("Discovery Call"), "meeting type name");
        // location present (Meet link, GOOGLE_MEET type)
        assertTrue(m.getHtml().contains("https://meet.google.com/abc-defg-hij"), "location/meet link");
        // manage link from manageToken
        assertTrue(m.getHtml().contains("/booking/"), "manage link path present");
        assertTrue(m.getHtml().contains("/manage"), "manage link suffix present");
        // answers
        assertTrue(m.getHtml().contains("What do you want to discuss?"), "field label");
        assertTrue(m.getHtml().contains("Pricing tiers"), "answer value");
        assertTrue(m.getHtml().contains("Company"));
        assertTrue(m.getHtml().contains("Acme"));
        assertTrue(m.getSubject().toLowerCase().contains("confirmed"));

        // .ics attachment present on an app-sent mail
        assertHasIcsAttachment(m);
    }

    // ---- Confirmed + Google connected: invitee gets NO app mail; owner still does ----

    @Test
    void confirmedWhenGoogleConnectedSuppressesInviteeButOwnerStillGetsMail() {
        when(calendarPort.isConnected()).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/xyz";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(mailbox.getMessagesSentTo(INVITEE_EMAIL).isEmpty(),
                "connected -> Google emails the invitee, app must not");
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size(),
                "owner still gets the app mail");
        assertEquals(1, mailbox.getTotalMessagesSent());
        assertHasIcsAttachment(mailbox.getMessagesSentTo(OWNER_EMAIL).get(0));
    }

    // ---- BookingRequested (PENDING): always to invitee + owner, regardless of Google ----

    @Test
    void requestedAlwaysSendsToInviteeAndOwnerEvenWhenGoogleConnected() {
        when(calendarPort.isConnected()).thenReturn(true); // connected, but no Google event exists yet
        long bookingId = seed(b -> {
            b.status = BookingStatus.PENDING;
            b.meetLink = null;
            b.answers = Map.of("description", "Need a demo");
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleRequested(new BookingRequested(bookingId));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size(),
                "requested is an always-send exception (no Google event)");
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());
        Mail m = mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0);
        assertTrue(m.getSubject().toLowerCase().contains("request"));
        assertTrue(m.getHtml().contains("Need a demo"));
        assertHasIcsAttachment(m);
    }

    // ---- BookingDeclined: always to invitee, regardless of Google ----

    @Test
    void declinedAlwaysSendsToInviteeEvenWhenGoogleConnected() {
        when(calendarPort.isConnected()).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.DECLINED;
            b.meetLink = null;
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleDeclined(new BookingDeclined(bookingId));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size(),
                "declined is an always-send exception (no Google event)");
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        Mail m = mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0);
        assertTrue(m.getSubject().toLowerCase().contains("declin"));
    }

    // ---- ownerNotificationsEnabled = false: owner gets nothing; invitee per rules ----

    @Test
    void ownerOptedOutGetsNothingInviteeStillFallbackWhenDisconnected() {
        when(calendarPort.isConnected()).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/opt-out";
        }, false /* ownerNotificationsEnabled = false */, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(mailbox.getMessagesSentTo(OWNER_EMAIL).isEmpty(), "owner opted out -> no owner mail");
        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size(),
                "invitee still gets fallback (disconnected)");
        assertEquals(1, mailbox.getTotalMessagesSent());
    }

    @Test
    void ownerOptedOutAndGoogleConnectedSendsNothing() {
        when(calendarPort.isConnected()).thenReturn(true);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/none";
        }, false, LocationType.GOOGLE_MEET, null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertEquals(0, mailbox.getTotalMessagesSent(),
                "connected (invitee suppressed) + owner opted out -> zero mail");
    }

    // ---- Non-Meet location (PHONE) renders locationDetail, not a link ----

    @Test
    void confirmedPhoneLocationRendersLocationDetail() {
        when(calendarPort.isConnected()).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = null;
        }, true, LocationType.PHONE, "+1 555 0100");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail m = mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0);
        assertTrue(m.getHtml().contains("+1 555 0100"), "phone locationDetail rendered");
        assertFalse(m.getHtml().contains("meet.google.com"), "no meet link for PHONE type");
    }

    // ---- Reschedule follows the fallback rule too ----

    @Test
    void rescheduleWhenConnectedSuppressesInviteeOwnerStillGets() {
        when(calendarPort.isConnected()).thenReturn(true);
        Instant newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedAt(newStart, b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/resch";
        }, true, LocationType.GOOGLE_MEET, null);
        Instant oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart));

        assertTrue(mailbox.getMessagesSentTo(INVITEE_EMAIL).isEmpty());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertTrue(mailbox.getMessagesSentTo(OWNER_EMAIL).get(0).getSubject()
                .toLowerCase().contains("reschedul"));
    }

    // ---- Cancellation: fallback rule, no location/meet link in body ----

    @Test
    void cancellationWhenDisconnectedSendsToBothWithoutMeetLink() {
        when(calendarPort.isConnected()).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CANCELLED;
            b.meetLink = "https://meet.google.com/will-not-appear";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleCancelled(new BookingCancelled(bookingId));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        Mail m = mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0);
        assertTrue(m.getSubject().toLowerCase().contains("cancel"));
        assertFalse(m.getHtml().contains("will-not-appear"),
                "cancellation body must not include a meet link");
    }

    // ---- Reminder follows the fallback rule ----

    @Test
    void reminderWhenDisconnectedSendsToBoth() {
        when(calendarPort.isConnected()).thenReturn(false);
        long bookingId = seed(b -> {
            b.status = BookingStatus.CONFIRMED;
            b.meetLink = "https://meet.google.com/rem";
        }, true, LocationType.GOOGLE_MEET, null);

        emailService.handleReminder(new ReminderDue(bookingId));

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertTrue(mailbox.getMessagesSentTo(INVITEE_EMAIL).get(0).getSubject()
                .toLowerCase().contains("reminder"));
    }

    // --- attachment assertion: every app-sent mail carries an .ics ---

    private static void assertHasIcsAttachment(Mail m) {
        assertFalse(m.getAttachments().isEmpty(), "mail must carry an attachment");
        assertTrue(m.getAttachments().stream().anyMatch(a ->
                        "invite.ics".equals(a.getName())
                                || (a.getContentType() != null
                                    && a.getContentType().contains("text/calendar"))),
                "an .ics (text/calendar) attachment must be present");
    }

    // --- seeding helpers ---

    private long seed(java.util.function.Consumer<Booking> tweak, boolean ownerNotificationsEnabled,
                      LocationType locationType, String locationDetail) {
        return seedAt(Instant.parse("2026-06-08T09:00:00Z"), tweak, ownerNotificationsEnabled,
                locationType, locationDetail);
    }

    private long seedAt(Instant startUtc, java.util.function.Consumer<Booking> tweak,
                        boolean ownerNotificationsEnabled, LocationType locationType,
                        String locationDetail) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.get();
            if (s == null) {
                s = new OwnerSettings();
                s.id = OwnerSettings.SINGLETON_ID;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = ownerNotificationsEnabled;
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "Discovery Call";
            t.slug = "discovery-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = locationType;
            t.locationDetail = locationDetail;
            t.persist();

            // Global custom fields so answers render with labels in order.
            BookingField f1 = new BookingField();
            f1.meetingTypeId = null;
            f1.fieldKey = "description";
            f1.label = "What do you want to discuss?";
            f1.type = FieldType.LONG_TEXT;
            f1.required = false;
            f1.position = 0;
            f1.persist();

            BookingField f2 = new BookingField();
            f2.meetingTypeId = null;
            f2.fieldKey = "company";
            f2.label = "Company";
            f2.type = FieldType.SHORT_TEXT;
            f2.required = false;
            f2.position = 1;
            f2.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = startUtc;
            b.endUtc = startUtc.plus(30, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = null;
            b.status = BookingStatus.CONFIRMED;
            b.answers = Map.of();
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            tweak.accept(b);
            b.persist();
            return b.id;
        });
    }
}
```

> The tests invoke the package-private helpers directly. Each helper opens its own `QuarkusTransaction.requiringNew()` to load entities, so the tests do **not** need `@TestTransaction`. `@InjectMock CalendarPort` replaces the real Google adapter so `isConnected()` is driven per test — this is how the connected-vs-disconnected split is asserted (connected ⇒ invitee mailbox empty; disconnected ⇒ invitee gets one). `getMessagesSentTo(...)` returns the per-recipient list; attachment presence is checked via `Mail.getAttachments()` (name `invite.ics` or content-type `text/calendar`). The seed always commits first (its own `requiringNew()`), so the helper's fresh transaction reads committed rows.

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: FAIL — compilation error, `EmailService` does not exist.

- [ ] **Step 3: Write `EmailService`**

`src/main/java/com/calit/email/EmailService.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRequested;
import com.calit.booking.events.BookingRescheduled;
import com.calit.booking.events.ReminderDue;
import com.calit.domain.BookingField;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class EmailService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";
    private static final String ICS_FILENAME = "invite.ics";

    @Inject
    Mailer mailer;

    @Inject
    CalendarPort calendarPort;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @Inject
    @io.quarkus.qute.Location("email/requested.html")
    Template requested;

    @Inject
    @io.quarkus.qute.Location("email/confirmation.html")
    Template confirmation;

    @Inject
    @io.quarkus.qute.Location("email/declined.html")
    Template declined;

    @Inject
    @io.quarkus.qute.Location("email/reschedule.html")
    Template reschedule;

    @Inject
    @io.quarkus.qute.Location("email/cancellation.html")
    Template cancellation;

    @Inject
    @io.quarkus.qute.Location("email/reminder.html")
    Template reminder;

    /** Which invitee-delivery rule a kind follows. */
    private enum InviteeRule {
        /** Always send to invitee (no Google event exists for this state). */
        ALWAYS,
        /** Send to invitee only when Google is NOT connected (Google notifies otherwise). */
        FALLBACK
    }

    // --- CDI observers: fire only after the booking transaction commits. ---

    void onRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRequested e) {
        handleRequested(e);
    }

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        handleConfirmed(e);
    }

    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        handleApproved(e);
    }

    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        handleDeclined(e);
    }

    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        handleRescheduled(e);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        handleCancelled(e);
    }

    void onReminder(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReminderDue e) {
        handleReminder(e);
    }

    // --- Package-private helpers: own their transaction, directly unit-testable. ---

    void handleRequested(BookingRequested e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.ALWAYS, "Booking request received: " + l.meetingType.name, l, location,
                role -> requested
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("location", location)
                        .data("isMeetLink", isMeet(l))
                        .data("manageUrl", manageUrl(l.booking))
                        .data("answers", l.answers)
                        .render());
    }

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.FALLBACK, "Booking confirmed: " + l.meetingType.name, l, location,
                role -> confirmation
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("location", location)
                        .data("isMeetLink", isMeet(l))
                        .data("manageUrl", manageUrl(l.booking))
                        .data("answers", l.answers)
                        .render());
    }

    void handleApproved(BookingApproved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        // Same body as confirmed (now confirmed after approval); only subject differs.
        sendForKind(InviteeRule.FALLBACK, "Booking approved: " + l.meetingType.name, l, location,
                role -> confirmation
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("location", location)
                        .data("isMeetLink", isMeet(l))
                        .data("manageUrl", manageUrl(l.booking))
                        .data("answers", l.answers)
                        .render());
    }

    void handleDeclined(BookingDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String start = format(l.booking.startUtc, l.zone);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKind(InviteeRule.ALWAYS, "Booking declined: " + l.meetingType.name, l, resolveLocation(l),
                role -> declined
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .render());
    }

    void handleRescheduled(BookingRescheduled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String newStart = format(l.booking.startUtc, l.zone);
        String oldStart = format(e.oldStartUtc(), l.zone);
        sendForKind(InviteeRule.FALLBACK, "Booking rescheduled: " + l.meetingType.name, l, location,
                role -> reschedule
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", newStart)
                        .data("oldStartTime", oldStart)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("location", location)
                        .data("isMeetLink", isMeet(l))
                        .data("manageUrl", manageUrl(l.booking))
                        .data("answers", l.answers)
                        .render());
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String start = format(l.booking.startUtc, l.zone);
        // No location/meet link in the cancellation body; .ics still attached describing the removed event.
        sendForKind(InviteeRule.FALLBACK, "Booking cancelled: " + l.meetingType.name, l, resolveLocation(l),
                role -> cancellation
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .render());
    }

    void handleReminder(ReminderDue e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.FALLBACK, "Reminder: " + l.meetingType.name, l, location,
                role -> reminder
                        .data("recipientRole", role)
                        .data("inviteeName", l.booking.inviteeName)
                        .data("meetingTypeName", l.meetingType.name)
                        .data("startTime", start)
                        .data("durationMinutes", l.meetingType.durationMinutes)
                        .data("location", location)
                        .data("isMeetLink", isMeet(l))
                        .data("manageUrl", manageUrl(l.booking))
                        .data("answers", l.answers)
                        .render());
    }

    // --- recipient selection + send plumbing ---

    /**
     * Sends the rendered body (per recipient role) to the selected recipients, each with the .ics.
     * Owner is included iff {@code ownerNotificationsEnabled}; invitee per {@code rule} and
     * {@code calendarPort.isConnected()}. No mail is sent if the recipient set is empty.
     */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             java.util.function.Function<String, String> bodyForRole) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected();
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            mailer.send(withIcs(
                    Mail.withHtml(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee")), ics));
        }
        if (sendOwner) {
            mailer.send(withIcs(
                    Mail.withHtml(l.owner.ownerEmail, subject, bodyForRole.apply("owner")), ics));
        }
    }

    private static Mail withIcs(Mail mail, byte[] ics) {
        return mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
    }

    private String manageUrl(Booking b) {
        return baseUrl + "/booking/" + b.manageToken + "/manage";
    }

    /** Meet link for GOOGLE_MEET types, else the type's locationDetail (phone/address/custom). */
    private static String resolveLocation(Loaded l) {
        if (l.meetingType.locationType == LocationType.GOOGLE_MEET) {
            return l.booking.meetLink; // may be null when Google is disconnected
        }
        return l.meetingType.locationDetail;
    }

    private static boolean isMeet(Loaded l) {
        return l.meetingType.locationType == LocationType.GOOGLE_MEET;
    }

    private static String format(Instant instant, ZoneId zone) {
        return TIME_FORMAT.format(instant.atZone(zone));
    }

    /**
     * Loads the booking + its meeting type + owner settings + custom-field answers inside a fresh
     * transaction. Required because AFTER_SUCCESS observers run with no active persistence context.
     * Returns null if the booking no longer exists.
     */
    private Loaded load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking booking = Booking.findById(bookingId);
            if (booking == null) {
                return null;
            }
            MeetingType type = MeetingType.findById(booking.meetingTypeId);
            OwnerSettings owner = OwnerSettings.get();
            ZoneId zone = ZoneId.of(owner.timezone);
            List<AnswerLine> answers = buildAnswerLines(booking);
            return new Loaded(booking, type, owner, zone, answers);
        });
    }

    /**
     * Joins {@code BookingField.formFor(meetingTypeId)} (ordered by {@code position}) to
     * {@code booking.answers} by {@code fieldKey}, skipping blank/absent values. Must run inside the
     * {@code requiringNew()} transaction opened by {@link #load}.
     */
    private static List<AnswerLine> buildAnswerLines(Booking booking) {
        List<AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new AnswerLine(field.label, value));
            }
        }
        return lines;
    }

    /** Immutable bundle read once in one transaction. */
    private record Loaded(Booking booking, MeetingType meetingType, OwnerSettings owner, ZoneId zone,
                          List<AnswerLine> answers) {}

    /** One rendered custom-field answer: human label + submitted value. Public for Qute access. */
    public record AnswerLine(String label, String value) {}
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=EmailServiceTest`
Expected: PASS (all tests). The connected/disconnected split, always-send exceptions, owner opt-out, location resolution and `.ics` presence are all captured by the `MockMailbox`; no SMTP is contacted.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/email/EmailService.java \
  src/test/java/com/calit/email/EmailServiceTest.java
git commit -m "feat: EmailService with fallback/always-send rules, owner opt-out, location and .ics"
```

---

### Task 5: End-to-end — fire the real CDI event across a committed transaction

This task proves the `@Observes(during = AFTER_SUCCESS)` wiring actually triggers when an event is fired from within a committed transaction (not just when the helper is called directly). It uses an injected `Event<BookingConfirmed>` fired inside a `QuarkusTransaction.requiringNew()` block; the observer must run only after that transaction commits. Google is mocked **disconnected** so the invitee fallback fires and we get two mails.

**Files:**
- Test: `src/test/java/com/calit/email/EmailServiceEventWiringTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/email/EmailServiceEventWiringTest.java`:

```java
package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingConfirmed;
import com.calit.domain.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailServiceEventWiringTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    Event<BookingConfirmed> confirmedEvent;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void firingConfirmedInCommittedTxTriggersObserverAndSendsTwoMails() {
        when(calendarPort.isConnected()).thenReturn(false); // disconnected -> invitee fallback fires

        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.get();
            if (s == null) {
                s = new OwnerSettings();
                s.id = OwnerSettings.SINGLETON_ID;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "Wiring Call";
            t.slug = "wiring-" + System.nanoTime();
            t.durationMinutes = 45;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(45, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = "https://meet.google.com/wire-test";
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();

            confirmedEvent.fire(new BookingConfirmed(b.id));
        });

        assertEquals(1, mailbox.getMessagesSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMessagesSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());
    }
}
```

> Firing inside a committed transaction is the reliable trigger for an `AFTER_SUCCESS` observer. If the event were fired with **no** active transaction, the observer would never run — that is the nuance this test pins down. The observer's own `load(...)` opens a *separate* `requiringNew()` transaction, which is why the row (committed by the time the observer fires) is readable. Google is mocked disconnected so both the invitee-fallback and the always-on owner mail are sent (two messages).

- [ ] **Step 2: Run it to confirm it passes**

Run: `mvn test -Dtest=EmailServiceEventWiringTest`
Expected: PASS — two messages captured by the `MockMailbox`, proving the `AFTER_SUCCESS` observer fired after commit.

- [ ] **Step 3: Run the full suite**

Run: `mvn test`
Expected: PASS — all prior plans' tests plus `IcsBuilderTest`, `EmailServiceTest` and `EmailServiceEventWiringTest`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/calit/email/EmailServiceEventWiringTest.java
git commit -m "test: verify AFTER_SUCCESS observer wiring fires on committed booking event"
```

---

## Built deviations (synced to reality)

- **`io.quarkus.test.InjectMock`** in both test files (Quarkus relocated it from `io.quarkus.test.junit.mockito.InjectMock`).
- **Nested enums:** `LocationType` is `com.calit.domain.MeetingType.LocationType` and `FieldType` is `com.calit.domain.BookingField.FieldType` (built nested in earlier plans), not the top-level `com.calit.domain.LocationType`/`FieldType` the plan's code blocks assumed. `EmailService` and both tests import the nested forms.
- **`ReminderDue` created here:** `com.calit.booking.events.ReminderDue(Long bookingId)` did not yet exist (Plan 6 will *fire* it; Plan 4 only *observes*). Created as the Plan 4↔6 contract record.
- **Test isolation:** both email tests add `@BeforeEach` `QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll())`. The verbatim seeds commit HELD (PENDING/CONFIRMED) bookings at a fixed start time with no `@TestTransaction` rollback, so across the HELD-status tests the second seed onward tripped the `booking_no_overlap_held` exclusion constraint. Cleanup between tests fixes it; fixed dates and direct-helper-call design are otherwise kept verbatim (the email path does no now-relative filtering, so fixed dates are safe here).
- **Known v1 follow-up (non-blocking):** observers don't wrap `mailer.send`/render in try-catch — `AFTER_SUCCESS` timing already guarantees the booking is committed first (a mail failure cannot roll it back; CDI logs the throw), but a failed send is not retried and a thrown invitee-send would skip the owner-send. Acceptable for v1; revisit if delivery reliability matters.

---

## Self-Review against spec

**1. Spec coverage (Plan 4 scope):**

| Requirement | Task |
|---|---|
| **Feature 4 — emails to participants, full model** | Tasks 3 & 4. INVITEE: app sends **only as fallback when `!CalendarPort.isConnected()`** (Google's `sendUpdates=all` notifies the invitee when connected), with two **always-send exceptions** — `BookingRequested` and `BookingDeclined` — because no Google event exists in those states (`InviteeRule.ALWAYS`). OWNER: app **always** sends, for every kind, **gated by `OwnerSettings.ownerNotificationsEnabled`** (opt-out, default true). Empty recipient set ⇒ no mail. Asserted: connected ⇒ invitee mailbox empty / owner = 1; disconnected ⇒ both get one; requested/declined ⇒ invitee always; owner opt-out ⇒ owner = 0 |
| Kinds: requested / approved / declined / confirmed / reschedule / cancel / reminder | Task 3 templates (`requested`, `confirmation` [serves confirmed+approved], `declined`, `reschedule`, `cancellation`, `reminder`); Task 4 helpers `handleRequested`/`handleConfirmed`/`handleApproved`/`handleDeclined`/`handleRescheduled`/`handleCancelled`/`handleReminder` |
| Observe all six Plan 3 events + Plan 6 `ReminderDue` | Task 4 — seven `@Observes(during = AFTER_SUCCESS)` methods on `BookingRequested`/`BookingConfirmed`/`BookingApproved`/`BookingDeclined`/`BookingRescheduled`/`BookingCancelled`/`ReminderDue` from `com.calit.booking.events` |
| **Feature 13 — location in emails** (Meet link OR `locationDetail` per `locationType`) | Task 4 `resolveLocation`: `meetLink` for `GOOGLE_MEET`, else `locationDetail`; `isMeetLink` drives link-vs-text rendering in templates. Asserted by the PHONE test (renders `+1 555 0100`, no meet link) and the GOOGLE_MEET tests (meet link present) |
| **Feature 14 — requested/approved/declined always-send semantics** | `BookingRequested` and `BookingDeclined` use `InviteeRule.ALWAYS` (no Google event exists); `BookingApproved` reuses the confirmation body. Asserted: requested + declined reach the invitee even when `isConnected()==true` |
| **Manage-token link** in invitee emails | Task 4 `manageUrl(booking)` = `{app.base-url}/booking/{manageToken}/manage`, passed as `manageUrl`; rendered in requested/confirmation/reschedule/reminder templates. Asserted: body contains `/booking/` and `/manage` |
| **Owner opt-out** | Task 4 `sendForKind` includes owner iff `owner.ownerNotificationsEnabled`. Asserted by the two opt-out tests (owner = 0; connected+opt-out ⇒ total = 0) |
| **`.ics` attachment on every app-sent mail** | Task 2 `IcsBuilder` (VEVENT: DTSTART/DTEND UTC, SUMMARY, LOCATION, ORGANIZER, UID=manageToken); Task 4 `withIcs` attaches `invite.ics` (`text/calendar; method=REQUEST`) to each `Mail`. Asserted via `Mail.getAttachments()` (`assertHasIcsAttachment`) |
| **Feature 10 — custom booking-field answers in emails** | Task 4 `buildAnswerLines` joins `BookingField.formFor(meetingTypeId)` (ordered by `position`) to `booking.answers` by `fieldKey`, skips blank/absent; rendered in requested/confirmed/approved/reschedule/reminder (not cancel/declined). Asserted (label `"What do you want to discuss?"` + value `"Pricing tiers"`, plus `"Company"`/`"Acme"`) |
| Times in owner's timezone | Task 4 (`ZoneId.of(owner.timezone)` + shared `DateTimeFormatter`) |
| Decoupled — only observe, don't change booking flow | No edit to any Plan 3 file; only `@Observes` consumers added. No new Flyway migration |
| Email over SMTP, config-driven; mock in dev/test | Task 1 (`quarkus.mailer.*` + `app.base-url` env-driven under `%prod`; `mock=true` in dev/test) |
| Loads happen inside the AFTER_SUCCESS transaction | Task 4 — `Booking`/`MeetingType`/`OwnerSettings`/`BookingField.formFor(...)` all read inside one `QuarkusTransaction.requiringNew()` in `load(...)` |

No feature-4, feature-10, feature-13 or feature-14 (email-rendering) requirement is unmapped.

**2. Placeholder scan:** No TBD / TODO / "handle edge cases" / "similar to" placeholders. Every task shows full file contents and exact `mvn test -Dtest=...` commands with explicit FAIL/PASS expectations and commits.

**3. Type consistency with the overview contract (consumed exactly):**
- **Events** (`com.calit.booking.events`, all records carrying `Long bookingId`): `BookingRequested(Long)` → `e.bookingId()`; `BookingConfirmed(Long)` → `e.bookingId()`; `BookingApproved(Long)` → `e.bookingId()`; `BookingDeclined(Long)` → `e.bookingId()`; `BookingRescheduled(Long, Instant oldStartUtc)` → `e.bookingId()`, `e.oldStartUtc()`; `BookingCancelled(Long)` → `e.bookingId()`; Plan 6 `ReminderDue(Long)` → `e.bookingId()`. All six Plan 3 events + ReminderDue observed.
- **`CalendarPort`** (`com.calit.google`, Plan 2): only `boolean isConnected()` is called — matches the overview "Defined in Plan 2" bullet; mocked via `@InjectMock` in tests to drive the connected/disconnected split. No other `CalendarPort` method is touched (Plan 3 owns the Google writes).
- **`Booking`** (Plan 3) fields used: `id`, `meetingTypeId`, `inviteeName`, `inviteeEmail`, `startUtc`, `endUtc`, `meetLink` (nullable), `status` (`PENDING`/`CONFIRMED`/`CANCELLED`/`DECLINED`), `answers` (`Map<String,String>`), **`manageToken`** (String — used for the manage link and the ICS UID). `Booking.findById(id)`. No field invented or renamed.
- **`OwnerSettings`** (Plan 1 / 1b): `ownerEmail`, `ownerName`, `timezone`, **`ownerNotificationsEnabled`** (boolean — the owner opt-out gate), `OwnerSettings.get()` / `SINGLETON_ID`. Matches the overview "OwnerSettings gains ownerNotificationsEnabled" bullet.
- **`MeetingType`** (Plan 1 / 1b): `name`, `durationMinutes`, **`locationType`** (`LocationType`: `GOOGLE_MEET`/`PHONE`/`IN_PERSON`/`CUSTOM`), **`locationDetail`** (nullable), `findById`. Matches the Plan 1b location bullet.
- **`BookingField`** (Plan 1): `BookingField.formFor(Long meetingTypeId)` (per-type-or-global, ordered by `position`), per-field `fieldKey`, `label`. Seeded in tests with `meetingTypeId`/`fieldKey`/`label`/`type` (`FieldType`)/`required`/`position`. No field invented or renamed.

**Transaction-phase note (the real gotcha, explicitly handled):** `@Observes(during = TransactionPhase.AFTER_SUCCESS)` observers run after the booking transaction has committed and with no active persistence context. Loading entities therefore happens inside `QuarkusTransaction.requiringNew()` in `EmailService.load(...)`. Tests cover both invocation styles: direct helper calls (Task 4) and a real `Event<>.fire(...)` inside a committed transaction (Task 5).
