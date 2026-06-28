# Invitee Guests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an invitee add guests (by email) to a booking; guests receive create/reschedule/cancel emails with a calendar `.ics`, can decline via a tokenized link, but cannot reschedule or cancel the meeting.

**Architecture:** Guests live in a new owner-scoped `booking_guest` table. They are notified entirely through calit-sent `.ics` emails (METHOD:REQUEST on invite/reschedule, METHOD:CANCEL on cancel/decline/removal) — no Google Calendar changes. Email fan-out reuses the existing CDI-event observers in `EmailService`; each guest gets its own ATTENDEE `.ics`. A per-booking `ics_sequence` counter bumps on every reschedule so a guest's calendar client supersedes the prior event. The invitee edits the guest list (add + remove) both at booking time and on the reschedule/manage page via a progressively-enhanced chips input.

**Tech Stack:** Quarkus 3.36, Java 25, Panache (`PanacheEntityBase`, public fields), Flyway, Qute `@CheckedTemplate`, Tailwind v4 + daisyUI 5, RestAssured + MockMailbox tests, Postgres via Dev Services.

## Global Constraints

- **Java 25 / Quarkus 3.36.** No new runtime dependencies.
- **No JavaScript ships at runtime** except small inline vanilla scripts (progressive enhancement). The chips UI is one such script; existing guests must still submit (and thus survive a reschedule) with JS disabled.
- **Owner scoping invariant:** every tenant row carries `owner_id`. `booking_guest.owner_id` is set from `booking.ownerId`. Never read another owner's data.
- **Flyway:** never edit an applied migration. Add `V18__*.sql` only. Hibernate is validate-only — entity fields must match the DDL exactly.
- **CSRF:** every POST form must carry `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">` or it 400s in prod (off in `%test`).
- **Qute:** `maven.compiler.parameters=true` is on; `@CheckedTemplate` native methods map 1:1 to template files under `src/main/resources/templates/<ResourceName>/`.
- **Tests:** Docker must be running. Surefire `reuseForks=true`; admin user is always id 1; committed-seed tests must clean up after themselves (see `EmailServiceTest`/`BookingPostTest` patterns).
- **i18n:** new UI/email strings are added to `AppMessages.java` as `@Message`-annotated methods (English default). German/Hebrew (`msg_de.properties`, `msg_he.properties`) fall back to the English default automatically — translating them is optional and out of scope here.
- **Build CSS once** (`bun run css:build`) before rendering pages, or they appear unstyled. No new CSS classes are introduced (daisyUI `badge`/`btn`/`input` only).

---

### Task 1: Schema + guest entity

**Files:**
- Create: `src/main/resources/db/migration/V18__invitee_guests.sql`
- Create: `src/main/java/site/asm0dey/calit/booking/GuestStatus.java`
- Create: `src/main/java/site/asm0dey/calit/booking/BookingGuest.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java` (add `icsSequence` field)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingGuestTest.java`

**Interfaces:**
- Produces: `BookingGuest` entity (public fields `id, ownerId, bookingId, email, status, declineToken, createdAt`); statics `BookingGuest.activeForBooking(Long bookingId) -> List<BookingGuest>`, `BookingGuest.allForBooking(Long bookingId) -> List<BookingGuest>`, `BookingGuest.findInBooking(Long bookingId, String email) -> BookingGuest`, `BookingGuest.findByDeclineToken(String) -> BookingGuest`. `GuestStatus { INVITED, DECLINED, REMOVED }`. `Booking.icsSequence` (public `int`, default 0).

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V18__invitee_guests.sql`:

```sql
-- Invitee-added guests. They receive create/reschedule/cancel emails + .ics and can decline,
-- but cannot reschedule/cancel the booking. ics_sequence is the iTIP SEQUENCE shared by the
-- booking's guest .ics invites: it bumps on every reschedule so a guest's calendar client
-- treats the new (or cancelling) .ics as superseding the previous one.
ALTER TABLE booking ADD COLUMN ics_sequence INT NOT NULL DEFAULT 0;

CREATE TABLE booking_guest (
    id            BIGSERIAL    PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    booking_id    BIGINT       NOT NULL REFERENCES booking(id)  ON DELETE CASCADE,
    email         VARCHAR(254) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'INVITED',
    decline_token VARCHAR(36)  NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT booking_guest_unique_email UNIQUE (booking_id, email)
);

CREATE INDEX idx_booking_guest_booking ON booking_guest (booking_id);
```

- [ ] **Step 2: Write the GuestStatus enum**

Create `src/main/java/site/asm0dey/calit/booking/GuestStatus.java`:

```java
package site.asm0dey.calit.booking;

/** Lifecycle of one guest on a booking. */
public enum GuestStatus {
    /** Active guest: receives every booking email + .ics. */
    INVITED,
    /** Guest declined via their own decline link. No further emails. */
    DECLINED,
    /** Guest was removed by the invitee during a reschedule. No further emails. */
    REMOVED
}
```

- [ ] **Step 3: Write the BookingGuest entity**

Create `src/main/java/site/asm0dey/calit/booking/BookingGuest.java`:

```java
package site.asm0dey.calit.booking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

/**
 * One guest the invitee added to a booking. Owner-scoped (owner_id copied from the booking) per the
 * multi-tenancy invariant. Guests are notified only through calit-sent .ics emails; declineToken is
 * the unguessable key for the guest's "I can't attend" link ({base-url}/guest/{declineToken}/decline).
 */
@Entity
@Table(name = "booking_guest")
public class BookingGuest extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(name = "booking_id", nullable = false)
    public Long bookingId;

    @Column(nullable = false, length = 254)
    public String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public GuestStatus status = GuestStatus.INVITED;

    @Column(name = "decline_token", nullable = false, length = 36, unique = true)
    public String declineToken;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Active (INVITED) guests for a booking — the current email recipients, ordered by id. */
    public static List<BookingGuest> activeForBooking(Long bookingId) {
        return list("bookingId = ?1 and status = ?2 order by id", bookingId, GuestStatus.INVITED);
    }

    /** Every guest row for a booking regardless of status (used by reschedule reconciliation). */
    public static List<BookingGuest> allForBooking(Long bookingId) {
        return list("bookingId = ?1 order by id", bookingId);
    }

    /** The guest row for this booking+email (any status), or null. Email match is case-insensitive. */
    public static BookingGuest findInBooking(Long bookingId, String email) {
        return find("bookingId = ?1 and lower(email) = lower(?2)", bookingId, email).firstResult();
    }

    /** Loads a guest by its unguessable decline token, or null. */
    public static BookingGuest findByDeclineToken(String declineToken) {
        return find("declineToken", declineToken).firstResult();
    }
}
```

- [ ] **Step 4: Add the icsSequence field to Booking**

In `src/main/java/site/asm0dey/calit/booking/Booking.java`, add this field immediately after the `answers` field (after line 79):

```java
    /**
     * iTIP SEQUENCE for this booking's guest .ics invites. Starts at 0; reschedule() increments it so
     * a guest's calendar client supersedes the prior event (and so a CANCEL with an equal-or-higher
     * SEQUENCE removes it). Only guest .ics uses it today; the invitee/owner .ics still emits SEQUENCE:0.
     */
    @Column(name = "ics_sequence", nullable = false)
    public int icsSequence = 0;
```

- [ ] **Step 5: Write the failing entity test**

Create `src/test/java/site/asm0dey/calit/booking/BookingGuestTest.java`:

