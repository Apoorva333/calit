# Multi-host Meeting Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a meeting type require multiple hosts; slots are offered only when every host is free, one booking blocks all hosts' calendars, and every co-host must consent before the type is bookable.

**Architecture:** A new `meeting_type_host` join table holds each host (creator + co-hosts) with consent status and optional per-host buffers. A multi-host booking writes **N `booking` rows** (one per host, linked by a new `booking.group_id`) for calit-side availability blocking, but creates **one** Google event on a chosen organizer host with all others invited. Slot generation intersects each host's own availability. All lifecycle transitions (approve/decline/cancel/reschedule/remind/expire) operate on the whole group and fan out email one-per-host + one-to-invitee.

**Tech Stack:** Quarkus 3.36 / Java 25, Panache (public-field entities, no getters/setters), Qute `@CheckedTemplate`, Flyway (validate-only Hibernate), Postgres (GiST exclusion constraint, `btree_gist`), quarkus-rest-csrf, RestAssured + `@QuarkusTest` (Docker Dev Services Postgres), palantir-java-format.

## Global Constraints

- **Owner scoping:** every tenant row carries `owner_id`; every query filters by the acting owner. `CurrentOwner.require()` → 401 if unset. One user must never read/write another's data. (Co-host availability rows are scoped to the *co-host's* own `owner_id` + the shared `meeting_type_id`.)
- **Never edit an applied Flyway migration** (checksum). Add new `V*.sql` only. Latest existing is `V19`; new files are `V20`, `V21`, `V22`.
- **Hibernate is validate-only** — migrations own the schema; new entity fields must have a matching migration column.
- **Entities are Panache with public fields**, no getters/setters (Qute reads fields directly).
- **i18n:** every new user-facing string is a `@Message` method (English default inline) in `AppMessages` (`{msg:}`) or `AdminMessages` (`{adm:}`), **plus** a line in each of the two matching property files (`msg_de`+`msg_he` or `adm_de`+`adm_he`), keyed by method name. No English `.properties` file exists.
- **Progressive enhancement** (CLAUDE.md rule, already updated): every feature works without JS; JS only enhances. New inline scripts lead with a `/* CALIT_<FEATURE> — ... */` marker comment that a RestAssured test asserts on (RestAssured can't run JS).
- **CSRF:** every POST form includes `<input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">`. CSRF is on in prod, stubbed off in `%test`.
- **Admin user is always `id = 1`** (`DatabaseResetCallback` reseeds per test). Write owner-scoped tests against that invariant; use `TestOwners.ensure(em, id)` to create extra owners.
- **`maven.compiler.parameters=true`** is required for Qute param names — already set; new `@CheckedTemplate` signatures just work.
- **Test one class:** `mvn test -Dtest=ClassName`; one method: `mvn test -Dtest=ClassName#method`. Docker must be running.
- **CLAUDE.md is out of scope** (already changed). **docs-site changelog + docs page are in scope** (Phase 7).
- **Format before every commit:** `mvn spotless:apply` (or rely on the lefthook pre-commit hook that runs `spotless:apply` on staged `*.java`).

## File Structure

**New Java (`src/main/java/site/asm0dey/calit/`):**
- `domain/MeetingTypeHost.java` — join entity: host membership + consent + per-host buffers + queries.
- `booking/MeetingHosts.java` — `@ApplicationScoped` service: host-set queries, add/remove/accept co-host, bookability gate, organizer selection, slug-collision enforcement, eligibility. One place for host-set logic so `BookingService`/resources stay focused.
- `booking/events/HostConsentRequested.java` — CDI event (co-host invited → send consent email).
- `web/SharedMeetingsResource.java` — `@Path("/me/shared")` `@RolesAllowed("user")`: Shared list, co-host consent accept/decline (dashboard), co-host per-type availability editor.
- `web/ConsentResource.java` — `@Path("/consent")` public one-click token accept/decline.
- `web/HostSuggestResource.java` — `@Path("/me/hosts")` owner-auth JSON typeahead endpoint.

**New migrations (`src/main/resources/db/migration/`):**
- `V20__meeting_type_host.sql`, `V21__booking_group_id.sql`, `V22__owner_scope_no_overlap.sql`.

**New templates (`src/main/resources/templates/`):**
- `SharedMeetingsResource/{shared,consentRequests,sharedAvailability}.html`, `ConsentResource/{confirm,done}.html`, plus a `_hostlist.html` partial for the meeting-type edit form.
- New email templates under `templates/email/`: `hostConsent.html`, and host-role branches reuse existing `requested`/`confirmation`/etc. via the `recipientRole` param.

**Modified Java:**
- `domain/MeetingType.java` — `listPublicIncludingCohosted`, rename/create slug-collision hooks.
- `booking/Booking.java` — add `group_id` field + group queries.
- `availability/SlotService.java` — per-host `generateRawSlots` overload.
- `booking/BookingService.java` — host-set-aware `availableSlots`/`busyIntervals`/`assertSlotAvailable`; group booking write; group approve/decline/cancel/reschedule/edit; organizer selection; group Google event; per-group abuse cap.
- `email/EmailService.java` — group fan-out (one per host + invitee), host-role emails, consent email, group-aware reminder/decline enqueue.
- `scheduler/ReminderScheduler.java`, `scheduler/PendingExpiryScheduler.java` — group-aware (lead row only / whole group).
- `web/PublicResource.java` — alias resolution, owner-context = creator, disabled-with-reason page, landing union.
- `web/AdminResource.java` — Main vs Shared split, add/remove co-host on the meeting-type form, revoke interstitial, slug-collision guard on create/rename.
- `web/Layout.java` — `HOST_TYPEAHEAD_SCRIPT` constant (marker `CALIT_HOST_TYPEAHEAD`).
- `i18n/AppMessages.java`, `i18n/AdminMessages.java` + 4 property files.

**Modified docs (`docs-site` branch — Phase 7):** `docs-site/src/content/docs/releases/changelog.md`, a usage page, `README.md` image tag if a release is cut.

---

## Phase 0 — Schema & entities

### Task 1: `meeting_type_host` migration + entity

**Files:**
- Create: `src/main/resources/db/migration/V20__meeting_type_host.sql`
- Create: `src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java`
- Test: `src/test/java/site/asm0dey/calit/domain/MeetingTypeHostTest.java`

**Interfaces:**
- Produces: entity `MeetingTypeHost` with public fields `Long id, meetingTypeId, ownerId; String status, role; UUID consentToken; Integer bufferBeforeMinutes, bufferAfterMinutes; Instant createdAt, respondedAt;` and statics `List<MeetingTypeHost> forType(Long meetingTypeId)`, `List<MeetingTypeHost> acceptedForType(Long meetingTypeId)`, `MeetingTypeHost find(Long meetingTypeId, Long ownerId)`, `MeetingTypeHost findByConsentToken(String token)`, `List<MeetingTypeHost> cohostedTypesFor(Long ownerId)`, `boolean isMultiHost(Long meetingTypeId)`. Status constants `PENDING`/`ACCEPTED`; role constants `CREATOR`/`COHOST`.

- [ ] **Step 1: Write the migration**

`V20__meeting_type_host.sql`:
```sql
-- Feature: multi-host meeting types. One row per host (creator + co-hosts) of a meeting type.
-- Single-host types have NO rows here. Multi-host = >=1 COHOST row (+ one CREATOR row).
CREATE TABLE meeting_type_host (
    id                    BIGSERIAL   PRIMARY KEY,
    meeting_type_id       BIGINT      NOT NULL REFERENCES meeting_type(id) ON DELETE CASCADE,
    owner_id              BIGINT      NOT NULL REFERENCES app_user(id)     ON DELETE CASCADE,
    status                VARCHAR(16) NOT NULL,   -- PENDING | ACCEPTED
    role                  VARCHAR(16) NOT NULL,   -- CREATOR | COHOST
    consent_token         UUID,                   -- one-click email accept; cleared on response
    buffer_before_minutes INT,                    -- per-host override; NULL = inherit type
    buffer_after_minutes  INT,                    -- per-host override; NULL = inherit type
    created_at            TIMESTAMPTZ NOT NULL,
    responded_at          TIMESTAMPTZ,
    CONSTRAINT uq_meeting_type_host UNIQUE (meeting_type_id, owner_id)
);
CREATE INDEX idx_mth_owner ON meeting_type_host (owner_id);
CREATE INDEX idx_mth_type  ON meeting_type_host (meeting_type_id);
```

- [ ] **Step 2: Write the failing test**

`MeetingTypeHostTest.java`:
```java
package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class MeetingTypeHostTest {

    @Inject EntityManager em;

    private MeetingType type(long ownerId, String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId; t.name = slug; t.slug = slug; t.durationMinutes = 30;
        t.persist();
        return t;
    }

    @Test
    @TestTransaction
    void findsHostsAndDetectsMultiHost() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        MeetingType t = type(1L, "intro");

        MeetingTypeHost creator = MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED);
        creator.persist();
        MeetingTypeHost cohost = MeetingTypeHost.of(t.id, 2L, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        cohost.consentToken = UUID.randomUUID();
        cohost.persist();

        assertTrue(MeetingTypeHost.isMultiHost(t.id));
        assertEquals(2, MeetingTypeHost.forType(t.id).size());
        assertEquals(1, MeetingTypeHost.acceptedForType(t.id).size());
        assertNotNull(MeetingTypeHost.findByConsentToken(cohost.consentToken.toString()));
        assertEquals(2L, MeetingTypeHost.find(t.id, 2L).ownerId);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=MeetingTypeHostTest`
Expected: compile failure (`MeetingTypeHost` doesn't exist) / then FAIL.

- [ ] **Step 4: Write the entity**

`MeetingTypeHost.java`:
```java
package site.asm0dey.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meeting_type_host")
public class MeetingTypeHost extends PanacheEntityBase {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String CREATOR = "CREATOR";
    public static final String COHOST = "COHOST";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(nullable = false, length = 16)
    public String role;

    @Column(name = "consent_token")
    public UUID consentToken;

    @Column(name = "buffer_before_minutes")
    public Integer bufferBeforeMinutes;

    @Column(name = "buffer_after_minutes")
    public Integer bufferAfterMinutes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "responded_at")
    public Instant respondedAt;

    public static MeetingTypeHost of(Long meetingTypeId, Long ownerId, String role, String status) {
        MeetingTypeHost h = new MeetingTypeHost();
        h.meetingTypeId = meetingTypeId;
        h.ownerId = ownerId;
        h.role = role;
        h.status = status;
        h.createdAt = Instant.now();
        return h;
    }

    public boolean accepted() {
        return ACCEPTED.equals(status);
    }

    public static List<MeetingTypeHost> forType(Long meetingTypeId) {
        return list("meetingTypeId", meetingTypeId);
    }

    public static List<MeetingTypeHost> acceptedForType(Long meetingTypeId) {
        return list("meetingTypeId = ?1 and status = ?2", meetingTypeId, ACCEPTED);
    }

    public static MeetingTypeHost find(Long meetingTypeId, Long ownerId) {
        return find("meetingTypeId = ?1 and ownerId = ?2", meetingTypeId, ownerId).firstResult();
    }

    public static MeetingTypeHost findByConsentToken(String token) {
        return find("consentToken", UUID.fromString(token)).firstResult();
    }

    /** Rows where this owner is a co-host (any status), for their "Shared" list. */
    public static List<MeetingTypeHost> cohostedTypesFor(Long ownerId) {
        return list("ownerId = ?1 and role = ?2", ownerId, COHOST);
    }

    /** True once the type has any co-host row (i.e. it is multi-host). */
    public static boolean isMultiHost(Long meetingTypeId) {
        return count("meetingTypeId = ?1 and role = ?2", meetingTypeId, COHOST) > 0;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=MeetingTypeHostTest`
Expected: PASS (Flyway applies V20 at boot; Hibernate validate passes).

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/resources/db/migration/V20__meeting_type_host.sql \
        src/main/java/site/asm0dey/calit/domain/MeetingTypeHost.java \
        src/test/java/site/asm0dey/calit/domain/MeetingTypeHostTest.java
git commit -m "feat(multi-host): meeting_type_host join table + entity"
```

---

### Task 2: `booking.group_id` migration + Booking group queries

**Files:**
- Create: `src/main/resources/db/migration/V21__booking_group_id.sql`
- Modify: `src/main/java/site/asm0dey/calit/booking/Booking.java`
- Test: `src/test/java/site/asm0dey/calit/booking/BookingGroupQueryTest.java`

**Interfaces:**
- Produces: `Booking.groupId` (`UUID`, nullable); statics `List<Booking> group(UUID groupId)`, `Booking leadOfGroup(UUID groupId, Long creatorOwnerId)` (row whose `ownerId == creatorOwnerId`), `long countDistinctBookingsByEmailBetween(String email, Instant from, Instant to)`.

- [ ] **Step 1: Write the migration**

`V21__booking_group_id.sql`:
```sql
-- Multi-host: a booking spans N rows (one per host), linked by group_id. NULL = single-host.
ALTER TABLE booking ADD COLUMN group_id UUID;
CREATE INDEX idx_booking_group ON booking (group_id) WHERE group_id IS NOT NULL;
```

- [ ] **Step 2: Write the failing test**

`BookingGroupQueryTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class BookingGroupQueryTest {

    @Inject EntityManager em;

    private Booking row(UUID group, long ownerId) {
        Booking b = new Booking();
        b.ownerId = ownerId; b.meetingTypeId = 1L; b.inviteeName = "Sam"; b.inviteeEmail = "sam@x.com";
        b.startUtc = Instant.parse("2030-01-01T09:00:00Z"); b.endUtc = Instant.parse("2030-01-01T09:30:00Z");
        b.status = BookingStatus.CONFIRMED; b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString(); b.groupId = group;
        b.persist();
        return b;
    }

    @Test
    @TestTransaction
    void groupAndLeadResolve() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        UUID g = UUID.randomUUID();
        row(g, 1L);   // creator/lead
        row(g, 2L);   // cohost
        assertEquals(2, Booking.group(g).size());
        assertEquals(1L, Booking.leadOfGroup(g, 1L).ownerId);
    }

    @Test
    @TestTransaction
    void abuseCapCountsPerGroupNotPerRow() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        UUID g = UUID.randomUUID();
        row(g, 1L); row(g, 2L);          // one multi-host booking = 2 rows
        row(null, 1L);                    // one single-host booking = 1 row
        long n = Booking.countDistinctBookingsByEmailBetween(
                "sam@x.com", Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"));
        assertEquals(2, n);               // 2 conceptual bookings, not 3 rows
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=BookingGroupQueryTest`
Expected: compile failure (`groupId`, `group`, `leadOfGroup`, `countDistinctBookingsByEmailBetween` missing).

- [ ] **Step 4: Add the field + queries to `Booking.java`**

Add field near `icsSequence`:
```java
@Column(name = "group_id")
public UUID groupId;
```
(add `import java.util.UUID;` if absent). Add statics next to the other query methods:
```java
public static List<Booking> group(UUID groupId) {
    return list("groupId", groupId);
}

/** The creator's row in a group (the invitee-facing lead). */
public static Booking leadOfGroup(UUID groupId, Long creatorOwnerId) {
    return find("groupId = ?1 and ownerId = ?2", groupId, creatorOwnerId).firstResult();
}

/** Counts one per conceptual booking: standalone rows + distinct group_id. */
public static long countDistinctBookingsByEmailBetween(String email, Instant dayStart, Instant dayEnd) {
    Long singles = getEntityManager()
            .createQuery("select count(b) from Booking b where b.inviteeEmail = :e "
                    + "and b.createdAt >= :s and b.createdAt < :d and b.groupId is null", Long.class)
            .setParameter("e", email).setParameter("s", dayStart).setParameter("d", dayEnd)
            .getSingleResult();
    Long groups = getEntityManager()
            .createQuery("select count(distinct b.groupId) from Booking b where b.inviteeEmail = :e "
                    + "and b.createdAt >= :s and b.createdAt < :d and b.groupId is not null", Long.class)
            .setParameter("e", email).setParameter("s", dayStart).setParameter("d", dayEnd)
            .getSingleResult();
    return singles + groups;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=BookingGroupQueryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/resources/db/migration/V21__booking_group_id.sql \
        src/main/java/site/asm0dey/calit/booking/Booking.java \
        src/test/java/site/asm0dey/calit/booking/BookingGroupQueryTest.java
git commit -m "feat(multi-host): booking.group_id + group/lead/abuse-cap queries"
```

---

### Task 3: Owner-scope the double-booking exclusion constraint

**Files:**
- Create: `src/main/resources/db/migration/V22__owner_scope_no_overlap.sql`
- Test: `src/test/java/site/asm0dey/calit/booking/OverlapConstraintTest.java`

**Interfaces:**
- Produces: DB guarantee — same owner cannot hold two overlapping `PENDING`/`CONFIRMED` bookings; **different** owners overlapping at the same time is allowed.

- [ ] **Step 1: Write the migration**

`V22__owner_scope_no_overlap.sql`:
```sql
-- The V4 constraint forbade overlaps instance-wide (ignored owner_id) -- a latent multi-tenant bug
-- AND it would make per-host multi-host rows collide. Re-scope it to the owner.
ALTER TABLE booking DROP CONSTRAINT booking_no_overlap_held;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
    EXCLUDE USING gist (owner_id WITH =, tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status IN ('PENDING', 'CONFIRMED'));
```

- [ ] **Step 2: Write the failing test**

`OverlapConstraintTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class OverlapConstraintTest {

    @Inject EntityManager em;

    private Booking held(long ownerId) {
        Booking b = new Booking();
        b.ownerId = ownerId; b.meetingTypeId = 1L; b.inviteeName = "S"; b.inviteeEmail = "s@x.com";
        b.startUtc = Instant.parse("2030-01-01T09:00:00Z"); b.endUtc = Instant.parse("2030-01-01T09:30:00Z");
        b.status = BookingStatus.CONFIRMED; b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        return b;
    }

    @Test
    @TestTransaction
    void differentOwnersMayOverlap() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        held(1L).persist();
        held(2L).persist();
        em.flush();   // no exception -- different owners at same time is allowed
    }

    @Test
    @TestTransaction
    void sameOwnerMayNotOverlap() {
        TestOwners.ensure(em, 1L);
        held(1L).persist();
        held(1L).persist();
        assertThrows(PersistenceException.class, em::flush);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=OverlapConstraintTest`
Expected: `differentOwnersMayOverlap` FAILS against the old instance-wide constraint (flush throws).

- [ ] **Step 4: Apply the migration** (already written in Step 1 — no code change). Re-run.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=OverlapConstraintTest`
Expected: both PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V22__owner_scope_no_overlap.sql \
        src/test/java/site/asm0dey/calit/booking/OverlapConstraintTest.java
git commit -m "feat(multi-host): owner-scope booking_no_overlap_held exclusion constraint"
```

---

## Phase 1 — Host-set service, consent, slug collisions

### Task 4: `MeetingHosts` service — host set, bookability gate, organizer, eligibility

**Files:**
- Create: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java`
- Modify: `src/main/java/site/asm0dey/calit/google/CalendarPort.java` is unchanged; inject it.
- Test: `src/test/java/site/asm0dey/calit/booking/MeetingHostsTest.java`

**Interfaces:**
- Produces:
  - `List<Long> hostOwnerIds(MeetingType type)` — `[creator]` for single-host; creator + accepted co-host ids for multi-host.
  - `boolean bookable(MeetingType type)` — single-host → `true`; multi-host → all host rows `ACCEPTED` **and** all host `AppUser.enabled`.
  - `Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds)` — creator if `calendarPort.isConnected(creator)`, else lowest hostId with `isConnected`, else `null`.
  - `int effectiveBufferBefore(MeetingType type, Long hostOwnerId)` / `effectiveBufferAfter(...)` — host-row override or type value.
  - `boolean eligibleCohost(Long meetingTypeId, Long creatorOwnerId, AppUser candidate)` — `enabled ∧ settingsComplete ∧ not-creator ∧ not-already-a-host`.

- [ ] **Step 1: Write the failing test**

`MeetingHostsTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsTest {

    @Inject MeetingHosts meetingHosts;
    @Inject EntityManager em;
    @InjectMock CalendarPort calendarPort;

    private MeetingType multiHostType() {
        // admin id 1 is the creator; make a second enabled user as co-host.
        AppUser cohost = AppUser.create("volodya", "x", false);
        cohost.settingsComplete = true;
        cohost.persist();
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "intro"; t.slug = "intro"; t.durationMinutes = 30;
        t.bufferBeforeMinutes = 5; t.bufferAfterMinutes = 10;
        t.persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost c = MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        c.persist();
        return t;
    }

    @Test
    @TestTransaction
    void notBookableUntilAllAccepted() {
        MeetingType t = multiHostType();
        assertFalse(meetingHosts.bookable(t));
        MeetingTypeHost.forType(t.id).forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        assertTrue(meetingHosts.bookable(t));
        assertEquals(2, meetingHosts.hostOwnerIds(t).size());
    }

    @Test
    @TestTransaction
    void organizerPrefersCreatorThenLowestConnected() {
        MeetingType t = multiHostType();
        List<Long> hosts = meetingHosts.hostOwnerIds(t);
        when(calendarPort.isConnected(1L)).thenReturn(true);
        assertEquals(1L, meetingHosts.chooseOrganizer(t, hosts));
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        assertNull(meetingHosts.chooseOrganizer(t, hosts));
    }

    @Test
    @TestTransaction
    void perHostBufferOverridesType() {
        MeetingType t = multiHostType();
        MeetingTypeHost creator = MeetingTypeHost.find(t.id, 1L);
        creator.bufferBeforeMinutes = 20;      // override
        em.flush();
        assertEquals(20, meetingHosts.effectiveBufferBefore(t, 1L));   // overridden
        assertEquals(10, meetingHosts.effectiveBufferAfter(t, 1L));    // inherits type
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MeetingHostsTest`
Expected: compile failure (`MeetingHosts` missing).

- [ ] **Step 3: Write `MeetingHosts.java`**

```java
package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

/** Host-set logic for multi-host meeting types: membership, bookability gate, organizer choice,
 *  per-host buffers, co-host eligibility. Single-host types short-circuit to the creator alone. */
@ApplicationScoped
public class MeetingHosts {

    public static final int MAX_HOSTS = 10;

    private final CalendarPort calendarPort;

    @Inject
    public MeetingHosts(CalendarPort calendarPort) {
        this.calendarPort = calendarPort;
    }

    /** Owner ids that must all be free: [creator] for single-host; creator + accepted co-hosts otherwise. */
    public List<Long> hostOwnerIds(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return List.of(type.ownerId);
        }
        List<Long> ids = new ArrayList<>();
        for (MeetingTypeHost h : MeetingTypeHost.acceptedForType(type.id)) {
            ids.add(h.ownerId);
        }
        return ids;
    }

    /** Single-host is always bookable. Multi-host requires every host ACCEPTED and every host enabled. */
    public boolean bookable(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return true;
        }
        List<MeetingTypeHost> hosts = MeetingTypeHost.forType(type.id);
        for (MeetingTypeHost h : hosts) {
            if (!h.accepted()) {
                return false;
            }
            AppUser u = AppUser.findById(h.ownerId);
            if (u == null || !u.enabled) {
                return false;
            }
        }
        return true;
    }

    /** Creator if connected, else the lowest-id connected host, else null (no Google event). */
    public Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds) {
        if (calendarPort.isConnected(type.ownerId)) {
            return type.ownerId;
        }
        Long chosen = null;
        for (Long id : hostOwnerIds) {
            if (calendarPort.isConnected(id) && (chosen == null || id < chosen)) {
                chosen = id;
            }
        }
        return chosen;
    }

    public int effectiveBufferBefore(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferBeforeMinutes != null) ? h.bufferBeforeMinutes : type.bufferBeforeMinutes;
    }

    public int effectiveBufferAfter(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferAfterMinutes != null) ? h.bufferAfterMinutes : type.bufferAfterMinutes;
    }

    public boolean eligibleCohost(Long meetingTypeId, Long creatorOwnerId, AppUser candidate) {
        if (candidate == null || !candidate.enabled || !candidate.settingsComplete) {
            return false;
        }
        if (candidate.id.equals(creatorOwnerId)) {
            return false;
        }
        return MeetingTypeHost.find(meetingTypeId, candidate.id) == null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MeetingHostsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/MeetingHosts.java \
        src/test/java/site/asm0dey/calit/booking/MeetingHostsTest.java
git commit -m "feat(multi-host): MeetingHosts service (host set, gate, organizer, buffers, eligibility)"
```

---

### Task 5: Add/remove/accept co-host + consent, with cap & idempotency

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java`
- Create: `src/main/java/site/asm0dey/calit/booking/events/HostConsentRequested.java`
- Test: `src/test/java/site/asm0dey/calit/booking/MeetingHostsMutationTest.java`

**Interfaces:**
- Produces (all `@Transactional` on `MeetingHosts`):
  - `void addCohost(MeetingType type, AppUser candidate)` — ensures a `CREATOR` `ACCEPTED` row exists, inserts a `COHOST` `PENDING` row with a fresh `consentToken`, fires `HostConsentRequested(type.id, candidate.id, token)`. **Idempotent**: existing `PENDING` co-host → no-op (no new row/email). Throws `IllegalStateException` if host count would exceed `MAX_HOSTS` or slug collides (Task 6 wires collision).
  - `void acceptConsent(MeetingTypeHost host)` — `status=ACCEPTED`, `respondedAt=now`, `consentToken=null`.
  - `void removeHost(MeetingType type, Long cohostOwnerId)` — delete the co-host row; if no `COHOST` rows remain, delete the `CREATOR` row too (revert to single-host).
  - `HostConsentRequested(Long meetingTypeId, Long cohostOwnerId, String consentToken)` event record.

- [ ] **Step 1: Write the event record**

`events/HostConsentRequested.java`:
```java
package site.asm0dey.calit.booking.events;

public record HostConsentRequested(Long meetingTypeId, Long cohostOwnerId, String consentToken) {}
```

- [ ] **Step 2: Write the failing test**

`MeetingHostsMutationTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsMutationTest {

    @Inject MeetingHosts meetingHosts;
    @Inject EntityManager em;

    static final AtomicInteger CONSENTS = new AtomicInteger();
    void onConsent(@Observes HostConsentRequested e) { CONSENTS.incrementAndGet(); }

    private AppUser enabledUser(String name) {
        AppUser u = AppUser.create(name, "x", false);
        u.settingsComplete = true; u.persist();
        return u;
    }

    private MeetingType type() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "intro"; t.slug = "intro"; t.durationMinutes = 30; t.persist();
        return t;
    }

    @Test
    @TestTransaction
    void addCohostIsIdempotentAndCreatesCreatorRow() {
        CONSENTS.set(0);
        MeetingType t = type();
        AppUser v = enabledUser("volodya");
        meetingHosts.addCohost(t, v);
        meetingHosts.addCohost(t, v);   // idempotent -- still one pending row, one email
        em.flush();
        assertEquals(2, MeetingTypeHost.forType(t.id).size());   // creator + one cohost
        assertEquals(1, CONSENTS.get());
        assertEquals(MeetingTypeHost.CREATOR, MeetingTypeHost.find(t.id, 1L).role);
    }

    @Test
    @TestTransaction
    void removeLastCohostRevertsToSingleHost() {
        MeetingType t = type();
        AppUser v = enabledUser("volodya");
        meetingHosts.addCohost(t, v);
        em.flush();
        meetingHosts.removeHost(t, v.id);
        em.flush();
        assertEquals(0, MeetingTypeHost.forType(t.id).size());   // creator row gone too
        assertFalse(MeetingTypeHost.isMultiHost(t.id));
    }

    @Test
    @TestTransaction
    void capRejectsEleventhHost() {
        MeetingType t = type();
        for (int i = 0; i < 9; i++) meetingHosts.addCohost(t, enabledUser("h" + i));
        em.flush();   // 1 creator + 9 cohosts = 10
        AppUser extra = enabledUser("overflow");
        assertThrows(IllegalStateException.class, () -> meetingHosts.addCohost(t, extra));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=MeetingHostsMutationTest`
Expected: compile failure (`addCohost`/`removeHost` missing).

- [ ] **Step 4: Add mutation methods to `MeetingHosts.java`**

Add imports: `jakarta.enterprise.event.Event; jakarta.transaction.Transactional; java.time.Instant; java.util.UUID; site.asm0dey.calit.booking.events.HostConsentRequested;`. Inject the event:
```java
@Inject Event<HostConsentRequested> consentEvent;
```
Add methods:
```java
@Transactional
public void addCohost(MeetingType type, AppUser candidate) {
    MeetingTypeHost existing = MeetingTypeHost.find(type.id, candidate.id);
    if (existing != null) {
        return; // idempotent: already a host (pending or accepted) -> no-op, no repeat email
    }
    ensureCreatorRow(type);
    long hostCount = MeetingTypeHost.count("meetingTypeId", type.id);
    if (hostCount >= MAX_HOSTS) {
        throw new IllegalStateException("A meeting can have at most " + MAX_HOSTS + " hosts.");
    }
    // ponytail: constant cap, revisit only if a real use-case needs more
    MeetingTypeHost h = MeetingTypeHost.of(type.id, candidate.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
    h.consentToken = UUID.randomUUID();
    h.persist();
    consentEvent.fire(new HostConsentRequested(type.id, candidate.id, h.consentToken.toString()));
}

private void ensureCreatorRow(MeetingType type) {
    if (MeetingTypeHost.find(type.id, type.ownerId) == null) {
        MeetingTypeHost.of(type.id, type.ownerId, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
    }
}

@Transactional
public void acceptConsent(MeetingTypeHost host) {
    host.status = MeetingTypeHost.ACCEPTED;
    host.respondedAt = Instant.now();
    host.consentToken = null;
    host.persist();
}

@Transactional
public void removeHost(MeetingType type, Long cohostOwnerId) {
    MeetingTypeHost.delete("meetingTypeId = ?1 and ownerId = ?2", type.id, cohostOwnerId);
    if (MeetingTypeHost.count("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.COHOST) == 0) {
        MeetingTypeHost.delete("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.CREATOR);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=MeetingHostsMutationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/MeetingHosts.java \
        src/main/java/site/asm0dey/calit/booking/events/HostConsentRequested.java \
        src/test/java/site/asm0dey/calit/booking/MeetingHostsMutationTest.java
git commit -m "feat(multi-host): add/remove/accept co-host with cap + idempotent consent"
```

---

### Task 6: Symmetric slug-collision block

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java` (add `assertSlugFreeAcrossHosts`)
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingType.java` (add `slugUsedByOwner`)
- Test: `src/test/java/site/asm0dey/calit/booking/SlugCollisionTest.java`

**Interfaces:**
- Produces:
  - `MeetingType.slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId)` → boolean (owner already has a *different* type with that slug).
  - `MeetingHosts.assertSlugFreeForCohost(MeetingType type, AppUser candidate)` — throws `IllegalStateException` if `candidate` already owns a type with `type.slug`, or is a host of another type with that slug.
  - `MeetingHosts.assertSlugFreeAcrossHosts(MeetingType type, String newSlug)` — for create/rename of a multi-host type: slug must be free in every host's namespace.
- `addCohost` (Task 5) calls `assertSlugFreeForCohost` before inserting.

- [ ] **Step 1: Write the failing test**

`SlugCollisionTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlugCollisionTest {

    @Inject MeetingHosts meetingHosts;
    @Inject EntityManager em;

    private MeetingType type(long owner, String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = owner; t.name = slug; t.slug = slug; t.durationMinutes = 30; t.persist();
        return t;
    }

    @Test
    @TestTransaction
    void addCohostRejectedWhenCandidateOwnsSameSlug() {
        AppUser v = AppUser.create("volodya", "x", false);
        v.settingsComplete = true; v.persist();
        type(v.id, "intro");                       // Volodya already owns /volodya/intro
        MeetingType shared = type(1L, "intro");    // Pasha's shared intro
        assertThrows(IllegalStateException.class, () -> meetingHosts.addCohost(shared, v));
    }

    @Test
    @TestTransaction
    void slugUsedByOwnerExcludesSelf() {
        MeetingType t = type(1L, "intro");
        assertTrue(MeetingType.slugUsedByOwner(1L, "intro", null));
        assertFalse(MeetingType.slugUsedByOwner(1L, "intro", t.id));   // exclude the type itself
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SlugCollisionTest`
Expected: compile failure (`slugUsedByOwner`/collision guard missing).

- [ ] **Step 3: Add `slugUsedByOwner` to `MeetingType.java`**

```java
public static boolean slugUsedByOwner(Long ownerId, String slug, Long excludeTypeId) {
    if (excludeTypeId == null) {
        return count("ownerId = ?1 and slug = ?2", ownerId, slug) > 0;
    }
    return count("ownerId = ?1 and slug = ?2 and id <> ?3", ownerId, slug, excludeTypeId) > 0;
}
```

- [ ] **Step 4: Add collision guards to `MeetingHosts.java` and call from `addCohost`**

Add methods:
```java
public void assertSlugFreeForCohost(MeetingType type, AppUser candidate) {
    if (MeetingType.slugUsedByOwner(candidate.id, type.slug, null)) {
        throw new IllegalStateException(
                candidate.username + " already uses the slug " + type.slug
                        + " -- pick a different slug or ask them to free it.");
    }
    // also reject if candidate is a host of another multi-host type with this slug
    for (MeetingTypeHost h : MeetingTypeHost.cohostedTypesFor(candidate.id)) {
        MeetingType other = MeetingType.findById(h.meetingTypeId);
        if (other != null && !other.id.equals(type.id) && other.slug.equals(type.slug)) {
            throw new IllegalStateException(candidate.username + " already co-hosts a type with slug " + type.slug);
        }
    }
}

/** For create/rename of a shared type: slug must be free in every host's namespace. */
public void assertSlugFreeAcrossHosts(MeetingType type, String newSlug) {
    for (Long hostId : hostOwnerIds(type)) {
        if (!hostId.equals(type.ownerId) && MeetingType.slugUsedByOwner(hostId, newSlug, null)) {
            throw new IllegalStateException("A host already uses the slug " + newSlug);
        }
    }
}
```
In `addCohost`, add before persisting the co-host row (after the cap check):
```java
assertSlugFreeForCohost(type, candidate);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=SlugCollisionTest`
Expected: PASS. Also run Task 5's test to confirm no regression: `mvn test -Dtest=MeetingHostsMutationTest`.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/MeetingHosts.java \
        src/main/java/site/asm0dey/calit/domain/MeetingType.java \
        src/test/java/site/asm0dey/calit/booking/SlugCollisionTest.java
git commit -m "feat(multi-host): symmetric slug-collision block for co-hosts"
```

> **Note for Task 15/16:** `AdminResource.createMeetingType`/`editMeetingType` must call `meetingHosts.assertSlugFreeAcrossHosts(type, slug)` when the type is multi-host, and the personal-type create/rename path must reject a slug that collides with a type the owner co-hosts (`MeetingTypeHost.cohostedTypesFor(ownerId)`). Those calls are added in the admin-form tasks.

---

## Phase 2 — Slot intersection

### Task 7: Per-host `generateRawSlots` overload

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/availability/SlotService.java`
- Test: `src/test/java/site/asm0dey/calit/availability/SlotServicePerHostTest.java`

**Interfaces:**
- Produces: `List<TimeSlot> generateRawSlots(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to)` — grid/duration from `type`, but `OwnerSettings`/availability read for `hostOwnerId`. Existing `generateRawSlots(type, from, to)` delegates with `type.ownerId` (unchanged behavior).

- [ ] **Step 1: Write the failing test**

`SlotServicePerHostTest.java`:
```java
package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlotServicePerHostTest {

    @Inject SlotService slotService;

    @Test
    @TestTransaction
    void rawSlotsUseHostWindowsNotCreatorWindows() {
        // creator = admin id 1 (no availability), cohost has Monday 09:00-10:00
        AppUser cohost = AppUser.create("volodya", "x", false);
        cohost.settingsComplete = true; cohost.persist();
        OwnerSettings os = new OwnerSettings();
        os.ownerId = cohost.id; os.ownerName = "V"; os.ownerEmail = "v@x.com";
        os.timezone = "Europe/Amsterdam"; os.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "intro"; t.slug = "intro"; t.durationMinutes = 30; t.horizonDays = 50000;
        t.persist();

        LocalDate monday = LocalDate.now(ZoneId.of("Europe/Amsterdam"))
                .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = cohost.id; r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(10, 0);
        r.persist();

        List<TimeSlot> slots = slotService.generateRawSlots(t, cohost.id, monday, monday);
        assertEquals(2, slots.size());   // 09:00 and 09:30 (30-min grid within 09:00-10:00)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SlotServicePerHostTest`
Expected: compile failure (no 4-arg overload).

- [ ] **Step 3: Refactor `SlotService.java`**

Change the existing method to delegate, and parameterize `loadAvailability`:
```java
public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
    return generateRawSlots(type, type.ownerId, from, to);
}

public List<TimeSlot> generateRawSlots(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to) {
    OwnerSettings settings = OwnerSettings.forOwner(hostOwnerId);
    if (settings == null) {
        throw new IllegalStateException("Owner settings not configured for owner " + hostOwnerId
                + "; set them via /me/settings before generating slots.");
    }
    ZoneId zone = ZoneId.of(settings.timezone);
    Availability availability = loadAvailability(type, hostOwnerId, from, to);
    List<TimeSlot> slots = new ArrayList<>();
    for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
        for (Window window : availability.windowsFor(date)) {
            int step = type.effectiveSlotIntervalMinutes();
            int duration = type.durationMinutes;
            var startMin = window.start().toSecondOfDay() / 60;
            var endMin = window.end().toSecondOfDay() / 60;
            for (var s = startMin; s + duration <= endMin; s += step) {
                var start = LocalTime.ofSecondOfDay(s * 60L);
                var end = LocalTime.ofSecondOfDay((s + duration) * 60L);
                slots.add(new TimeSlot(date.atTime(start).atZone(zone), date.atTime(end).atZone(zone)));
            }
        }
    }
    return slots;
}
```
Change `loadAvailability` signature to `private Availability loadAvailability(MeetingType type, Long hostOwnerId, LocalDate from, LocalDate to)` and replace every `type.ownerId` inside it with `hostOwnerId` (the `type.id` uses stay — per-type rules are still keyed by the shared `meeting_type_id`, but under the host's own `owner_id`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SlotServicePerHostTest`
Expected: PASS. Regression: `mvn test -Dtest=BookServiceTest` (existing single-host path still green).

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/availability/SlotService.java \
        src/test/java/site/asm0dey/calit/availability/SlotServicePerHostTest.java
git commit -m "feat(multi-host): per-host generateRawSlots overload"
```

---

### Task 8: Intersecting `availableSlots` (host set, per-host buffers, fail-closed)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`
- Test: `src/test/java/site/asm0dey/calit/booking/AvailableSlotsIntersectionTest.java`

**Interfaces:**
- Consumes: `MeetingHosts.hostOwnerIds`, `MeetingHosts.bookable`, `MeetingHosts.effectiveBuffer{Before,After}`, `SlotService.generateRawSlots(type, hostId, from, to)`, `busyIntervals(hostId, ...)`.
- Produces: `availableSlots(type, from, to, excludeBookingId)` returns the **intersection** across all hosts; returns **empty** when the type is not `bookable` or any host's `freeBusy` throws `CalendarUnavailableException` (fail-closed). Single-host unchanged.

- [ ] **Step 1: Write the failing test**

`AvailableSlotsIntersectionTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AvailableSlotsIntersectionTest {

    @Inject BookingService bookingService;
    @Inject MeetingHosts meetingHosts;
    @InjectMock CalendarPort calendarPort;

    private final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private OwnerSettings settings(long id, String name) {
        OwnerSettings o = new OwnerSettings();
        o.ownerId = id; o.ownerName = name; o.ownerEmail = name + "@x.com"; o.timezone = "Europe/Amsterdam";
        o.persist(); return o;
    }
    private void rule(long owner, DayOfWeek d, int sh, int eh) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = owner; r.dayOfWeek = d; r.startTime = LocalTime.of(sh, 0); r.endTime = LocalTime.of(eh, 0);
        r.persist();
    }

    /** creator free 09-12, cohost free 10-12 -> intersection 10-12. */
    private MeetingType twoHostType() {
        settings(1L, "pasha");
        AppUser v = AppUser.create("volodya", "x", false); v.settingsComplete = true; v.persist();
        settings(v.id, "volodya");
        DayOfWeek mon = DayOfWeek.MONDAY;
        rule(1L, mon, 9, 12);
        rule(v.id, mon, 10, 12);
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "intro"; t.slug = "intro"; t.durationMinutes = 60; t.horizonDays = 50000;
        t.persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, v.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        return t;
    }

    @Test
    @TestTransaction
    void intersectsHostWindows() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        LocalDate mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        List<TimeSlot> slots = bookingService.availableSlots(t, mon, mon);
        // 60-min slots in 10:00-12:00 -> 10:00 and 11:00 only (09:00 excluded: cohost busy)
        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.get(0).start().withZoneSameInstant(AMS).toLocalTime());
    }

    @Test
    @TestTransaction
    void brokenHostCalendarFailsClosedToEmpty() {
        MeetingType t = twoHostType();
        // cohost id is the 2nd host; make its freeBusy throw
        Long cohostId = meetingHosts.hostOwnerIds(t).stream().filter(id -> id != 1L).findFirst().orElseThrow();
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.freeBusy(eq(cohostId), any(), any()))
                .thenThrow(new CalendarUnavailableException("needs reconnect"));
        LocalDate mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }

    @Test
    @TestTransaction
    void notBookableTypeYieldsNoSlots() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        MeetingTypeHost.find(t.id, meetingHosts.hostOwnerIds(t).get(1)).status = MeetingTypeHost.PENDING;
        MeetingTypeHost.find(t.id, 1L).persistAndFlush();
        LocalDate mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }
}
```
(If `CalendarUnavailableException` is in a different package, fix the import per the compiler — it lives in `site.asm0dey.calit.google` per the code map.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AvailableSlotsIntersectionTest`
Expected: FAIL (current `availableSlots` ignores co-hosts).

- [ ] **Step 3: Refactor `availableSlots` in `BookingService.java`**

Inject `MeetingHosts` (add to constructor params + field). Replace the 4-arg `availableSlots` body:
```java
public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to, Long excludeBookingId) {
    if (!meetingHosts.bookable(type)) {
        return List.of();
    }
    List<Long> hostIds = meetingHosts.hostOwnerIds(type);
    var now = Instant.now();
    Instant earliest = now.plusSeconds(60L * type.minNoticeMinutes);
    Instant latest = now.plus(type.horizonDays, ChronoUnit.DAYS);

    // Per-host free set, keyed by slot-start instant; a slot survives only if free for ALL hosts.
    Map<Instant, TimeSlot> candidate = null;
    for (Long hostId : hostIds) {
        ZoneId zone = ZoneId.of(OwnerSettings.forOwner(hostId).timezone);
        var fromInstant = from.atStartOfDay(zone).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Interval> busy;
        try {
            busy = busyIntervals(hostId, fromInstant, toInstant, excludeBookingId);
        } catch (CalendarUnavailableException failClosed) {
            return List.of(); // any host's calendar unverifiable -> offer nothing
        }
        int bufBefore = meetingHosts.effectiveBufferBefore(type, hostId);
        int bufAfter = meetingHosts.effectiveBufferAfter(type, hostId);
        Map<Instant, TimeSlot> hostFree = new LinkedHashMap<>();
        for (TimeSlot slot : slotService.generateRawSlots(type, hostId, from, to)) {
            Instant slotStart = slot.start().toInstant();
            if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) continue;
            Interval buffered = new Interval(
                    slotStart.minusSeconds(60L * bufBefore),
                    slot.end().toInstant().plusSeconds(60L * bufAfter));
            if (!buffered.overlapsAny(busy)) hostFree.put(slotStart, slot);
        }
        if (candidate == null) {
            candidate = hostFree;
        } else {
            candidate.keySet().retainAll(hostFree.keySet()); // intersection by start instant
        }
        if (candidate.isEmpty()) return List.of();
    }
    return candidate == null ? List.of() : new ArrayList<>(candidate.values());
}
```
Add imports: `java.util.LinkedHashMap; java.util.Map; site.asm0dey.calit.google.CalendarUnavailableException;`. Keep `busyIntervals` as-is (already per-owner). The 3-arg overload still delegates to this.

> **Design note:** slots are grouped by exact start instant. Since every host uses the same grid/duration from `type` and only their *windows* differ, equal start instants denote the same offered slot. The creator's `TimeSlot` (first host) is the one returned.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AvailableSlotsIntersectionTest`
Expected: PASS. Regression: `mvn test -Dtest=BookServiceTest`.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/AvailableSlotsIntersectionTest.java
git commit -m "feat(multi-host): intersect availableSlots across hosts, fail-closed"
```

---

## Phase 3 — Group booking write, approval, cancel/reschedule, edit

### Task 9: Group booking write + single Google event on organizer

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java`
- Test: `src/test/java/site/asm0dey/calit/booking/GroupBookingWriteTest.java`

**Interfaces:**
- Produces: `book(...)` for a multi-host type inserts **N** `booking` rows sharing a `group_id`; abuse cap counts per group; assertSlotAvailable checks the host set; auto-confirm creates **one** Google event on the organizer (attendees = invitee + all other host emails + guests) and stores `googleEventId`/`meetLink` on the organizer's row. Approval types insert N PENDING rows and fire one `BookingRequested(leadId)`.
- New helpers: `assertSlotAvailable(type, startUtc, excludeBookingId)` uses `availableSlots` (already host-set-aware after Task 8 — no change needed); `createGroupGoogleEvent(MeetingType type, UUID groupId)`.

- [ ] **Step 1: Write the failing test**

`GroupBookingWriteTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class GroupBookingWriteTest {

    @Inject BookingService bookingService;
    @Inject MeetingHosts meetingHosts;
    @InjectMock CalendarPort calendarPort;

    private final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private Instant nextMonday10() {
        LocalDate mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(10, 0).atZone(AMS).toInstant();
    }
    private void settings(long id, String n) {
        OwnerSettings o = new OwnerSettings();
        o.ownerId = id; o.ownerName = n; o.ownerEmail = n + "@x.com"; o.timezone = "Europe/Amsterdam"; o.persist();
    }
    private void rule(long owner) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = owner; r.dayOfWeek = DayOfWeek.MONDAY; r.startTime = LocalTime.of(9,0); r.endTime = LocalTime.of(17,0);
        r.persist();
    }
    private MeetingType type(boolean approval) {
        settings(1L, "pasha");
        AppUser v = AppUser.create("volodya", "x", false); v.settingsComplete = true; v.persist();
        settings(v.id, "volodya");
        rule(1L); rule(v.id);
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "intro"; t.slug = "intro"; t.durationMinutes = 60; t.horizonDays = 50000;
        t.requiresApproval = approval; t.persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, v.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        return t;
    }

    @Test
    @TestTransaction
    void autoConfirmWritesNRowsAndOneGoogleEvent() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet/x", "https://cal/x"));
        MeetingType t = type(false);

        Booking lead = bookingService.book(1L, "intro", nextMonday10(), "Sam", "sam@x.com",
                Map.of(), "tok", "", "en", List.of());

        assertNotNull(lead.groupId);
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        rows.forEach(r -> assertEquals(BookingStatus.CONFIRMED, r.status));
        // exactly one Google event, on the creator (organizer), with both hosts + invitee as attendees
        verify(calendarPort, times(1)).createEvent(eq(1L), anyString(), anyString(), any(), any(),
                argThat(a -> a.contains("sam@x.com") && a.contains("pasha@x.com") && a.contains("volodya@x.com")),
                eq(true), any());
        Booking organizerRow = Booking.leadOfGroup(lead.groupId, 1L);
        assertEquals("evt-1", organizerRow.googleEventId);
    }

    @Test
    @TestTransaction
    void approvalTypeWritesNPendingRowsNoEvent() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = type(true);
        Booking lead = bookingService.book(1L, "intro", nextMonday10(), "Sam", "sam@x.com",
                Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        rows.forEach(r -> { assertEquals(BookingStatus.PENDING, r.status); assertNotNull(r.approvalToken); });
        verify(calendarPort, never()).createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=GroupBookingWriteTest`
Expected: FAIL (book still writes a single row).

- [ ] **Step 3: Refactor `book` for the multi-host branch**

In `book(...)`, after `assertSlotAvailable(type, startUtc, null)` and after computing `endUtc`, branch on multi-host. Extract the existing single-row logic into a private `bookSingle(...)` (keep current behavior) and add `bookGroup(...)`:
```java
if (MeetingTypeHost.isMultiHost(type.id)) {
    return bookGroup(type, startUtc, endUtc, inviteeName, inviteeEmail, submitted, locale, guestEmails);
}
// ... existing single-host body unchanged ...
```
Change the abuse cap call `enforcePerEmailDailyCap` to use `Booking.countDistinctBookingsByEmailBetween` instead of `countByEmailCreatedBetween` (one line in that private method).

Add:
```java
private Booking bookGroup(MeetingType type, Instant startUtc, Instant endUtc, String inviteeName,
        String inviteeEmail, Map<String, String> answers, String locale, List<String> guestEmails) {
    UUID groupId = UUID.randomUUID();
    List<Long> hostIds = meetingHosts.hostOwnerIds(type);
    boolean approval = type.requiresApproval;
    Booking lead = null;
    try {
        for (Long hostId : hostIds) {
            Booking b = new Booking();
            b.ownerId = hostId;
            b.meetingTypeId = type.id;
            b.inviteeName = inviteeName;
            b.inviteeEmail = inviteeEmail;
            b.startUtc = startUtc;
            b.endUtc = endUtc;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.answers = answers;
            b.locale = AppLocales.isSupported(locale) ? locale : "en";
            b.status = approval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;
            if (approval) b.approvalToken = UUID.randomUUID().toString();
            b.groupId = groupId;
            b.persist();
            if (hostId.equals(type.ownerId)) lead = b;
        }
        getEntityManager().flush(); // surface booking_no_overlap_held now
    } catch (PersistenceException ex) {
        if (isNoOverlapViolation(ex)) throw new BookingConflictException("Slot no longer available");
        throw ex;
    }
    // guests attach to the lead row only
    persistGuests(lead, guestEmails);
    if (approval) {
        bookingRequestedEvent.fire(new BookingRequested(lead.id));
        return lead;
    }
    createGroupGoogleEvent(type, groupId);
    bookingConfirmedEvent.fire(new BookingConfirmed(lead.id));
    return lead;
}

/** One Google event on the chosen organizer, all other hosts + invitee + guests invited. */
private void createGroupGoogleEvent(MeetingType type, UUID groupId) {
    List<Long> hostIds = meetingHosts.hostOwnerIds(type);
    Long organizer = meetingHosts.chooseOrganizer(type, hostIds);
    if (organizer == null) return; // no host has Google -> calit-only booking
    Booking organizerRow = Booking.leadOfGroup(groupId, organizer);
    if (organizerRow == null) { // organizer is a co-host, not the lead
        organizerRow = Booking.<Booking>group(groupId).stream()
                .filter(r -> r.ownerId.equals(organizer)).findFirst().orElseThrow();
    }
    Booking lead = Booking.leadOfGroup(groupId, type.ownerId);
    List<String> attendees = groupAttendeeEmails(type, groupId, hostIds);
    CreatedEvent created = calendarPort.createEvent(organizer,
            googleSummary(type, lead), googleDescription(type, lead),
            lead.startUtc, lead.endUtc, attendees,
            type.locationType == MeetingType.LocationType.GOOGLE_MEET, type.locationDetail);
    organizerRow.googleEventId = created.googleEventId();
    organizerRow.meetLink = created.meetLink();
    // propagate the meet link to the lead row too so invitee-facing views show it
    if (lead != null && lead.meetLink == null) lead.meetLink = created.meetLink();
}

private List<String> groupAttendeeEmails(MeetingType type, UUID groupId, List<Long> hostIds) {
    List<String> emails = new ArrayList<>();
    Booking lead = Booking.leadOfGroup(groupId, type.ownerId);
    emails.add(lead.inviteeEmail);
    for (Long hostId : hostIds) {
        OwnerSettings os = OwnerSettings.forOwner(hostId);
        if (os != null && os.ownerEmail != null) emails.add(os.ownerEmail);
    }
    for (BookingGuest g : BookingGuest.activeForBooking(lead.id)) emails.add(g.email);
    return emails;
}
```
Add imports as needed: `java.util.UUID; jakarta.persistence.PersistenceException; site.asm0dey.calit.domain.MeetingTypeHost; site.asm0dey.calit.google.CreatedEvent;`. (`AppLocales`, `BookingGuest`, `googleSummary`, `googleDescription`, `isNoOverlapViolation` already exist in the class.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=GroupBookingWriteTest`
Expected: PASS. Regression: `mvn test -Dtest=BookServiceTest`.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/GroupBookingWriteTest.java
git commit -m "feat(multi-host): group booking write + single organizer Google event"
```

---

### Task 10: All-hosts approval (row-status = truth)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (`approve`, `decline`)
- Test: `src/test/java/site/asm0dey/calit/booking/GroupApprovalTest.java`

**Interfaces:**
- Produces: `approve(Long bookingId)` flips **that host's** row `PENDING→CONFIRMED`; if no group row remains `PENDING`, creates the group Google event and fires `BookingConfirmed(leadId)`. `decline(Long bookingId)` flips the **whole group** to `DECLINED` and fires `BookingDeclined(leadId)`. Single-host unchanged (group of one).

- [ ] **Step 1: Write the failing test**

`GroupApprovalTest.java`:
```java
package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class GroupApprovalTest {

    @Inject BookingService bookingService;
    @InjectMock CalendarPort calendarPort;
    private final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    // ... reuse the same twoHost approval-type setup as GroupBookingWriteTest.type(true) ...
    // (copy the helper methods settings/rule/type here; do NOT factor into a shared base -- keep the test self-contained)

    @Test
    @TestTransaction
    void confirmsOnlyAfterEveryHostApproves() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt", "meet", "cal"));
        MeetingType t = /* approval two-host type */ null;  // build via copied helper
        Booking lead = bookingService.book(1L, "intro",
                LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(10,0).atZone(AMS).toInstant(),
                "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);

        bookingService.approve(rows.get(0).id);   // first host approves
        assertEquals(BookingStatus.CONFIRMED, Booking.<Booking>findById(rows.get(0).id).status);
        verify(calendarPort, never()).createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        bookingService.approve(rows.get(1).id);   // last host approves -> event + confirm
        Booking.group(lead.groupId).forEach(r -> assertEquals(BookingStatus.CONFIRMED, r.status));
        verify(calendarPort, times(1)).createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void anyDeclineKillsWholeGroup() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = /* approval two-host type */ null;
        Booking lead = bookingService.book(1L, "intro",
                LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(10,0).atZone(AMS).toInstant(),
                "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        bookingService.decline(rows.get(0).id);
        Booking.group(lead.groupId).forEach(r -> assertEquals(BookingStatus.DECLINED, r.status));
    }
}
```
(Implementer: copy the `settings`/`rule`/`type(true)` helpers from `GroupBookingWriteTest` into this file — tests stay self-contained; do not build a shared base class.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=GroupApprovalTest`
Expected: FAIL (approve confirms the single row + would create event immediately / decline touches one row).

- [ ] **Step 3: Rework `approve`/`decline` in `BookingService.java`**

```java
@Transactional
public void approve(Long bookingId) {
    Booking b = Booking.findById(bookingId);
    if (b == null || b.status != BookingStatus.PENDING) return;
    b.status = BookingStatus.CONFIRMED;
    if (b.groupId == null) {                       // single-host: unchanged behavior
        MeetingType type = MeetingType.findById(b.meetingTypeId);
        if (calendarPort.isConnected(b.ownerId)) createGoogleEvent(type, b);
        bookingApprovedEvent.fire(new BookingApproved(b.id));
        return;
    }
    MeetingType type = MeetingType.findById(b.meetingTypeId);
    boolean anyPending = Booking.<Booking>group(b.groupId).stream().anyMatch(r -> r.status == BookingStatus.PENDING);
    if (!anyPending) {                             // last approval -> confirm the group
        createGroupGoogleEvent(type, b.groupId);
        Booking lead = Booking.leadOfGroup(b.groupId, type.ownerId);
        bookingConfirmedEvent.fire(new BookingConfirmed(lead.id));
    }
    // else: still waiting on other hosts -> no invitee-facing event yet
}

@Transactional
public void decline(Long bookingId) {
    Booking b = Booking.findById(bookingId);
    if (b == null) return;
    if (b.groupId == null) {                       // single-host: unchanged
        b.status = BookingStatus.DECLINED;
        bookingDeclinedEvent.fire(new BookingDeclined(b.id));
        return;
    }
    MeetingType type = MeetingType.findById(b.meetingTypeId);
    for (Booking r : Booking.<Booking>group(b.groupId)) r.status = BookingStatus.DECLINED;
    Booking lead = Booking.leadOfGroup(b.groupId, type.ownerId);
    bookingDeclinedEvent.fire(new BookingDeclined(lead.id));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=GroupApprovalTest`
Expected: PASS. Regression: `mvn test -Dtest=BookServiceTest`.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/GroupApprovalTest.java
git commit -m "feat(multi-host): all-hosts approval, group confirm on last approval"
```

---

### Task 11: Group cancel + group reschedule with reconfirm (incl. single-host change)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (`cancel`, `reschedule`, `applyRescheduleOutcome`)
- Test: `src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java`

**Interfaces:**
- Produces:
  - `cancel(manageToken, byOwner)` → cancels **all** rows in the group, deletes the **one** Google event, fires `BookingCancelled(leadId)`.
  - `reschedule(...)` → resolves the group via the lead row; re-checks intersection; moves **all** rows + the one Google event; bumps the lead row's `icsSequence`. For `requiresApproval` types it reverts to approval: invitee-initiated (`byOwner=false`) resets **all** host rows to `PENDING`; host-initiated (`byOwner=true`) resets all rows **except** the initiating host's; the Google event is **deleted** and recreated on full re-confirmation. This changes single-host behavior too (single host: host-initiated stays confirmed, invitee-initiated → PENDING).

- [ ] **Step 1: Write the failing test** (self-contained helpers as before)

`GroupCancelRescheduleTest.java` — key assertions:
```java
// cancel: any host cancels -> all rows CANCELLED, deleteEvent called once
bookingService.cancel(anyRow.manageToken, true);
Booking.group(g).forEach(r -> assertEquals(BookingStatus.CANCELLED, r.status));
verify(calendarPort, times(1)).deleteEvent(anyLong(), eq("evt"));

// invitee reschedule of an approval group -> all rows back to PENDING, event deleted
Booking lead = Booking.leadOfGroup(g, 1L);
bookingService.reschedule(lead.manageToken, newStart);   // byOwner=false
Booking.group(g).forEach(r -> assertEquals(BookingStatus.PENDING, r.status));
verify(calendarPort, times(1)).deleteEvent(anyLong(), anyString());
```
(Write full setup mirroring Task 9/10; assert both single-host reschedule cases: single-host approval type, `reschedule(token)` invitee path → status PENDING; owner path → stays CONFIRMED.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=GroupCancelRescheduleTest`
Expected: FAIL.

- [ ] **Step 3: Rework `cancel`, `reschedule`, `applyRescheduleOutcome`**

Cancel core (`cancel(String manageToken, boolean byOwner)`):
```java
@Transactional
public void cancel(String manageToken, boolean byOwner) {
    Booking b = Booking.findByManageToken(manageToken);
    if (b == null) throw new NotFoundException("No booking " + manageToken);
    if (b.groupId == null) {                    // single-host unchanged
        cancelSingle(b, byOwner);
        return;
    }
    MeetingType type = MeetingType.findById(b.meetingTypeId);
    deleteGroupGoogleEvent(b.groupId);          // one event
    for (Booking r : Booking.<Booking>group(b.groupId)) r.status = BookingStatus.CANCELLED;
    Booking lead = Booking.leadOfGroup(b.groupId, type.ownerId);
    bookingCancelledEvent.fire(new BookingCancelled(lead.id, byOwner));
}

private void deleteGroupGoogleEvent(UUID groupId) {
    for (Booking r : Booking.<Booking>group(groupId)) {
        if (r.googleEventId != null) {
            calendarPort.deleteEvent(r.ownerId, r.googleEventId);
            r.googleEventId = null;
            r.meetLink = null;
        }
    }
}
```
(Extract the existing single-host cancel body into `cancelSingle(Booking, boolean)`.)

Reschedule core — add a `byOwner`-aware group branch. After the no-op guard and computing `newEnd`:
```java
if (booking.groupId != null) {
    return rescheduleGroup(booking, newStartUtc, guestEmails, byOwner);
}
// ... existing single-host body, BUT change the re-approval trigger (see below) ...
```
For single-host, change the re-approval condition so an **owner-initiated** reschedule of an approval type does **not** revert, while an invitee-initiated one does:
```java
boolean reApproval = type.requiresApproval && !byOwner;
```
(Previously it reverted on any reschedule; now gate on `!byOwner`.) When `reApproval`, keep the existing behavior (status=PENDING, null googleEventId/meetLink, fire `BookingRequested`).

Add `rescheduleGroup`:
```java
private Booking rescheduleGroup(Booking lead, Instant newStartUtc, List<String> guestEmails, boolean byOwner) {
    MeetingType type = MeetingType.findById(lead.meetingTypeId);
    Instant newEnd = newStartUtc.plusSeconds(60L * type.durationMinutes);
    // re-check intersection at the new time across all hosts (fail-closed inside availableSlots)
    assertSlotAvailable(type, newStartUtc, lead.id);
    String priorEventId = groupEventId(lead.groupId);
    boolean reApproval = type.requiresApproval;
    Long initiator = byOwner ? currentInitiatorHost(lead.groupId) : null;

    deleteGroupGoogleEvent(lead.groupId);       // drop the one event; recreated on confirm
    Instant oldStart = lead.startUtc;
    for (Booking r : Booking.<Booking>group(lead.groupId)) {
        r.startUtc = newStartUtc;
        r.endUtc = newEnd;
        r.icsSequence++;
        if (reApproval) {
            boolean keepsApproval = initiator != null && r.ownerId.equals(initiator);
            r.status = keepsApproval ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
            if (!keepsApproval && r.approvalToken == null) r.approvalToken = UUID.randomUUID().toString();
        }
    }
    if (guestEmails != null) reconcileGuests(Booking.leadOfGroup(lead.groupId, type.ownerId), guestEmails);

    Booking freshLead = Booking.leadOfGroup(lead.groupId, type.ownerId);
    if (reApproval) {
        bookingRequestedEvent.fire(new BookingRequested(freshLead.id));      // hosts re-approve
    } else {
        createGroupGoogleEvent(type, lead.groupId);
        bookingRescheduledEvent.fire(new BookingRescheduled(freshLead.id, oldStart, byOwner));
    }
    return freshLead;
}

private String groupEventId(UUID groupId) {
    return Booking.<Booking>group(groupId).stream()
            .map(r -> r.googleEventId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
}
```
For `currentInitiatorHost`: when a host reschedules from `/me`, the resource passes the acting owner id. Add an overload `reschedule(String manageToken, Instant newStartUtc, List<String> guestEmails, boolean byOwner, Long initiatorOwnerId)` and thread `initiatorOwnerId` into `rescheduleGroup` instead of `currentInitiatorHost`. For the invitee path (`byOwner=false`) initiator is `null`. Replace `currentInitiatorHost(lead.groupId)` with the passed `initiatorOwnerId`. Keep the existing 2/3/4-arg overloads delegating with `initiatorOwnerId=null`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=GroupCancelRescheduleTest`
Expected: PASS. Regression: `mvn test -Dtest=BookServiceTest` and any existing reschedule test (`grep -rl reschedule src/test`).

> **Watch:** the single-host re-approval change (`reApproval = type.requiresApproval && !byOwner`) may break an existing test that expected an owner reschedule to revert to PENDING. If so, that test encodes the **old** rule — update it to the new rule (owner reschedule stays confirmed) and note it in the commit. This is the deliberate behavior change from the spec.

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/GroupCancelRescheduleTest.java
git commit -m "feat(multi-host): group cancel + reschedule reconfirm (initiator-aware, single-host too)"
```

---

### Task 12: Group edit (title/description group-wide; guests on lead)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/booking/BookingService.java` (`updateDetails`)
- Test: `src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java`

**Interfaces:**
- Produces: `updateDetails(manageToken, title, description, guestEmails, byOwner)` for a group writes `title`/`description` to **all** rows, updates the one Google event's details, and reconciles guests on the **lead** row only.

- [ ] **Step 1: Write the failing test** — assert title written to all group rows; `updateEventDetails` called once; guests only on lead.

- [ ] **Step 2: Run to verify it fails.** `mvn test -Dtest=GroupEditDetailsTest`

- [ ] **Step 3: Add a group branch to `updateDetails`**
```java
if (booking.groupId != null) {
    MeetingType type = MeetingType.findById(booking.meetingTypeId);
    for (Booking r : Booking.<Booking>group(booking.groupId)) {
        r.title = (title == null || title.isBlank()) ? null : title;
        r.description = (description == null || description.isBlank()) ? null : description;
    }
    Booking lead = Booking.leadOfGroup(booking.groupId, type.ownerId);
    if (guestEmails != null) reconcileGuests(lead, guestEmails);
    String eventId = groupEventId(booking.groupId);
    Long organizer = organizerOwnerOf(booking.groupId);
    if (eventId != null && organizer != null) {
        calendarPort.updateEventDetails(organizer, eventId,
                googleSummary(type, lead), googleDescription(type, lead),
                groupAttendeeEmails(type, booking.groupId, meetingHosts.hostOwnerIds(type)));
    }
    bookingDetailsChangedEvent.fire(new BookingDetailsChanged(lead.id, byOwner));
    return lead;
}
// ... existing single-host body ...
```
Add `private Long organizerOwnerOf(UUID groupId)` returning the `ownerId` of the row whose `googleEventId != null`.

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=GroupEditDetailsTest`; regression `mvn test -Dtest=BookServiceTest`.

- [ ] **Step 5: Commit**
```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/booking/BookingService.java \
        src/test/java/site/asm0dey/calit/booking/GroupEditDetailsTest.java
git commit -m "feat(multi-host): group-wide detail edits, guests on lead row"
```

---

## Phase 4 — Email fan-out & schedulers

### Task 13: Host fan-out for lifecycle emails + consent + approval-needed

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/email/EmailService.java`
- Create: `src/main/resources/templates/email/hostConsent.html`
- Test: `src/test/java/site/asm0dey/calit/email/MultiHostEmailFanoutTest.java`

**Interfaces:**
- Consumes: `HostConsentRequested`, group-level `BookingConfirmed/Declined/Cancelled/Rescheduled/DetailsChanged/BookingRequested` (each fired once with the lead id), `MeetingHosts`, `OwnerSettings.forOwner`.
- Produces: for a group booking, the invitee gets exactly **one** email per transition, and **each host** gets one personalized email (host as `recipientRole = OWNER_ROLE`, using their own `OwnerSettings` locale/name and their own row's approve/manage token). `HostConsentRequested` → one email to the co-host with a `/consent/{token}` link. Actionable emails (consent, approval-needed `requested`) ignore `ownerNotificationsEnabled`; informational ones respect it.

Because this is the largest email change, split into sub-steps but one commit per sub-task.

- [ ] **Step 1: Write the failing test** (`MultiHostEmailFanoutTest`)

Use a spy/mock `MailSender` (existing tests inject a mock mailer under `%test`; assert send-count). Assert:
- book (auto-confirm two-host) → `mailSender.send` called for invitee once + each host once.
- `HostConsentRequested` fired → one consent email to the co-host containing `/consent/`.

Consult existing `EmailService` tests (`grep -rl EmailService src/test`) for the established mock-mailer harness and reuse it.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement the fan-out**

In `EmailService`, generalize the per-booking send helpers so that, when `l.booking.groupId != null`, the "owner" side iterates over **each host** (from `MeetingHosts.hostOwnerIds(type)` → `OwnerSettings.forOwner(hostId)`), rendering the owner-role template per host with that host's locale and that host's booking-row tokens (approve/manage). Invitee send stays single (keyed on the lead row). Concretely:
- Add `@Inject MeetingHosts meetingHosts;`.
- In `sendForKindLocaleAware`, after the invitee delivery, replace the single owner delivery with: if group → loop hosts, resolve each host's `Booking` row (`Booking.group(groupId)` filtered by `ownerId`), compute `ownerManageUrl`/`approveUrl`/`declineUrl` from **that row**, deliver to `OwnerSettings.forOwner(hostId).ownerEmail` (respecting `ownerNotificationsEnabled` for informational kinds; always for the `requested`/approval kind). Non-group → unchanged single owner path.
- Add observer `void onHostConsent(@Observes(during = TransactionPhase.AFTER_SUCCESS) HostConsentRequested e)` → load co-host `OwnerSettings`, render `Templates.hostConsent(lang, greetingName, creatorName, meetingTypeName, consentUrl)` where `consentUrl = baseUrl + "/consent/" + e.consentToken()`, send (always — actionable). Add the `hostConsent` native method to the `Templates` `@CheckedTemplate` class:
```java
static native TemplateInstance hostConsent(String lang, String greetingName, String creatorName,
        String meetingTypeName, String consentUrl);
```

`templates/email/hostConsent.html` (mirror an existing email template's structure; minimal body):
```html
{@java.lang.String lang}
{@java.lang.String greetingName}
{@java.lang.String creatorName}
{@java.lang.String meetingTypeName}
{@java.lang.String consentUrl}
<p>{msg:email_host_consent_greeting(greetingName)}</p>
<p>{msg:email_host_consent_body(creatorName,meetingTypeName)}</p>
<p><a href="{consentUrl}">{msg:email_host_consent_cta}</a></p>
```
Add the three `@Message` keys to `AppMessages` + de/he (Task 21 consolidates, but add them here so the template compiles).

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=MultiHostEmailFanoutTest`; regression `mvn test -Dtest=BookServiceTest` + existing email tests.

- [ ] **Step 5: Commit**
```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/email/EmailService.java \
        src/main/resources/templates/email/hostConsent.html \
        src/main/java/site/asm0dey/calit/i18n/AppMessages.java \
        src/main/resources/messages/msg_de.properties src/main/resources/messages/msg_he.properties \
        src/test/java/site/asm0dey/calit/email/MultiHostEmailFanoutTest.java
git commit -m "feat(multi-host): per-host email fan-out + consent-request email"
```

---

### Task 14: Group-aware reminder + pending-expiry schedulers

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/scheduler/ReminderScheduler.java`
- Modify: `src/main/java/site/asm0dey/calit/scheduler/PendingExpiryScheduler.java`
- Test: `src/test/java/site/asm0dey/calit/scheduler/GroupSchedulerTest.java`

**Interfaces:**
- Produces: reminders are created for the **lead row only** of a group (no N reminders). Pending expiry declines the **whole group** when the window elapses without full approval.

- [ ] **Step 1: Write the failing test** — on group confirm, exactly one `reminder` row exists for the group (the lead row's booking id). On a group where one row is still PENDING at the deadline, the expiry tick sets **all** rows to `DECLINED`.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement**
- `ReminderScheduler.scheduleReminder(bookingId)`: after loading the booking, if `booking.groupId != null` and `booking` is **not** the lead row, `return` (only the lead row schedules). Lead row check: `Booking lead = Booking.leadOfGroup(booking.groupId, MeetingType.findById(booking.meetingTypeId).ownerId); if (!lead.id.equals(booking.id)) return;`. Since `BookingConfirmed` now fires once with the lead id (Task 10/9), this is naturally satisfied — but guard anyway for the reschedule path.
- `PendingExpiryScheduler.claimAndDeclineExpired`: after selecting an expired PENDING row id, if it belongs to a group, decline **all** rows in the group and enqueue **one** declined email for the lead. Change the per-row body to:
```java
Booking b = em.find(Booking.class, id);
if (b.groupId != null) {
    Long creatorOwner = em.find(MeetingType.class, b.meetingTypeId).ownerId;
    for (Booking r : Booking.<Booking>group(b.groupId)) { r.status = BookingStatus.DECLINED; Reminder.deleteUnsentFor(r.id); }
    Booking lead = Booking.leadOfGroup(b.groupId, creatorOwner);
    emailService.enqueueDeclined(lead.id);
} else {
    b.status = BookingStatus.DECLINED;
    Reminder.deleteUnsentFor(id);
    emailService.enqueueDeclined(id);
}
```
(The claim query already `SKIP LOCKED`s; declining sibling rows inside the same tx is safe because siblings share the invitee but different owner_id and won't be double-claimed as their own PENDING within the grace window — and if they are, the second claim finds them already DECLINED and no-ops. Add a guard `if (b.status != BookingStatus.PENDING) continue;`.)

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=GroupSchedulerTest`; regression existing `Reminder*Test`.

- [ ] **Step 5: Commit**
```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/scheduler/ReminderScheduler.java \
        src/main/java/site/asm0dey/calit/scheduler/PendingExpiryScheduler.java \
        src/test/java/site/asm0dey/calit/scheduler/GroupSchedulerTest.java
git commit -m "feat(multi-host): group-aware reminders + pending expiry"
```

---

## Phase 5 — Public routing, aliases, visibility

### Task 15: Alias resolution + creator context + landing union + disabled page

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/PublicResource.java`
- Modify: `src/main/java/site/asm0dey/calit/domain/MeetingType.java` (`resolveForAlias`, `listPublicIncludingCohosted`)
- Create: `src/test/java/site/asm0dey/calit/web/AliasRoutingTest.java`

**Interfaces:**
- Produces:
  - `MeetingType.resolveForAlias(Long urlOwnerId, String slug)` — returns the type owned by `urlOwnerId` with that slug (own type wins), else a multi-host type with that slug where `urlOwnerId` is an ACCEPTED host, else `null`. (Slug-collision block from Task 6 guarantees ≤1 match.)
  - `MeetingType.listPublicIncludingCohosted(Long ownerId)` — owner's own public types **plus** multi-host types where `ownerId` is an ACCEPTED host (and the type is active & not secret & fully bookable).
  - `PublicResource.book`/`submitBooking` use the **type's creator** (`type.ownerId`) as the owner context, not the URL user; render a disabled page when `!meetingHosts.bookable(type)`.

- [ ] **Step 1: Write the failing test** (`AliasRoutingTest`, RestAssured)

Assert:
- `GET /volodya/intro` (Volodya = accepted co-host of Pasha's `intro`) → 200 and the page renders the same meeting name; the booking form posts to `/volodya/intro` but resolves the creator's type.
- `GET /pasha/intro` before Volodya accepts → 200 but shows the disabled marker (e.g. body contains `CALIT_HOST_PENDING`).
- Landing `GET /volodya` lists `intro` once accepted.

Use `TestOwners`/`FormAuth` helpers and seed via direct entity persists in a `@Transactional` setup, following `BookPageTest`.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement**
- Add to `MeetingType`:
```java
public static MeetingType resolveForAlias(Long urlOwnerId, String slug) {
    MeetingType own = findBySlug(urlOwnerId, slug);
    if (own != null) return own;
    // else a multi-host type this user is an accepted host of
    for (MeetingTypeHost h : MeetingTypeHost.list("ownerId = ?1 and role = ?2 and status = ?3",
            urlOwnerId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)) {
        MeetingType t = findById(h.meetingTypeId);
        if (t != null && t.slug.equals(slug)) return t;
    }
    return null;
}

public static List<MeetingType> listPublicIncludingCohosted(Long ownerId) {
    List<MeetingType> own = listPublic(ownerId);
    List<MeetingType> result = new ArrayList<>(own);
    for (MeetingTypeHost h : MeetingTypeHost.list("ownerId = ?1 and role = ?2 and status = ?3",
            ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)) {
        MeetingType t = findById(h.meetingTypeId);
        if (t != null && t.active && !t.secret) result.add(t);
    }
    return result;
}
```
(Add imports for `MeetingTypeHost`, `ArrayList`, `List`.)
- In `PublicResource.book(user, slug)`: resolve `AppUser urlUser = resolveOwner(user)`; `MeetingType type = MeetingType.resolveForAlias(urlUser.id, slug)`; 404 if null. Then set the **owner context to the creator**: `AppUser creator = AppUser.findById(type.ownerId); currentOwner.set(creator);` and use `type.ownerId` for `OwnerSettings`, `daySlots`, etc. Inject `MeetingHosts meetingHosts`; if `!meetingHosts.bookable(type)`, render a new disabled template (add a `notReady`/`unavailable` variant carrying the marker `CALIT_HOST_PENDING`) instead of the booking form.
- In `submitBooking`: same alias resolution; call `bookingService.book(type.ownerId, type.slug, ...)`. Reject (render disabled) if `!bookable`.
- In `userLanding`: use `MeetingType.listPublicIncludingCohosted(owner.id)` and, for co-hosted entries, link to the canonical `/{creatorUsername}/{slug}` (look up `AppUser.findById(t.ownerId).username`). Add a `String bookUrl` alongside each type in the landing view-model, or compute in the template via a small record; simplest is a new record `LandingType(MeetingType type, String bookUrl)` and change the `landing` template param to `List<LandingType>`.

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=AliasRoutingTest`; regression `mvn test -Dtest=BookPageTest`.

- [ ] **Step 5: Commit**
```bash
mvn spotless:apply -q
git add src/main/java/site/asm0dey/calit/web/PublicResource.java \
        src/main/java/site/asm0dey/calit/domain/MeetingType.java \
        src/main/resources/templates/PublicResource/ \
        src/test/java/site/asm0dey/calit/web/AliasRoutingTest.java
git commit -m "feat(multi-host): username-alias routing, creator context, landing union, disabled page"
```

---

## Phase 6 — Admin & co-host UI

### Task 16: Main vs Shared split + Shared page

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (`meetingTypes` filters single-host; add `shared()`)
- Create: `src/main/resources/templates/AdminResource/shared.html`
- Modify: `src/main/resources/templates/AdminResource/meetingTypes.html` (conditional "Shared →" link)
- Test: `src/test/java/site/asm0dey/calit/web/SharedPageTest.java`

**Interfaces:**
- Produces: `GET /me/meeting-types` lists only single-host types + shows a "Shared →" link when the owner has any shared type (created or co-hosted). `GET /me/shared` renders one flat list of every multi-host type involving the owner, each row with a role badge (creator/co-host) + status.

- [ ] **Step 1–2: Failing RestAssured test** — after login as admin (id 1), create a two-host type; assert `/me/meeting-types` does **not** list it but shows the shared link; `/me/shared` lists it with a `CREATOR` badge; as the co-host, `/me/shared` lists it with a `COHOST` badge.

- [ ] **Step 3: Implement**
- `meetingTypes()`: filter `MeetingType.listForOwner(currentOwner.id())` to those where `!MeetingTypeHost.isMultiHost(t.id)`; pass a `boolean hasShared` flag (true if any `MeetingTypeHost` row references the owner as creator OR co-host) to the template.
- Add `@GET @Path("/shared") shared()`: build a `List<SharedRow>` where `SharedRow(MeetingType type, String role, String status, boolean needsReconnect)`; sources = types the owner created that are multi-host (`listForOwner` filtered to `isMultiHost`, role CREATOR) + `MeetingTypeHost.cohostedTypesFor(owner)` mapped to their types (role COHOST, status from the row). Render `AdminResource/shared.html`. Add the `shared` native method to the inner `@CheckedTemplate`.

`shared.html` skeleton (flat list, role badge):
```html
{@java.util.List<site.asm0dey.calit.web.AdminResource.SharedRow> rows}
{@java.lang.Long pendingCount}{@java.lang.Boolean isAdmin}{@java.lang.String title}
{#include adminBase title=title pendingCount=pendingCount active="shared" isAdmin=isAdmin}
  <h1 class="text-2xl font-bold mb-4">{adm:adm_shared_h1}</h1>
  <ul class="menu bg-base-200 rounded-box">
    {#for r in rows}
    <li>
      <a href="/me/meeting-types/{r.type.id}">
        <span>{r.type.name}</span>
        <span class="badge badge-sm">{r.role}</span>
        {#if r.status == 'PENDING'}<span class="badge badge-warning badge-sm">{adm:adm_shared_pending}</span>{/if}
        {#if r.needsReconnect}<span class="badge badge-error badge-sm">{adm:adm_shared_reconnect}</span>{/if}
      </a>
    </li>
    {/for}
  </ul>
{/include}
```
Add the `adm_shared_*` keys (Task 21).

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=SharedPageTest`.

- [ ] **Step 5: Commit** (`feat(multi-host): Main vs Shared meeting-type navigation`).

---

### Task 17: Co-host management on the meeting-type form (add/remove + host list)

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (add-cohost/remove endpoints; slug guards on create/rename)
- Create: `src/main/resources/templates/AdminResource/_hostlist.html` (partial), include it in `meetingTypeDetail.html`
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java` (typeahead script — Task 20)
- Test: `src/test/java/site/asm0dey/calit/web/CohostManageTest.java`

**Interfaces:**
- Produces:
  - `POST /me/meeting-types/{id}/hosts` (`@RestForm String cohost`) → `meetingHosts.addCohost(requireType(id), resolveEligible(cohost))`; re-render detail; surface `IllegalStateException` message as an error alert.
  - `POST /me/meeting-types/{id}/hosts/{cohostOwnerId}/remove` → Task 18 interstitial (this task: the plain remove when no future bookings).
  - `createMeetingType`/`editMeetingType`: when creating/renaming, reject a slug that collides with a type the owner co-hosts, and for a multi-host type call `meetingHosts.assertSlugFreeAcrossHosts`.
  - `_hostlist.html` renders host rows (status, remove button) + the add-cohost input.

- [ ] **Step 1–2: Failing test** — as admin: POST add-cohost with a valid username creates a PENDING host row and the detail page lists it; adding a username that already owns the slug shows the error alert (no row created); remove deletes the row.

- [ ] **Step 3: Implement** the two endpoints + guards; render `_hostlist.html` inside `meetingTypeDetail.html`:
```html
{@java.util.List<site.asm0dey.calit.domain.MeetingTypeHost> hosts}
{@site.asm0dey.calit.domain.MeetingType type}
<section class="mt-6">
  <h2 class="font-semibold">{adm:adm_hosts_h2}</h2>
  <ul>
    {#for h in hosts}
    <li class="flex items-center gap-2">
      <span>{h.ownerId}</span>  {! resolve to username in the view-model; see note !}
      <span class="badge badge-sm">{h.role}</span>
      <span class="badge badge-sm {#if h.status == 'ACCEPTED'}badge-success{#else}badge-warning{/if}">{h.status}</span>
      {#if h.role != 'CREATOR'}
      <form method="post" action="/me/meeting-types/{type.id}/hosts/{h.ownerId}/remove">
        <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
        <button class="btn btn-xs btn-ghost">{adm:adm_hosts_remove}</button>
      </form>
      {/if}
    </li>
    {/for}
  </ul>
  <form method="post" action="/me/meeting-types/{type.id}/hosts" class="flex gap-2 mt-2" data-host-add>
    <input type="hidden" name="{inject:csrf.parameterName}" value="{inject:csrf.token}">
    <input name="cohost" class="input input-sm" list="host-suggest" placeholder="{adm:adm_hosts_add_placeholder}" autocomplete="off">
    <datalist id="host-suggest"></datalist>
    <button class="btn btn-sm btn-primary">{adm:adm_hosts_add}</button>
  </form>
</section>
```
> **View-model note:** to show usernames not ids, change the detail template param from `List<MeetingTypeHost>` to a `List<HostRow>` record `HostRow(Long ownerId, String username, String role, String status, boolean needsReconnect)` built in `meetingTypeDetail()`; resolve username via `AppUser.findById`, `needsReconnect` via a new `GoogleCredential.needsReconnect(ownerId)` helper (or reuse an existing query).

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=CohostManageTest`.

- [ ] **Step 5: Commit** (`feat(multi-host): co-host add/remove on meeting-type form + slug guards`).

---

### Task 18: Revoke/removal keep-vs-cancel interstitial

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/web/AdminResource.java` (remove flow) and `SharedMeetingsResource.java` (co-host revoke flow, created in Task 19)
- Modify: `src/main/java/site/asm0dey/calit/booking/MeetingHosts.java` (`futureBookingsFor`, `removeHostAndCancel`)
- Create: `src/main/resources/templates/AdminResource/removeHostConfirm.html`
- Test: `src/test/java/site/asm0dey/calit/booking/HostRemovalInterstitialTest.java`

**Interfaces:**
- Produces:
  - `MeetingHosts.countFutureBookings(MeetingType type, Long hostOwnerId)` — PENDING+CONFIRMED group bookings for that host with `startUtc > now`.
  - `MeetingHosts.removeHostKeeping(type, hostOwnerId)` = `removeHost` (existing bookings untouched).
  - `MeetingHosts.removeHostCancelling(type, hostOwnerId)` — cancels every future group booking involving that host (delegates to `bookingService.cancel` per group by lead manage token), then `removeHost`.
  - Remove endpoint: if `countFutureBookings > 0`, render `removeHostConfirm` (count + Keep/Cancel buttons); else remove immediately.

- [ ] **Step 1–2: Failing test** — with a future confirmed group booking, removing the co-host renders the confirm page; POST "keep" removes the host but leaves the booking CONFIRMED; POST "cancel" cancels the group and removes the host.

- [ ] **Step 3: Implement** the two service methods + the interstitial GET/POST (`.../remove` shows confirm when needed; `.../remove?choice=keep|cancel` executes). `MeetingHosts.removeHostCancelling` must inject `BookingService` (careful: `BookingService` already injects `MeetingHosts` → to avoid a constructor cycle, inject `BookingService` into `MeetingHosts` as an `Instance<BookingService>`/`Provider`, or move the cancel-cascade into `BookingService.removeHostCancelling(type, hostOwnerId)` and have `MeetingHosts` expose only `countFutureBookings`). **Chosen:** put the cascade in `BookingService.cancelFutureGroupBookingsForHost(MeetingType, Long hostOwnerId)` (no cycle — `BookingService` already depends on `MeetingHosts`), and the resource calls that then `meetingHosts.removeHost`.

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=HostRemovalInterstitialTest`.

- [ ] **Step 5: Commit** (`feat(multi-host): keep-vs-cancel interstitial on host removal`).

---

### Task 19: Co-host consent (dashboard + public token) & shared availability editor

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/SharedMeetingsResource.java` (`@Path("/me/shared")`, dashboard consent + per-type availability editor)
- Create: `src/main/java/site/asm0dey/calit/web/ConsentResource.java` (`@Path("/consent")`, public one-click)
- Create: templates `SharedMeetingsResource/{consentRequests,sharedAvailability}.html`, `ConsentResource/{confirm,done}.html`
- Test: `src/test/java/site/asm0dey/calit/web/ConsentFlowTest.java`, `src/test/java/site/asm0dey/calit/web/CohostAvailabilityAuthzTest.java`

**Interfaces:**
- Produces:
  - `GET /me/shared/requests` — pending consent requests for the logged-in owner (accept/decline forms).
  - `POST /me/shared/requests/{typeId}/accept|decline` — `meetingHosts.acceptConsent(...)` / `removeHost(...)` scoped to `currentOwner.id()`.
  - `GET /consent/{token}` → confirm page; `POST /consent/{token}` (`accept`/`decline`) → `acceptConsent` / delete row; clears token. No auth (token authorizes).
  - `GET /me/shared/{typeId}/availability` + POST bulk endpoints — the co-host's per-type working-hours + buffer editor, **authorized only if** `MeetingTypeHost.find(typeId, currentOwner.id())` is ACCEPTED; writes `AvailabilityRule`/`DateOverride` scoped to `currentOwner.id()` + `typeId`, and host-row buffers. Reuse `AdminResource.persistFrames(ownerId, typeId, form)` logic (extract to a shared helper or duplicate the small zip loop).

- [ ] **Step 1–2: Failing tests**
  - `ConsentFlowTest`: `GET /consent/{token}` 200 + confirm marker; `POST .../accept` flips the row to ACCEPTED and clears the token; a bad token → 404.
  - `CohostAvailabilityAuthzTest`: an ACCEPTED co-host can POST availability for the shared type (rows written under their own owner_id + typeId); a non-host gets 404/403; a host cannot write another host's rows.

- [ ] **Step 3: Implement** both resources + templates. Authorization pattern (mirror `requireType` but for host membership):
```java
private MeetingTypeHost requireAcceptedHost(Long typeId) {
    MeetingTypeHost h = MeetingTypeHost.find(typeId, currentOwner.id());
    if (h == null || !h.accepted()) throw new NotFoundException();
    return h;
}
```
Availability writes: `AvailabilityRule.delete("ownerId=?1 and meetingTypeId=?2", currentOwner.id(), typeId)` then persist frames under `currentOwner.id()`; buffers → set `h.bufferBeforeMinutes/After` from the form. Confirm template carries marker `CALIT_CONSENT_CONFIRM`.

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=ConsentFlowTest,CohostAvailabilityAuthzTest`.

- [ ] **Step 5: Commit** (`feat(multi-host): co-host consent (dashboard + token) + shared availability editor`).

---

### Task 20: Host typeahead endpoint + progressive-enhancement autocomplete

**Files:**
- Create: `src/main/java/site/asm0dey/calit/web/HostSuggestResource.java` (`@Path("/me/hosts")`)
- Modify: `src/main/java/site/asm0dey/calit/web/Layout.java` (add `HOST_TYPEAHEAD_SCRIPT`)
- Modify: `_hostlist.html` include the script; `meetingTypeDetail()` thread the script param
- Test: `src/test/java/site/asm0dey/calit/web/HostSuggestTest.java`

**Interfaces:**
- Produces: `GET /me/hosts?q=<prefix>&typeId=<id>` `@RolesAllowed("user")` `@Produces(APPLICATION_JSON)` → up to 20 usernames matching the prefix, filtered by the eligibility predicate (`enabled ∧ settingsComplete ∧ not-self ∧ not-already-a-host-of typeId`). Baseline no-JS: `addCohost` already validates eligibility server-side (Task 6), so the plain input works without the endpoint.

- [ ] **Step 1–2: Failing test**
  - `HostSuggestTest`: logged-in `GET /me/hosts?q=vol&typeId=<id>` returns JSON containing an eligible `"volodya"` and excludes self / disabled / already-host / settings-incomplete users; unauthenticated → 401.
  - A RestAssured assertion on `meetingTypeDetail` body containing the marker `CALIT_HOST_TYPEAHEAD`.

- [ ] **Step 3: Implement**
- `HostSuggestResource.suggest(@QueryParam("q") String q, @QueryParam("typeId") Long typeId)` → query `AppUser.list("enabled = true and settingsComplete = true and lower(username) like ?1", q.toLowerCase()+"%")`, filter via `meetingHosts.eligibleCohost(typeId, currentOwner.id(), u)`, cap 20, map to `record Suggestion(String username)`. Return the list (Quarkus REST serializes to JSON).
- `Layout.HOST_TYPEAHEAD_SCRIPT`:
```java
public static final String HOST_TYPEAHEAD_SCRIPT = """
        <script>
        /* CALIT_HOST_TYPEAHEAD - progressive-enhancement co-host autocomplete over a plain input */
        (function () {
          var form = document.querySelector('[data-host-add]');
          if (!form) return;
          var input = form.querySelector('input[name=cohost]');
          var list = form.querySelector('datalist');
          var typeId = form.getAttribute('action').split('/')[3];
          var t;
          input.addEventListener('input', function () {
            clearTimeout(t);
            var q = input.value.trim();
            if (q.length < 2) return;
            t = setTimeout(function () {
              fetch('/me/hosts?typeId=' + typeId + '&q=' + encodeURIComponent(q))
                .then(function (r) { return r.json(); })
                .then(function (rows) {
                  list.innerHTML = '';
                  rows.forEach(function (row) {
                    var o = document.createElement('option');
                    o.value = row.username;
                    list.appendChild(o);
                  });
                });
            }, 200);
          });
        })();
        </script>
        """;
```
Thread it into `meetingTypeDetail(...)` as a `String hostTypeaheadScript` param and render at the bottom of `_hostlist.html` (`{hostTypeaheadScript|raw}` — check how existing scripts are rendered, e.g. `tzScript`, and match the raw/safe pattern used there).

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=HostSuggestTest`.

- [ ] **Step 5: Commit** (`feat(multi-host): host typeahead endpoint + no-JS-safe autocomplete`).

---

## Phase 7 — i18n parity & docs

### Task 21: i18n parity sweep

**Files:**
- Modify: `src/main/java/site/asm0dey/calit/i18n/AppMessages.java`, `AdminMessages.java`
- Modify: `src/main/resources/messages/{msg_de,msg_he,adm_de,adm_he}.properties`
- Test: `src/test/java/site/asm0dey/calit/i18n/MultiHostMessageParityTest.java`

**Interfaces:**
- Produces: every new `@Message` key used by Tasks 13/16–20 has a `de` and `he` line in the matching property file.

- [ ] **Step 1: Write a parity test** — reflectively list all `@Message` methods on both bundles, load each locale property file, assert every method name is present as a key in each locale file (this generalizes the CLAUDE.md "quick parity check"). If a generic parity test already exists, extend it; else add one scoped to the new keys.

- [ ] **Step 2: Run to verify it fails** (missing de/he keys added incrementally in prior tasks may already be partial).

- [ ] **Step 3: Fill every missing `de` + `he` value.** Keys introduced across the feature (add any used in templates):
  - App (`msg`): `email_host_consent_greeting`, `email_host_consent_body`, `email_host_consent_cta`, plus any public disabled-page strings (`pub_host_pending`).
  - Admin (`adm`): `adm_nav_shared`, `adm_shared_h1`, `adm_shared_pending`, `adm_shared_reconnect`, `adm_hosts_h2`, `adm_hosts_add`, `adm_hosts_add_placeholder`, `adm_hosts_remove`, `adm_host_remove_confirm_h1`, `adm_host_remove_keep`, `adm_host_remove_cancel`, `adm_consent_confirm_h1`, `adm_consent_accept`, `adm_consent_decline`, `adm_shared_availability_h1`, `adm_hosts_cap_error`, `adm_hosts_slug_error`.
  German + Hebrew values: translate each (use existing entries as tone reference; if a confident Hebrew value isn't possible for a given key, ship the English default and open a translation-labelled GitHub issue referenced in the PR — but attempt all).

- [ ] **Step 4: Run to verify it passes.** `mvn test -Dtest=MultiHostMessageParityTest`.

- [ ] **Step 5: Commit** (`i18n(multi-host): de/he translations for all new strings`).

---

### Task 22: Docs-site changelog + usage page

**Files (on the `docs-site` branch — separate worktree/checkout):**
- Modify: `docs-site/src/content/docs/releases/changelog.md` (new top section)
- Create/modify: a usage page under `docs-site/src/content/docs/` describing multi-host meeting types (create, add co-hosts, consent, per-host availability, how slots intersect, disconnected-host behavior).
- Modify: `README.md` example image tag only if a release is cut alongside.

**Interfaces:** docs only; no code.

- [ ] **Step 1:** Check out `docs-site` (`git worktree add ../calit-docs docs-site` or `git checkout docs-site` in a separate clone — do **not** mix with the feature branch).

- [ ] **Step 2:** Add a changelog entry at the top summarizing multi-host meeting types + the progressive-enhancement rule change + the owner-scoped no-overlap fix.

- [ ] **Step 3:** Write the usage page: creating a shared type, adding co-hosts + the consent flow (email one-click + dashboard), each host setting their own working hours/buffers, slot-intersection behavior, the "temporarily unavailable" states (pending consent / disabled host / disconnected Google), aliases (`/{anyhost}/{slug}`), and the 10-host cap.

- [ ] **Step 4:** Commit on `docs-site` and push (docs deploy via the docs workflow). Do **not** merge into `main`.

- [ ] **Step 5:** Back on `main`/feature branch, nothing to build; note the docs commit hash in the PR description.

---

## Self-Review (completed by plan author)

**Spec coverage** — every spec section maps to a task:
- §1 data model → Tasks 1, 2. §3 slot intersection → 7, 8 (fail-closed in 8). §4 booking write / organizer → 9. §5 approval/cancel/reschedule/edit/reminders/expiry/manage-token → 10, 11, 12, 14; email fan-out → 13. §6 revoke interstitial → 18. §7 owner-scope constraint → 3. §8 aliases/slug-block/visibility/canonical URL → 6, 15. §9 ICS (keep hand-rolled, per-recipient with shared UID) → covered by 13 (EmailService already keys .ics uid on `manageToken`; **note:** in the group path the invitee .ics must use the **lead row's** `manageToken` as UID so all recipients share it — verify in Task 13). §10 Main/Shared, co-host /me, authz, notification prefs → 16, 17, 19; per-host notification prefs → 13. §11 progressive enhancement + typeahead → 20. §12 migrations → 1,2,3. §13 testing → each task's tests. §14 host cap/consent-spam → 5.
- **Gap found & assigned:** the shared-UID-for-group .ics requirement (§9) is now an explicit checkpoint inside Task 13 Step 3 (use the lead row's `manageToken` for the group's `IcsEvent.uid`, and skip the .ics for hosts covered by the Google event). No new task needed.

**Placeholder scan** — no "TBD"/"handle appropriately"; UI tasks (16–20) give concrete endpoints, template skeletons, and exact predicates; the only intentionally-not-fully-inlined parts are large HTML files that must mirror named existing templates (`availability.html`, `meetingTypeDetail.html`) — the plan cites the exact file to copy structure from and gives the multi-host-specific markup in full.

**Type consistency** — `MeetingTypeHost` fields/constants, `Booking.groupId`/`group`/`leadOfGroup`/`countDistinctBookingsByEmailBetween`, `MeetingHosts` method names (`hostOwnerIds`, `bookable`, `chooseOrganizer`, `effectiveBufferBefore/After`, `eligibleCohost`, `addCohost`, `removeHost`, `acceptConsent`, `assertSlugFreeForCohost`, `assertSlugFreeAcrossHosts`), and `BookingService` group helpers (`bookGroup`, `createGroupGoogleEvent`, `groupAttendeeEmails`, `deleteGroupGoogleEvent`, `rescheduleGroup`, `groupEventId`, `organizerOwnerOf`, `cancelFutureGroupBookingsForHost`) are used consistently across tasks.

**Ordering** — each phase compiles and its tests pass before the next depends on it. Constructor-cycle risk (`BookingService` ↔ `MeetingHosts`) is resolved by keeping the cancel-cascade in `BookingService` (Task 18 note).