```java
package site.asm0dey.calit.booking;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BookingGuestTest {

    private Long createBooking() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "g"; t.slug = "g-" + System.nanoTime(); t.durationMinutes = 30;
        t.persist();
        Booking b = new Booking();
        b.ownerId = 1L; b.meetingTypeId = t.id; b.inviteeName = "Sam"; b.inviteeEmail = "sam@example.com";
        b.startUtc = Instant.parse("2026-06-08T07:00:00Z"); b.endUtc = b.startUtc.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED; b.createdAt = Instant.now();
        b.manageToken = java.util.UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }

    private BookingGuest guest(Long bookingId, String email, GuestStatus status) {
        BookingGuest g = new BookingGuest();
        g.ownerId = 1L; g.bookingId = bookingId; g.email = email; g.status = status;
        g.declineToken = java.util.UUID.randomUUID().toString(); g.createdAt = Instant.now();
        g.persist();
        return g;
    }

    @Test
    @TestTransaction
    void persistsAndReadsBackGuestFields() {
        Long bookingId = createBooking();
        BookingGuest g = guest(bookingId, "ana@example.com", GuestStatus.INVITED);

        BookingGuest loaded = BookingGuest.findById(g.id);
        assertEquals(1L, loaded.ownerId);
        assertEquals(bookingId, loaded.bookingId);
        assertEquals("ana@example.com", loaded.email);
        assertEquals(GuestStatus.INVITED, loaded.status);
        assertEquals(g.declineToken, loaded.declineToken);
    }

    @Test
    @TestTransaction
    void activeForBookingReturnsOnlyInvited() {
        Long bookingId = createBooking();
        guest(bookingId, "ana@example.com", GuestStatus.INVITED);
        guest(bookingId, "bob@example.com", GuestStatus.DECLINED);
        guest(bookingId, "cyd@example.com", GuestStatus.REMOVED);

        List<BookingGuest> active = BookingGuest.activeForBooking(bookingId);
        assertEquals(1, active.size());
        assertEquals("ana@example.com", active.getFirst().email);
        assertEquals(3, BookingGuest.allForBooking(bookingId).size());
    }

    @Test
    @TestTransaction
    void findByDeclineTokenAndFindInBookingResolve() {
        Long bookingId = createBooking();
        BookingGuest g = guest(bookingId, "Ana@Example.com", GuestStatus.INVITED);

        assertEquals(g.id, BookingGuest.findByDeclineToken(g.declineToken).id);
        // findInBooking is case-insensitive on email
        assertEquals(g.id, BookingGuest.findInBooking(bookingId, "ana@example.com").id);
        assertNull(BookingGuest.findInBooking(bookingId, "nobody@example.com"));
    }

    @Test
    @TestTransaction
    void bookingIcsSequenceDefaultsToZeroAndRoundTrips() {
        Long bookingId = createBooking();
        Booking b = Booking.findById(bookingId);
        assertEquals(0, b.icsSequence);
        b.icsSequence = 2;
        b.persistAndFlush();
        assertEquals(2, Booking.<Booking>findById(bookingId).icsSequence);
    }
}
```

- [ ] **Step 6: Run the test (expect PASS — schema + entity wired)**

Run: `mvn test -Dtest=BookingGuestTest`
Expected: PASS. (If Flyway validation or Hibernate validate-only fails, the migration DDL and entity columns are out of sync — fix the mismatch.)

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V18__invitee_guests.sql \
        src/main/java/site/asm0dey/calit/booking/GuestStatus.java \
        src/main/java/site/asm0dey/calit/booking/BookingGuest.java \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/test/java/site/asm0dey/calit/booking/BookingGuestTest.java
git commit -m "feat(guests): booking_guest table + entity + booking.ics_sequence"
```

---

### Task 2: IcsBuilder — METHOD + SEQUENCE overload

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/IcsBuilder.java`
- Test: `src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java` (add cases)

**Interfaces:**
- Consumes: nothing new.
- Produces: new overload `IcsBuilder.build(String uid, String summary, String location, Party organizer, Party attendee, Instant start, Instant end, String method, int sequence) -> String`. Existing 7-arg `build(...)` is preserved (delegates with `"REQUEST", 0`), so all current callers compile unchanged. `method` is `"REQUEST"` or `"CANCEL"`; a `"CANCEL"` emits `STATUS:CANCELLED`, otherwise `STATUS:CONFIRMED`.

- [ ] **Step 1: Write the failing test**

Add these two methods to `src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java`:

```java
    @Test
    void cancelMethodEmitsCancelStatusAndSequence() {
        String ics = IcsBuilder.build(
                "tok-9", "Discovery Call", null,
                new IcsBuilder.Party("Owner Name", "owner@example.com"),
                new IcsBuilder.Party("guest@example.com", "guest@example.com"),
                Instant.parse("2026-06-08T09:00:00Z"), Instant.parse("2026-06-08T09:30:00Z"),
                "CANCEL", 3);

        assertTrue(ics.contains("METHOD:CANCEL"), "cancel must be an iTIP CANCEL");
        assertTrue(ics.contains("STATUS:CANCELLED"), "cancelled event status");
        assertTrue(ics.contains("SEQUENCE:3"), "sequence carried through");
        assertTrue(ics.contains("UID:tok-9"), "same UID so the client matches the prior event");
        assertTrue(ics.contains("mailto:guest@example.com"), "guest is the attendee");
    }

    @Test
    void requestOverloadWithSequenceEmitsRequestAndConfirmed() {
        String ics = IcsBuilder.build(
                "tok-9", "Discovery Call", "https://meet.google.com/abc",
                new IcsBuilder.Party("Owner Name", "owner@example.com"),
                new IcsBuilder.Party("guest@example.com", "guest@example.com"),
                Instant.parse("2026-06-08T09:00:00Z"), Instant.parse("2026-06-08T09:30:00Z"),
                "REQUEST", 1);

        assertTrue(ics.contains("METHOD:REQUEST"));
        assertTrue(ics.contains("STATUS:CONFIRMED"));
        assertTrue(ics.contains("SEQUENCE:1"));
    }
```

(`IcsBuilderTest` already imports `java.time.Instant` and `assertTrue`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=IcsBuilderTest`
Expected: FAIL — no `build(...)` overload with 9 params (compile error).

- [ ] **Step 3: Refactor IcsBuilder to add the overload**

In `src/main/java/site/asm0dey/calit/email/IcsBuilder.java`, replace the existing `build(...)` method (lines 38–64) with these two methods:

```java
    public static String build(String uid, String summary, String location,
                               Party organizer, Party attendee,
                               Instant start, Instant end) {
        return build(uid, summary, location, organizer, attendee, start, end, "REQUEST", 0);
    }

    /**
     * @param method   "REQUEST" (invitation/update) or "CANCEL" (removal)
     * @param sequence iTIP SEQUENCE — must be monotonic per UID so updates/cancels supersede.
     */
    public static String build(String uid, String summary, String location,
                               Party organizer, Party attendee,
                               Instant start, Instant end, String method, int sequence) {
        boolean cancel = "CANCEL".equals(method);
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:").append(method).append("\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(escape(uid)).append("\r\n");
        sb.append("SEQUENCE:").append(sequence).append("\r\n");
        sb.append("STATUS:").append(cancel ? "CANCELLED" : "CONFIRMED").append("\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(start)).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(end)).append("\r\n");
        sb.append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (location != null && !location.isBlank()) {
            sb.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        sb.append("ORGANIZER;CN=").append(cn(organizer.name()))
                .append(":mailto:").append(escape(organizer.email())).append("\r\n");
        sb.append("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=")
                .append(cn(attendee.name())).append(":mailto:").append(escape(attendee.email())).append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }
```

Also update the class Javadoc's first sentence to drop the "REQUEST-only" implication if present — optional, no behavior change.

> Note (ponytail: known ceiling): `MailSender` hard-codes the MIME part as `method=REQUEST` / filename `invite.ics`. A CANCEL `.ics` therefore ships under a REQUEST content-type param. Gmail/Outlook honor the **body** `METHOD:CANCEL` + `SEQUENCE`, so the guest's event is removed; the MIME param is a secondary hint. This matches the existing invitee-cancellation behavior. Upgrade `MailSender` to a method-aware content type only if a target client misbehaves.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=IcsBuilderTest`
Expected: PASS (all old + 2 new cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/site/asm0dey/calit/email/IcsBuilder.java \
        src/test/java/site/asm0dey/calit/email/IcsBuilderTest.java
git commit -m "feat(guests): IcsBuilder METHOD+SEQUENCE overload for cancel/update .ics"
```

---

### Task 3: Events + BookingService guest logic

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/events/GuestDeclined.java`
- Create: `src/main/java/site/asm0dey/calit/booking/events/GuestRemoved.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingResource.java` (pass empty guest list)
- Test: `src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java`

**Interfaces:**
- Consumes: `BookingGuest`, `GuestStatus`, `Booking.icsSequence`, `BookingService.isPlausibleEmail` (existing private static).
- Produces:
  - `book(Long ownerId, String meetingTypeSlug, Instant startUtc, String inviteeName, String inviteeEmail, Map<String,String> answers, String turnstileToken, String honeypot, String locale, List<String> guestEmails)` — **new last param** added to the existing `book(...)`.
  - `reschedule(String manageToken, Instant newStartUtc)` — unchanged signature, delegates with `null` guests (no change).
  - `reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails)` — **new overload**; `guestEmails == null` leaves guests untouched, a non-null (possibly empty) list reconciles the active set.
  - `declineGuest(String declineToken)` — marks the guest DECLINED, fires `GuestDeclined`.
  - Events: `GuestDeclined(Long bookingId, Long guestId)`, `GuestRemoved(Long bookingId, Long guestId)`.
  - Constant `BookingService.MAX_GUESTS_PER_BOOKING = 10`.

- [ ] **Step 1: Write the event records**

Create `src/main/java/site/asm0dey/calit/booking/events/GuestDeclined.java`:

```java
package site.asm0dey.calit.booking.events;

/** A guest declined their invitation. The invitee is notified; the guest gets a cancel .ics. */
public record GuestDeclined(Long bookingId, Long guestId) {}
```

Create `src/main/java/site/asm0dey/calit/booking/events/GuestRemoved.java`:

```java
package site.asm0dey.calit.booking.events;

/** The invitee removed a guest during a reschedule. The guest gets a cancel .ics; nobody else is notified. */
public record GuestRemoved(Long bookingId, Long guestId) {}
```

- [ ] **Step 2: Write the failing service test**

Create `src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java`:

```java
package site.asm0dey.calit.booking;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookingServiceGuestTest {

    @Inject BookingService bookingService;
    @InjectMock CalendarPort calendarPort;

    @BeforeEach
    void init() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        QuarkusTransaction.requiringNew().run(() -> {
            BookingGuest.deleteAll();
            Booking.deleteAll();
            MeetingType.delete("ownerId = ?1 and slug = ?2", 1L, "guest-svc");
            AvailabilityRule.delete("ownerId = ?1", 1L);
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L; t.name = "Guest Svc"; t.slug = "guest-svc"; t.durationMinutes = 30;
            t.locationType = LocationType.PHONE; t.locationDetail = "+1 555";
            t.persist();
            for (DayOfWeek d : DayOfWeek.values()) {
                AvailabilityRule r = new AvailabilityRule();
                r.ownerId = 1L; r.dayOfWeek = d; r.startTime = LocalTime.of(0, 0); r.endTime = LocalTime.of(23, 59);
                r.meetingTypeId = null; r.persist();
            }
        });
    }

    /** The earliest available slot start for the seeded type. */
    private Instant firstSlot() {
        MeetingType t = MeetingType.findBySlug(1L, "guest-svc");
        var slots = bookingService.availableSlots(t, java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam")),
                java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam")).plusDays(10));
        return slots.getFirst().start().toInstant();
    }

    @Test
    void bookPersistsGuestsDedupesDropsInviteeAndCaps() {
        // 12 raw entries: a dup (case-insensitive), the invitee's own email, a malformed one,
        // and enough valid ones to exceed the cap of 10.
        java.util.List<String> raw = new java.util.ArrayList<>();
        raw.add("ana@example.com");
        raw.add("ANA@example.com");      // case-insensitive dup -> dropped
        raw.add("sam@example.com");      // the invitee -> dropped
        raw.add("not-an-email");         // invalid -> dropped
        for (int i = 0; i < 12; i++) raw.add("g" + i + "@example.com"); // plenty, to hit the cap

        Booking b = bookingService.book(1L, "guest-svc", firstSlot(), "Sam", "sam@example.com",
                Map.of(), null, null, "en", raw);

        List<BookingGuest> guests = BookingGuest.activeForBooking(b.id);
        assertEquals(BookingService.MAX_GUESTS_PER_BOOKING, guests.size(), "capped at the max");
        assertTrue(guests.stream().noneMatch(g -> g.email.equalsIgnoreCase("sam@example.com")), "invitee dropped");
        assertTrue(guests.stream().noneMatch(g -> g.email.equals("not-an-email")), "invalid dropped");
        assertEquals(guests.size(), guests.stream().map(g -> g.email.toLowerCase()).distinct().count(), "deduped");
        assertTrue(guests.stream().allMatch(g -> g.ownerId.equals(1L)), "owner-scoped");
        assertTrue(guests.stream().allMatch(g -> g.declineToken != null && !g.declineToken.isBlank()), "decline tokens");
    }

    @Test
    void rescheduleReconcilesAddRemoveKeepAndBumpsSequence() {
        Instant start = firstSlot();
        Booking b = bookingService.book(1L, "guest-svc", start, "Sam", "sam@example.com",
                Map.of(), null, null, "en", List.of("ana@example.com", "bob@example.com"));
        assertEquals(0, Booking.<Booking>findById(b.id).icsSequence);

        // Move to a different free slot, drop bob, keep ana, add cyd.
        Instant newStart = start.plusSeconds(3600);
        bookingService.reschedule(b.manageToken, newStart, List.of("ana@example.com", "cyd@example.com"));

        List<BookingGuest> active = BookingGuest.activeForBooking(b.id);
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(g -> g.email.equals("ana@example.com")), "ana kept");
        assertTrue(active.stream().anyMatch(g -> g.email.equals("cyd@example.com")), "cyd added");
        BookingGuest bob = BookingGuest.findInBooking(b.id, "bob@example.com");
        assertEquals(GuestStatus.REMOVED, bob.status, "bob removed");
        assertEquals(1, Booking.<Booking>findById(b.id).icsSequence, "sequence bumped once");
    }

    @Test
    void rescheduleWithNullGuestsLeavesGuestListUntouched() {
        Instant start = firstSlot();
        Booking b = bookingService.book(1L, "guest-svc", start, "Sam", "sam@example.com",
                Map.of(), null, null, "en", List.of("ana@example.com"));

        bookingService.reschedule(b.manageToken, start.plusSeconds(3600)); // 2-arg overload -> null guests

        assertEquals(1, BookingGuest.activeForBooking(b.id).size(), "guests preserved by the no-guest overload");
    }

    @Test
    void declineGuestMarksDeclinedAndIsIdempotent() {
        Booking b = bookingService.book(1L, "guest-svc", firstSlot(), "Sam", "sam@example.com",
                Map.of(), null, null, "en", List.of("ana@example.com"));
        BookingGuest ana = BookingGuest.activeForBooking(b.id).getFirst();

        bookingService.declineGuest(ana.declineToken);
        assertEquals(GuestStatus.DECLINED, BookingGuest.<BookingGuest>findById(ana.id).status);

        // Second call is a no-op, not an error.
        bookingService.declineGuest(ana.declineToken);
        assertEquals(GuestStatus.DECLINED, BookingGuest.<BookingGuest>findById(ana.id).status);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=BookingServiceGuestTest`
Expected: FAIL — `book(...)` has no 10-arg overload, `reschedule(...,List)` / `declineGuest(...)` / `MAX_GUESTS_PER_BOOKING` don't exist (compile errors).

- [ ] **Step 4: Add the guest fields, events, and constant to BookingService**

In `src/main/java/site/asm0dey/calit/booking/BookingService.java`:

Add to the imports (the `events.*` wildcard already covers the new events; add `LinkedHashSet`/`Locale` are not needed). Add the cap constant near the other fields (after line 41):

```java
    /** Max guests an invitee may attach to one booking. ponytail: a constant, not a config knob. */
    public static final int MAX_GUESTS_PER_BOOKING = 10;
```

Add two CDI emitters after the existing `bookingCancelledEvent` field (after line 70):

```java
    @Inject
    Event<GuestDeclined> guestDeclinedEvent;

    @Inject
    Event<GuestRemoved> guestRemovedEvent;
```

- [ ] **Step 5: Add guestEmails to book(...) and persist guests**

Replace the `book(...)` signature (lines 135–139) so the method takes a final `List<String> guestEmails`:

```java
    @Transactional
    public Booking book(Long ownerId, String meetingTypeSlug, Instant startUtc,
                        String inviteeName, String inviteeEmail,
                        Map<String, String> answers, String turnstileToken, String honeypot,
                        String locale, List<String> guestEmails) {
```

Then, inside `book(...)`, immediately after the persist `try { booking.persistAndFlush(); } catch (...) { ... }` block (after line 198, before the `if (type.requiresApproval)` branch on line 200), add:

```java
        persistGuests(booking, guestEmails);
```

Add this helper method to the class (place it right after `book(...)`, before `createGoogleEvent`):

```java
    /**
     * Normalizes + persists the invitee-supplied guest emails as INVITED rows. Trims, lower-cases for
     * de-dup, drops blanks / the invitee's own address / anything that fails {@link #isPlausibleEmail},
     * and caps at {@link #MAX_GUESTS_PER_BOOKING}. Bad entries are dropped silently (one typo must not
     * fail the whole booking). owner_id is copied from the booking for the multi-tenancy invariant.
     */
    private void persistGuests(Booking booking, List<String> guestEmails) {
        for (String email : normalizeGuestEmails(guestEmails, booking.inviteeEmail)) {
            BookingGuest g = new BookingGuest();
            g.ownerId = booking.ownerId;
            g.bookingId = booking.id;
            g.email = email;
            g.status = GuestStatus.INVITED;
            g.declineToken = UUID.randomUUID().toString();
            g.createdAt = Instant.now();
            g.persist();
        }
    }

    /** Cleaned, de-duped (case-insensitive), capped, invitee-excluded guest list. Preserves order. */
    private static List<String> normalizeGuestEmails(List<String> guestEmails, String inviteeEmail) {
        if (guestEmails == null || guestEmails.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashMap<String, String> byLower = new java.util.LinkedHashMap<>();
        for (String raw : guestEmails) {
            if (raw == null) continue;
            String email = raw.trim();
            if (email.isEmpty() || email.length() > 254) continue;
            if (email.equalsIgnoreCase(inviteeEmail)) continue;   // invitee already gets every mail
            if (!isPlausibleEmail(email)) continue;               // drop malformed silently
            byLower.putIfAbsent(email.toLowerCase(), email);
            if (byLower.size() >= MAX_GUESTS_PER_BOOKING) break;
        }
        return List.copyOf(byLower.values());
    }
```

- [ ] **Step 6: Increment icsSequence and add the reschedule guest overload**

Change the existing `reschedule(String manageToken, Instant newStartUtc)` (line 369) into a 2-arg delegator and add the reconciling 3-arg version. Replace the whole existing `reschedule(...)` method (lines 369–420) with:

```java
    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc) {
        return reschedule(manageToken, newStartUtc, null);
    }

    /**
     * Reschedules a booking by its manage token. When {@code guestEmails} is non-null the active guest
     * set is reconciled to it (added guests -> INVITED + GuestRemoved/invite emails downstream; dropped
     * guests -> REMOVED + cancel email; kept guests get the reschedule .ics via BookingRescheduled).
     * A null {@code guestEmails} leaves guests untouched (the JSON API + the 2-arg overload).
     */
    @Transactional
    public Booking reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails) {
        Booking booking = Booking.findByManageToken(manageToken);
        if (booking == null
                || booking.status == BookingStatus.CANCELLED
                || booking.status == BookingStatus.DECLINED) {
            throw new NotFoundException("No active booking for token " + manageToken);
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);

        // Exclude this booking so it may move freely within its own window.
        assertSlotAvailable(type, newStartUtc, booking.id);

        Instant oldStart = booking.startUtc;
        booking.startUtc = newStartUtc;
        booking.endUtc = newEnd;
        // Bump the iTIP SEQUENCE so guest .ics updates/cancels supersede the prior event.
        booking.icsSequence = booking.icsSequence + 1;

        boolean reApproval = type.requiresApproval;
        String priorEventId = booking.googleEventId;
        if (reApproval) {
            // Feature 14: return to the approval queue; drop any existing event.
            booking.status = BookingStatus.PENDING;
            booking.googleEventId = null;
            booking.meetLink = null;
        }

        // Reconcile guests (if the caller supplied a list) inside the same transaction. Collect the
        // ids of guests removed so we can fire cancel emails after commit.
        List<Long> removedGuestIds = reconcileGuests(booking, guestEmails);

        // NFR cross-node guard: flush so the no-overlap exclusion constraint is checked against
        // the new range; a concurrent overlap is surfaced as the same 409 as a double-book.
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + newStartUtc + " is not available for token " + manageToken);
            }
            throw ex;
        }

        // Cancel emails for guests the invitee removed (fired regardless of approval/auto).
        for (Long guestId : removedGuestIds) {
            guestRemovedEvent.fire(new GuestRemoved(booking.id, guestId));
        }

        if (reApproval) {
            if (calendarPort.isConnected(type.ownerId) && priorEventId != null) {
                calendarPort.deleteEvent(type.ownerId, priorEventId);
            }
            bookingRequestedEvent.fire(new BookingRequested(booking.id)); // re-approval request
        } else {
            if (calendarPort.isConnected(type.ownerId) && booking.googleEventId != null) {
                calendarPort.updateEvent(type.ownerId, booking.googleEventId, newStartUtc, newEnd);
            }
            bookingRescheduledEvent.fire(new BookingRescheduled(booking.id, oldStart));
        }
        return booking;
    }

    /**
     * Reconciles the booking's guests to {@code guestEmails}. Returns the ids of guests transitioned to
     * REMOVED (so the caller can fire cancel emails after commit). A null list is a no-op (returns empty).
     */
    private List<Long> reconcileGuests(Booking booking, List<String> guestEmails) {
        if (guestEmails == null) {
            return List.of();
        }
        List<String> wanted = normalizeGuestEmails(guestEmails, booking.inviteeEmail);
        java.util.Set<String> wantedLower = new java.util.HashSet<>();
        for (String e : wanted) wantedLower.add(e.toLowerCase());

        // Existing rows for this booking, keyed by lowercase email.
        java.util.Map<String, BookingGuest> existing = new java.util.HashMap<>();
        for (BookingGuest g : BookingGuest.<BookingGuest>allForBooking(booking.id)) {
            existing.put(g.email.toLowerCase(), g);
        }

        // Add or re-activate wanted guests.
        for (String email : wanted) {
            BookingGuest g = existing.get(email.toLowerCase());
            if (g == null) {
                g = new BookingGuest();
                g.ownerId = booking.ownerId;
                g.bookingId = booking.id;
                g.email = email;
                g.declineToken = UUID.randomUUID().toString();
                g.createdAt = Instant.now();
                g.status = GuestStatus.INVITED;
                g.persist();
            } else if (g.status != GuestStatus.INVITED) {
                g.status = GuestStatus.INVITED; // re-invited a previously removed/declined guest
            }
        }

        // Remove active guests no longer wanted.
        List<Long> removed = new ArrayList<>();
        for (BookingGuest g : existing.values()) {
            if (g.status == GuestStatus.INVITED && !wantedLower.contains(g.email.toLowerCase())) {
                g.status = GuestStatus.REMOVED;
                removed.add(g.id);
            }
        }
        return removed;
    }
```

- [ ] **Step 7: Add declineGuest(...)**

Add this method to `BookingService` (after `cancel(...)`, at the end of the class before the closing brace):

```java
    @Transactional
    public void declineGuest(String declineToken) {
        BookingGuest guest = BookingGuest.findByDeclineToken(declineToken);
        if (guest == null) {
            throw new NotFoundException("No guest for token " + declineToken);
        }
        if (guest.status == GuestStatus.DECLINED) {
            return; // idempotent: a second decline click is a no-op
        }
        guest.status = GuestStatus.DECLINED;
        guestDeclinedEvent.fire(new GuestDeclined(guest.bookingId, guest.id));
    }
```

- [ ] **Step 8: Update the JSON BookingResource caller**

In `src/main/java/site/asm0dey/calit/booking/BookingResource.java`, update the `bookingService.book(...)` call (lines 54–56) to pass an empty guest list (the JSON API does not support guests):

```java
        Booking b = bookingService.book(owner.id, req.slug(), Instant.parse(req.startUtc()),
                req.inviteeName(), req.inviteeEmail(), req.answers(), req.turnstileToken(), req.honeypot(),
                locale, java.util.List.of());
```

(The JSON `reschedule` keeps calling the 2-arg `bookingService.reschedule(manageToken, instant)` — guests untouched. No change there.)

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn test -Dtest=BookingServiceGuestTest`
Expected: PASS (4 methods).

- [ ] **Step 10: Run the existing booking tests to confirm no regression**

Run: `mvn test -Dtest=BookingPostTest,ManageBookingTest,BookingResourceTest`
Expected: PASS (existing callers compile against the unchanged 2-arg reschedule and the new book signature — `BookingPostTest`/`ManageBookingTest` go through `PublicResource`, updated in Task 5; this run confirms the JSON-path change and 2-arg overload didn't break them).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/site/asm0dey/calit/booking/events/GuestDeclined.java \
        src/main/java/site/asm0dey/calit/booking/events/GuestRemoved.java \
        src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/main/java/site/asm0dey/calit/booking/BookingResource.java \
        src/test/java/site/asm0dey/calit/booking/BookingServiceGuestTest.java
git commit -m "feat(guests): book/reschedule/decline guest logic + reconciliation + ics_sequence bump"
```

---

### Task 4: EmailService guest fan-out + templates + i18n

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` (new keys)
- Create: `src/main/resources/templates/email/guest-invite.html`
- Create: `src/main/resources/templates/email/guest-cancel.html`
- Create: `src/main/resources/templates/email/guest-declined.html`
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Test: `src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java`

**Interfaces:**
- Consumes: `BookingGuest.activeForBooking`, `BookingGuest.findById`, `Booking.icsSequence`, `IcsBuilder.build(...,method,sequence)`, events `GuestDeclined`/`GuestRemoved`.
- Produces: guest emails. Observers `onGuestDeclined`, `onGuestRemoved`. Guest emails fire on `BookingConfirmed`, `BookingApproved`, `BookingRescheduled` (invite/update, REQUEST) and `BookingCancelled` (CANCEL). NOT on `BookingRequested`/`BookingDeclined` (booking not real to guests yet). New `AppMessages` methods: `email_role_guest()`, `email_guest_invite_title()`, `email_guest_invite_body(String inviterName)`, `email_guest_decline_link_text()`, `email_guest_cancel_title()`, `email_guest_cancel_body()`, `email_guest_declined_subject(String meetingTypeName)`, `email_guest_declined_title()`, `email_guest_declined_body(String guestEmail)`.

- [ ] **Step 1: Add the i18n message keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, add these methods next to the other `email_*` entries (e.g. after `email_role_owner()` ~line 451):

```java
    @Message("guest")
    String email_role_guest();

    @Message("You're invited to a meeting")
    String email_guest_invite_title();

    @Message("{inviterName} has invited you to a meeting.")
    String email_guest_invite_body(String inviterName);

    @Message("Can't attend? Decline this invitation")
    String email_guest_decline_link_text();

    @Message("Meeting cancelled")
    String email_guest_cancel_title();

    @Message("This meeting has been cancelled. It has been removed from your calendar.")
    String email_guest_cancel_body();

    @Message("A guest declined: {meetingTypeName}")
    String email_guest_declined_subject(String meetingTypeName);

    @Message("A guest can't attend")
    String email_guest_declined_title();

    @Message("{guestEmail} declined your meeting invitation. You may want to reschedule.")
    String email_guest_declined_body(String guestEmail);
```

- [ ] **Step 2: Write the guest email templates**

Create `src/main/resources/templates/email/guest-invite.html`:

```html
{#include email/layout}
{#title}{msg:email_guest_invite_title}{/title}
<p>{msg:email_guest_invite_body(inviteeName)}</p>
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
  <li><strong>{msg:email_body_duration_label}</strong> {msg:email_body_duration_minutes(durationMinutes)}</li>
  {#include email/_location /}
</ul>
<p><a href="{declineGuestUrl}">{msg:email_guest_decline_link_text}</a></p>
{/include}
```

Create `src/main/resources/templates/email/guest-cancel.html`:

```html
{#include email/layout}
{#title}{msg:email_guest_cancel_title}{/title}
<p>{msg:email_guest_cancel_body}</p>
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
</ul>
{/include}
```

Create `src/main/resources/templates/email/guest-declined.html`:

```html
{#include email/layout}
{#title}{msg:email_guest_declined_title}{/title}
<p>{msg:email_guest_declined_body(guestEmail)}</p>
<ul>
  <li><strong>{msg:email_body_meeting_label}</strong> {meetingTypeName}</li>
  <li><strong>{msg:email_body_when_label}</strong> {startTime}</li>
</ul>
<p><a href="{manageUrl}">{msg:email_body_manage_link_text}</a></p>
{/include}
```

(`email/_location` reads `location` + `isMeetLink`; `email/layout` reads `lang`, `greetingName`, `recipientRoleDisplay`. All are supplied in Step 4.)

- [ ] **Step 3: Write the failing email test**

Create `src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java`:

```java
package site.asm0dey.calit.email;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailServiceGuestTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final String GUEST_EMAIL = "guest@example.com";

    @Inject EmailService emailService;
    @Inject MockMailbox mailbox;
    @InjectMock CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Google connected: invitee/owner suppression must NOT affect guests (they always get calit mail).
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        QuarkusTransaction.requiringNew().run(() -> {
            BookingGuest.deleteAll();
            Booking.deleteAll();
        });
    }

    /** Seeds a CONFIRMED booking with one INVITED guest; returns the booking id. */
    private long seedWithGuest(GuestStatus guestStatus) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner"; s.ownerEmail = OWNER_EMAIL; s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true; s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L; t.name = "Discovery Call"; t.slug = "disc-" + System.nanoTime();
            t.durationMinutes = 30; t.locationType = LocationType.GOOGLE_MEET; t.persist();
            Booking b = new Booking();
            b.ownerId = 1L; b.meetingTypeId = t.id; b.inviteeName = "Sam Invitee"; b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z"); b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.meetLink = "https://meet.google.com/abc"; b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-" + System.nanoTime(); b.createdAt = Instant.now(); b.icsSequence = 0;
            b.persist();
            BookingGuest g = new BookingGuest();
            g.ownerId = 1L; g.bookingId = b.id; g.email = GUEST_EMAIL; g.status = guestStatus;
            g.declineToken = "dt-" + System.nanoTime(); g.createdAt = Instant.now(); g.persist();
            return b.id;
        });
    }

    @Test
    void confirmedSendsGuestInviteWithDeclineLinkAndIcsEvenWhenGoogleConnected() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST_EMAIL);
        assertEquals(1, toGuest.size(), "guest always gets calit mail (no Google path for guests)");
        Mail m = toGuest.getFirst();
        assertTrue(m.getHtml().contains("/guest/"), "guest decline link present");
        assertTrue(m.getHtml().contains("/decline"), "guest decline link suffix");
        assertFalse(m.getHtml().contains("/manage"), "guest must NOT get a manage/reschedule link");
        assertFalse(m.getAttachments().isEmpty(), "guest .ics attached");
    }

    @Test
    void cancelledSendsGuestCancel() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleCancelled(new BookingCancelled(bookingId));

        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "active guest gets a cancellation");
        assertTrue(mailbox.getMailsSentTo(GUEST_EMAIL).getFirst().getSubject().toLowerCase().contains("cancel"));
    }

    @Test
    void declinedGuestStatusGetsNoInviteOnConfirmed() {
        long bookingId = seedWithGuest(GuestStatus.DECLINED);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(mailbox.getMailsSentTo(GUEST_EMAIL).isEmpty(), "declined guest is not an active recipient");
    }

    @Test
    void guestDeclinedNotifiesInviteeAndCancelsThatGuest() {
        long bookingId = seedWithGuest(GuestStatus.DECLINED); // already declined in the DB
        Long guestId = BookingGuest.<BookingGuest>find("bookingId", bookingId).firstResult().id;

        emailService.handleGuestDeclined(new GuestDeclined(bookingId, guestId));

        // Guest gets a cancel; invitee gets a "guest declined, you may want to reschedule" notice.
        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "departing guest gets a cancel .ics");
        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee notified of the decline");
        assertTrue(toInvitee.getFirst().getHtml().contains("/manage"), "invitee notice links to reschedule");
    }

    @Test
    void guestRemovedSendsCancelToThatGuestOnly() {
        long bookingId = seedWithGuest(GuestStatus.REMOVED);
        Long guestId = BookingGuest.<BookingGuest>find("bookingId", bookingId).firstResult().id;

        emailService.handleGuestRemoved(new GuestRemoved(bookingId, guestId));

        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "removed guest gets a cancel");
        assertTrue(mailbox.getMailsSentTo(INVITEE_EMAIL).isEmpty(), "invitee initiated removal — not notified");
    }
}
```

- [ ] **Step 4: Wire guest fan-out into EmailService**

In `src/main/java/site/asm0dey/calit/email/EmailService.java`:

Add imports:

```java
import site.asm0dey.calit.booking.BookingGuest;
```

Add the role constant and the URL data key near the other constants (after line 41 / line 57):

```java
    private static final String GUEST_ROLE = "guest";
    public static final String DECLINE_GUEST_URL = "declineGuestUrl";
    public static final String GUEST_EMAIL_DATA = "guestEmail";
```

Inject the three new templates after the existing template fields (after line 101):

```java
    @Inject
    @Location("email/guest-invite.html")
    Template guestInvite;

    @Inject
    @Location("email/guest-cancel.html")
    Template guestCancel;

    @Inject
    @Location("email/guest-declined.html")
    Template guestDeclinedNotice;
```

Add the two observers after `onCancelled` (after line 179):

```java
    void onGuestDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestDeclined e) {
        handleGuestDeclined(e);
    }

    void onGuestRemoved(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestRemoved e) {
        handleGuestRemoved(e);
    }
```

In `handleConfirmed`, `handleApproved`, and `handleRescheduled`, add a guest-invite fan-out **after** the `sendForKindLocaleAware(...)` call (before the method's closing brace). For `handleConfirmed` (after line 250) and `handleApproved` (after line 283):

```java
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(l.meetingType.name));
```

For `handleRescheduled` (after line 359), reuse the reschedule subject:

```java
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_rescheduled_subject(l.meetingType.name));
```

In `handleCancelled`, after the `sendForKindLocaleAware(...)` call (after line 386):

```java
        sendGuestCancels(l, messages.forLocale(inviteeLocale).email_cancelled_subject(l.meetingType.name));
```

Add the guest helper methods (place them after `handleReminder`/`deliverReminder`, before `sendForKindLocaleAware`):

```java
    // --- guest fan-out: guests are notified ONLY by calit .ics (no Google path), always. ---

    /** REQUEST .ics + invite body to every active guest, in the booking (invitee's) locale. */
    private void sendGuestInvites(Loaded l, String location, String subject) {
        List<BookingGuest> guests = BookingGuest.activeForBooking(l.booking.id);
        if (guests.isEmpty()) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        for (BookingGuest g : guests) {
            byte[] ics = guestIcs(l, g, location, "REQUEST");
            String body = guestInvite.instance().setLocale(locale)
                    .data(RECIPIENT_ROLE, GUEST_ROLE)
                    .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_guest())
                    .data("lang", locale.getLanguage())
                    .data(GREETING_NAME, g.email)
                    .data(INVITEE_NAME, l.booking.inviteeName)
                    .data(MEETING_TYPE_NAME, l.meetingType.name)
                    .data(START_TIME, start)
                    .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                    .data(LOCATION, location)
                    .data(IS_MEET_LINK, isMeet(l))
                    .data(DECLINE_GUEST_URL, declineGuestUrl(g))
                    .render();
            mailSender.send(g.email, subject, body, ics);
        }
    }

    /** CANCEL .ics + cancel body to every active guest. */
    private void sendGuestCancels(Loaded l, String subject) {
        List<BookingGuest> guests = BookingGuest.activeForBooking(l.booking.id);
        if (guests.isEmpty()) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        for (BookingGuest g : guests) {
            mailSender.send(g.email, subject, guestCancelBody(l, g, locale), guestIcs(l, g, null, "CANCEL"));
        }
    }

    void handleGuestRemoved(GuestRemoved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        BookingGuest guest = QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(e.guestId()));
        if (guest == null) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        mailSender.send(guest.email, messages.forLocale(locale).email_cancelled_subject(l.meetingType.name),
                guestCancelBody(l, guest, locale), guestIcs(l, guest, null, "CANCEL"));
    }

    void handleGuestDeclined(GuestDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        BookingGuest guest = QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(e.guestId()));
        if (guest == null) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        // 1) cancel .ics to the departing guest
        mailSender.send(guest.email, messages.forLocale(locale).email_cancelled_subject(l.meetingType.name),
                guestCancelBody(l, guest, locale), guestIcs(l, guest, null, "CANCEL"));
        // 2) notify the invitee so they can reschedule
        String inviteeBody = guestDeclinedNotice.instance().setLocale(locale)
                .data("lang", locale.getLanguage())
                .data(GREETING_NAME, l.booking.inviteeName)
                .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_invitee())
                .data(GUEST_EMAIL_DATA, guest.email)
                .data(MEETING_TYPE_NAME, l.meetingType.name)
                .data(START_TIME, start)
                .data(MANAGE_URL, manageUrl(l.booking))
                .render();
        mailSender.send(l.booking.inviteeEmail,
                messages.forLocale(locale).email_guest_declined_subject(l.meetingType.name), inviteeBody, null);
    }

    /** Renders the guest cancel body in the given locale. */
    private String guestCancelBody(Loaded l, BookingGuest g, Locale locale) {
        return guestCancel.instance().setLocale(locale)
                .data(RECIPIENT_ROLE, GUEST_ROLE)
                .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_guest())
                .data("lang", locale.getLanguage())
                .data(GREETING_NAME, g.email)
                .data(MEETING_TYPE_NAME, l.meetingType.name)
                .data(START_TIME, format(l.booking.startUtc, l.zone, locale))
                .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                .render();
    }

    /** Builds a guest .ics: owner is ORGANIZER, this guest is the ATTENDEE, SEQUENCE = booking.icsSequence. */
    private byte[] guestIcs(Loaded l, BookingGuest g, String location, String method) {
        return IcsBuilder.build(l.booking.manageToken, l.meetingType.name, location,
                        new IcsBuilder.Party(l.owner.ownerName, l.owner.ownerEmail),
                        new IcsBuilder.Party(g.email, g.email),
                        l.booking.startUtc, l.booking.endUtc, method, l.booking.icsSequence)
                .getBytes(StandardCharsets.UTF_8);
    }

    private String declineGuestUrl(BookingGuest g) {
        return baseUrl + "/guest/" + g.declineToken + "/decline";
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=EmailServiceGuestTest`
Expected: PASS (5 methods).

- [ ] **Step 6: Run the existing email tests for no regression**

Run: `mvn test -Dtest=EmailServiceTest,EmailRoleCopyTest,EmailLocaleTest`
Expected: PASS (guest fan-out only adds mail when guests exist; these seed none).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/templates/email/guest-invite.html \
        src/main/resources/templates/email/guest-cancel.html \
        src/main/resources/templates/email/guest-declined.html \
        src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/test/java/site/asm0dey/calit/email/EmailServiceGuestTest.java
git commit -m "feat(guests): guest .ics email fan-out (invite/cancel/decline) + templates + i18n"
```

---

### Task 5: Web layer — chips input, guest parsing, decline pages

**Files:**
- Create: `src/main/resources/templates/PublicResource/_guestschips.html`
- Modify: `src/main/resources/templates/PublicResource/book.html`
- Modify: `src/main/resources/templates/PublicResource/manage.html`
- Create: `src/main/resources/templates/PublicResource/guestDeclineConfirm.html`
- Create: `src/main/resources/templates/PublicResource/guestDeclined.html`
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java`
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java` (web strings)
- Test: `src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java`

**Interfaces:**
- Consumes: `bookingService.book(...,guestEmails)`, `bookingService.reschedule(token,instant,guestEmails)`, `bookingService.declineGuest(token)`, `BookingGuest.activeForBooking/findByDeclineToken`.
- Produces: `book` template gains an `initialGuests` String param; `manage` template gains an `initialGuests` String param; new `Templates.guestDeclineConfirm(String title, Booking booking, MeetingType type, String guestEmail, String tzScript)` and `Templates.guestDeclined(String title)`. Routes `GET/POST /guest/{declineToken}/decline`. New `AppMessages`: `pub_book_guests_label()`, `pub_book_guests_hint()`, `pub_guest_decline_confirm_title()`, `pub_guest_decline_confirm_h1()`, `pub_guest_decline_confirm_desc()`, `pub_guest_decline_confirm_btn()`, `pub_guest_decline_keep_btn()`, `pub_guest_declined_title()`, `pub_guest_declined_h1()`, `pub_guest_declined_desc()`, `pub_guest_declined_btn()`.

- [ ] **Step 1: Add the web i18n keys**

In `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, add near the other `pub_*` entries:

```java
    @Message("Guests (optional)")
    String pub_book_guests_label();

    @Message("Type an email and press Enter")
    String pub_book_guests_hint();

    @Message("Decline invitation")
    String pub_guest_decline_confirm_title();

    @Message("Decline this invitation?")
    String pub_guest_decline_confirm_h1();

    @Message("You'll be removed from this meeting and won't receive further updates.")
    String pub_guest_decline_confirm_desc();

    @Message("Decline")
    String pub_guest_decline_confirm_btn();

    @Message("Keep my spot")
    String pub_guest_decline_keep_btn();

    @Message("Invitation declined")
    String pub_guest_declined_title();

    @Message("You've declined")
    String pub_guest_declined_h1();

    @Message("You've been removed from this meeting. The organizer has been notified.")
    String pub_guest_declined_desc();

    @Message("Done")
    String pub_guest_declined_btn();
```

- [ ] **Step 2: Write the chips widget include**

Create `src/main/resources/templates/PublicResource/_guestschips.html`:

```html
{@java.lang.String initial}
<label class="label" for="guest-entry">{msg:pub_book_guests_label}</label>
<div id="guests-chips" class="flex flex-wrap gap-2 mb-2"></div>
<input id="guest-entry" class="input w-full" type="email" autocomplete="off"
       placeholder="{msg:pub_book_guests_hint}">
{! Source of truth: pre-filled with existing guests so a no-JS reschedule still submits them.
   The script below clears the visible field and lets chips own this value. !}
<input type="hidden" name="guests" id="guests-data" value="{initial ?: ''}">
<script>
(function () {
  var entry = document.getElementById('guest-entry');
  var data = document.getElementById('guests-data');
  var chips = document.getElementById('guests-chips');
  if (!entry || !data || !chips) return;
  var list = (data.value || '').split(/[,\s]+/).filter(Boolean);
  function sync() { data.value = list.join(','); render(); }
  function render() {
    chips.textContent = '';
    list.forEach(function (email, i) {
      var chip = document.createElement('span');
      chip.className = 'badge badge-neutral gap-1';
      chip.textContent = email;
      var x = document.createElement('button');
      x.type = 'button'; x.className = 'btn btn-xs btn-circle btn-ghost'; x.textContent = '×';
      x.addEventListener('click', function () { list.splice(i, 1); sync(); });
      chip.appendChild(x);
      chips.appendChild(chip);
    });
  }
  function add() {
    var v = entry.value.trim().replace(/[,\s]+$/, '');
    if (v && list.indexOf(v) === -1) list.push(v);
    entry.value = ''; sync();
  }
  entry.addEventListener('keydown', function (e) {
    if ((e.key === 'Enter' || e.key === 'Tab' || e.key === ',') && entry.value.trim()) {
      e.preventDefault(); add();
    }
  });
  entry.addEventListener('blur', add);
  render();
})();
</script>
```

- [ ] **Step 3: Add the chips field to the booking form**

In `src/main/resources/templates/PublicResource/book.html`, add a new param declaration after line 13 (`{@java.lang.String ownerName}`):

```html
{@java.lang.String initialGuests}
```

Then inside the `<fieldset>`, after the invitee email input block (after line 77, before the `{#for f in fields}` loop on line 78), include the widget:

```html
                {#include PublicResource/_guestschips initial=initialGuests /}
```

- [ ] **Step 4: Add the chips field to the manage form**

First inspect `src/main/resources/templates/PublicResource/manage.html` to find its reschedule `<form method="post" ...>` and its param declarations at the top. Add a param declaration:

```html
{@java.lang.String initialGuests}
```

Inside the reschedule `<form>` (which posts to `/booking/{booking.manageToken}/reschedule`), after the CSRF hidden input and before the submit button, include the widget:

```html
                {#include PublicResource/_guestschips initial=initialGuests /}
```

(If `manage.html`'s reschedule control is rendered per-slot rather than as a single form wrapping a guests field, wrap the slot grid + a single guests field + one submit button in one `<form>` so the `guests` value posts alongside the chosen `startUtc`. Keep the existing CSRF hidden input.)

- [ ] **Step 5: Write the decline pages**

Create `src/main/resources/templates/PublicResource/guestDeclineConfirm.html`:

```html
{@java.lang.String title}
{@site.asm0dey.calit.booking.Booking booking}
{@site.asm0dey.calit.domain.MeetingType type}
{@java.lang.String guestEmail}
{@java.lang.String tzScript}
{#include base title=title}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <div class="card-body items-start gap-3">
      <h1 class="text-2xl font-bold">{msg:pub_guest_decline_confirm_h1}</h1>
      <p class="text-base-content/70">{msg:pub_guest_decline_confirm_desc}</p>
      <ul class="list-disc ms-5">
        <li><strong>{msg:pub_booking_meeting_label}</strong> {type.name}</li>
        <li><strong>{msg:pub_booking_when_label}</strong> <time data-utc="{booking.startUtc}">{booking.startUtc} UTC</time></li>
      </ul>
      <div class="flex gap-2">
        <form method="post" action="/guest/{guestDeclineToken}/decline"><input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}"><button type="submit" class="btn btn-error">{msg:pub_guest_decline_confirm_btn}</button></form>
        <a class="btn btn-ghost" href="/">{msg:pub_guest_decline_keep_btn}</a>
      </div>
    </div>
  </div>
  {tzScript.raw}
{/include}
```

Note: the form action needs the decline token. Pass it as a template variable named `guestDeclineToken` (add `{@java.lang.String guestDeclineToken}` to the param block at the top and to the `Templates.guestDeclineConfirm` signature). Update the param block to:

```html
{@java.lang.String title}
{@site.asm0dey.calit.booking.Booking booking}
{@site.asm0dey.calit.domain.MeetingType type}
{@java.lang.String guestEmail}
{@java.lang.String guestDeclineToken}
{@java.lang.String tzScript}
```

Create `src/main/resources/templates/PublicResource/guestDeclined.html`:

```html
{@java.lang.String title}
{#include base title=title}
  <div class="card bg-base-100 border border-base-300 shadow-sm max-w-xl mx-auto">
    <div class="card-body items-start">
      <h1 class="text-2xl font-bold">{msg:pub_guest_declined_h1}</h1>
      <p class="text-base-content/70">{msg:pub_guest_declined_desc}</p>
      <a class="btn btn-primary mt-2" href="/">{msg:pub_guest_declined_btn}</a>
    </div>
  </div>
{/include}
```

(`pub_booking_meeting_label` / `pub_booking_when_label` already exist — they're used by `cancelConfirm.html`.)

- [ ] **Step 6: Write the failing web flow test**

Create `src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java`:

```java
package site.asm0dey.calit.web;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class GuestBookingFlowTest {

    @InjectMock CalendarPort calendarPort;
    @Inject MockMailbox mailbox;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("gob");
        if (owner == null) { owner = AppUser.create("gob", "x", false); owner.persistAndFlush(); }
        Long ownerId = owner.id;
        Booking.delete("ownerId", ownerId);
        BookingGuest.delete("ownerId", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "g-type");
        AvailabilityRule.delete("ownerId", ownerId);
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam"; s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId; t.name = "G Type"; t.slug = "g-type"; t.durationMinutes = 30;
        t.locationType = LocationType.PHONE; t.locationDetail = "+1 555"; t.persist();
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId; r.dayOfWeek = d; r.startTime = LocalTime.of(0, 0); r.endTime = LocalTime.of(23, 59);
            r.meetingTypeId = null; r.persist();
        }
    }

    private String firstSlot() {
        String html = given().when().get("/gob/g-type").then().statusCode(200).extract().asString();
        String s = html.substring(html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return s.substring(0, s.indexOf('"'));
    }

    @Test
    void bookingFormShowsGuestsField() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        given().when().get("/gob/g-type").then().statusCode(200)
                .body(containsString("name=\"guests\""));
    }

    @Test
    void postWithGuestsCreatesGuestRowsAndEmailsThem() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        mailbox.clear();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com, bob@example.com")
                .when().post("/gob/g-type").then().statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        assertNotNull(b);
        assertEquals(2, BookingGuest.activeForBooking(b.id).size());
        assertEquals(1, mailbox.getMailsSentTo("ana@example.com").size());
        assertEquals(1, mailbox.getMailsSentTo("bob@example.com").size());
    }

    @Test
    void guestDeclineLinkMarksDeclinedAndNotifiesInvitee() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com")
                .when().post("/gob/g-type").then().statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        String token = BookingGuest.activeForBooking(b.id).getFirst().declineToken;
        mailbox.clear();

        // Confirmation page renders.
        given().when().get("/guest/" + token + "/decline").then().statusCode(200)
                .body(containsString("Decline"));
        // POST declines (CSRF is off in %test).
        given().contentType("application/x-www-form-urlencoded")
                .when().post("/guest/" + token + "/decline").then().statusCode(200);

        assertEquals(GuestStatus.DECLINED, BookingGuest.findByDeclineToken(token).status);
        assertEquals(1, mailbox.getMailsSentTo("sam@example.com").size(), "invitee notified of the decline");
    }

    @Test
    void rescheduleEditsGuestList() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com, bob@example.com")
                .when().post("/gob/g-type").then().statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        String manageToken = b.manageToken;
        // A new slot (the manage page lists fresh slots); reuse the booking form's first slot as a free one.
        String newSlot = firstSlot();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", newSlot)
                .formParam("guests", "ana@example.com, cyd@example.com") // drop bob, add cyd
                .when().post("/booking/" + manageToken + "/reschedule").then().statusCode(200);

        assertEquals(GuestStatus.REMOVED, BookingGuest.findInBooking(b.id, "bob@example.com").status);
        assertEquals(2, BookingGuest.activeForBooking(b.id).size());
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `mvn test -Dtest=GuestBookingFlowTest`
Expected: FAIL — `Templates.book(...)`/`manage(...)` lack the `initialGuests` arg, decline routes 404, `book.html` has no `guests` field (compile + assertion failures).

- [ ] **Step 8: Update PublicResource — template signatures, guest parsing, decline routes**

In `src/main/java/site/asm0dey/calit/web/PublicResource.java`:

Add `initialGuests` to the `book` native template method (after the `String ownerName` param, line 56):

```java
                String ownerName,
                String initialGuests);
```

Add `initialGuests` to the `manage` native method (after `String calScript`, line 68):

```java
                String tzBar, String tzScript, String calScript, String initialGuests);
```

Add two new native methods inside `Templates`:

```java
        public static native TemplateInstance guestDeclineConfirm(
                String title, site.asm0dey.calit.booking.Booking booking,
                site.asm0dey.calit.domain.MeetingType type, String guestEmail,
                String guestDeclineToken, String tzScript);

        public static native TemplateInstance guestDeclined(String title);
```

Update the three existing `Templates.book(...)` call sites to pass an `initialGuests` argument. In `book(...)` (GET, line 170) and the catch-block re-render in `submitBooking(...)` (line 227), new bookings have no guests yet — pass `""`:

```java
        return Templates.book(bookTitle, owner.username, type, byDate, fields, null,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                              turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(owner.id),
                              settings.ownerName, "");
```

```java
            return Templates.book(bookTitle, owner.username, type, daySlots(type), BookingField.formFor(owner.id, type.id),
                                  be.getMessage(),
                                  Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT,
                                  turnstileEnabled, turnstileSiteKey(), calendarPort.isConnected(owner.id),
                                  settings.ownerName, "");
```

In `submitBooking(...)`, parse the `guests` form field and pass it to `book(...)`. Replace the `bookingService.book(...)` call (lines 219–221) with:

```java
            booking = bookingService.book(
                    owner.id, slug, Instant.parse(startUtc), inviteeName, inviteeEmail, answers,
                    turnstileToken, website, locale, parseGuests(form));
```

Add the parse helper (place it near `daySlots`, after `turnstileSiteKey()`):

```java
    /** Splits the optional "guests" form field on commas/whitespace into a clean email list. */
    private static List<String> parseGuests(MultivaluedMap<String, String> form) {
        String raw = form.getFirst("guests");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
```

Update the `manage(...)` handler (line 279) to load existing guests and pass them as a CSV. Replace the `return Templates.manage(...)` line with:

```java
        String guestsCsv = BookingGuest.activeForBooking(booking.id).stream()
                .map(g -> g.email).collect(java.util.stream.Collectors.joining(","));
        return Templates.manage(m.pub_manage_title(), booking, current, currentUtcIso, byDate,
                                Layout.TZ_BAR, Layout.TZ_SCRIPT, Layout.CALENDAR_SCRIPT, guestsCsv);
```

Update the `rescheduleBooking(...)` handler (line 287) to accept the form and pass parsed guests to the reconciling overload:

```java
    @POST
    @Path("/booking/{manageToken}/reschedule")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance rescheduleBooking(@PathParam("manageToken") String manageToken,
                                              @RestForm String startUtc,
                                              MultivaluedMap<String, String> form) {
        Booking booking = bookingService.reschedule(manageToken, Instant.parse(startUtc), parseGuests(form));
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return confirmationPage(booking, type);
    }
```

Add the decline routes (place after `cancelBooking(...)`, before `resolveOwner(...)`):

```java
    @GET
    @Path("/guest/{declineToken}/decline")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance guestDeclineConfirm(@PathParam("declineToken") String declineToken) {
        var m = messages.forLocale(activeLocale.current());
        BookingGuest guest = BookingGuest.findByDeclineToken(declineToken); // unguessable key
        if (guest == null) {
            throw new NotFoundException("No guest for token " + declineToken);
        }
        if (guest.status != GuestStatus.INVITED) {
            return Templates.guestDeclined(m.pub_guest_declined_title()); // already declined/removed
        }
        Booking booking = Booking.findById(guest.bookingId);
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        return Templates.guestDeclineConfirm(m.pub_guest_decline_confirm_title(), booking, type,
                guest.email, declineToken, Layout.TZ_SCRIPT);
    }

    @POST
    @Path("/guest/{declineToken}/decline")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance guestDecline(@PathParam("declineToken") String declineToken) {
        var m = messages.forLocale(activeLocale.current());
        bookingService.declineGuest(declineToken); // keyed by the token; idempotent
        return Templates.guestDeclined(m.pub_guest_declined_title());
    }
```

Add the import for `GuestStatus` (the `booking.*` wildcard import on line 12 already covers `BookingGuest`/`GuestStatus` — verify; if `web` uses explicit imports, add `import site.asm0dey.calit.booking.GuestStatus;` and `BookingGuest`).

- [ ] **Step 9: Build CSS and run the test to verify it passes**

Run: `bun run css:build && mvn test -Dtest=GuestBookingFlowTest`
Expected: PASS (5 methods).

- [ ] **Step 10: Run the broader web/booking suite for no regression**

Run: `mvn test -Dtest=BookingPostTest,ManageBookingTest,PublicI18nTest`
Expected: PASS (`book`/`manage` callers now pass `initialGuests`; existing forms still post).

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/templates/PublicResource/_guestschips.html \
        src/main/resources/templates/PublicResource/book.html \
        src/main/resources/templates/PublicResource/manage.html \
        src/main/resources/templates/PublicResource/guestDeclineConfirm.html \
        src/main/resources/templates/PublicResource/guestDeclined.html \
        src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/test/java/site/asm0dey/calit/web/GuestBookingFlowTest.java
git commit -m "feat(guests): chips input, guest parsing, reschedule guest edit, decline pages"
```

---

### Task 6: Full suite + docs

**Files:**
- Modify (on `docs-site` branch): `docs-site/src/content/docs/...` (usage page) + changelog

**Interfaces:** none (verification + documentation).

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: PASS. (Docker required. If a guest test interferes with a committed-seed test ordering, confirm each guest test cleans `BookingGuest`/`Booking` in its `@BeforeEach`/`seed()` as written.)

- [ ] **Step 2: Manual smoke (optional but recommended)**

Run: `bun run css:build && mvn quarkus:dev` (pass dummy `-DGOOGLE_OAUTH_*` per the dev-server memory). Book a meeting with two guest chips; confirm both guests receive an email with an `.ics` and a decline link in the Dev Services mock mailer; click a decline link; confirm the invitee gets a "guest declined" mail.

- [ ] **Step 3: Update docs on the `docs-site` branch**

Per CLAUDE.md, user-facing changes land on `docs-site` same-effort. Add to the usage docs a short "Guests" subsection: invitees may add guest emails (up to 10) when booking or when rescheduling; guests receive calendar invites and updates, can decline via a link, but cannot reschedule or cancel. Add a changelog entry at the top of `docs-site/src/content/docs/releases/changelog.md` describing the guests feature. (Do this on the `docs-site` branch in a separate worktree/checkout; it is not part of the `main` build.)

- [ ] **Step 4: Commit docs (on docs-site branch)**

```bash
git add docs-site/
git commit -m "docs(guests): invitee guests usage + changelog entry"
```

---

## Self-Review

**Spec coverage:**
- "add guests from the side of invitee" → Task 5 chips on the booking form; Task 3 `book(...,guestEmails)` + `persistGuests`.
- "receive the same updates — created, rescheduled, cancel" → Task 4 fan-out on `BookingConfirmed`/`BookingApproved` (created), `BookingRescheduled` (rescheduled), `BookingCancelled` (cancel).
- "can't reschedule or cancel themselves" → guests get only a decline link; no manage/cancel link (asserted in `EmailServiceGuestTest.confirmedSendsGuestInvite...`); no token grants them reschedule/cancel.
- "can decline to participate" → Task 3 `declineGuest`, Task 4 `GuestDeclined` (guest cancel + invitee notice), Task 5 decline pages.
- "get event into their calendars" → guest `.ics` (REQUEST) on every invite/update; CANCEL on removal/decline/cancel (Task 2 + Task 4).
- "chips input, type emails, Enter/Tab creates a chip" → Task 5 `_guestschips.html`.
- "when invitee reschedules, change the guest list" → Task 3 `reconcileGuests` + Task 5 pre-filled manage chips.
- "when invitee cancels, everyone gets cancellation" → Task 4 `sendGuestCancels` on `BookingCancelled`.

**Placeholder scan:** No TBD/TODO; every code step shows full code; every test step shows the test body.

**Type consistency:** `book(...)` 10-arg + `reschedule(token,instant,List)` overload + 2-arg delegator are consistent across Tasks 3/5 and `BookingResource`. `GuestStatus{INVITED,DECLINED,REMOVED}` used consistently. `IcsBuilder.build(...,method,sequence)` signature matches all call sites in Task 4. Template param additions (`initialGuests`, `guestDeclineConfirm`/`guestDeclined`) match the `PublicResource.Templates` method signatures. `EmailService` data keys (`DECLINE_GUEST_URL`, `GUEST_EMAIL_DATA`) match the template variables (`declineGuestUrl`, `guestEmail`).

**Known simplifications (ponytail):**
- `MailSender` MIME part stays `method=REQUEST` for CANCEL `.ics` (body METHOD is authoritative; matches existing invitee behavior).
- Guest cap is a constant, not config (no new env var / docs knob).
- Reschedule "kept" guests get the same invite template as "added" (one template; the SEQUENCE bump updates their calendar).
- Chips UI requires JS to *add* guests; existing guests survive a no-JS reschedule via the pre-filled hidden field.
