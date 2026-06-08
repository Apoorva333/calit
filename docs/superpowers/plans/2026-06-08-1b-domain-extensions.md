# Plan 1b — Domain Extensions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Migration ordering note:** This plan's migration is **V2**. The Google plan (Plan 2) is **V3** and the booking plan (Plan 3) is **V4** — they were renumbered so that Plan 1b's domain extensions apply *before* them.

**Goal:** Apply additive changes on top of the already-built-and-committed Plan 1: extend `meeting_type` (min-notice, horizon, location, approval) and `owner_settings` (owner-notify opt-out), introduce a **date-specific availability override** entity (`DateOverride`/`DateOverrideWindow`) with replace-semantics, and integrate it into `SlotService.generateRawSlots`.

**Architecture:** A single Flyway migration (`V2__domain_extensions.sql`) adds columns to the two existing tables and creates two new tables. Entities `MeetingType` and `OwnerSettings` gain plain Panache fields with DB-matching defaults. A new `DateOverride` entity carries a list of bookable windows for one date (empty list = day off) and a static `resolve(meetingTypeId, date)` returning the per-type override, else the global override, else `null`. `SlotService.generateRawSlots` consults `DateOverride.resolve` per date: when an override exists its windows REPLACE that day's weekly hours; otherwise it falls back to the existing weekly-rule resolution. Min-notice/horizon are stored here as columns only — they are slot filters relative to "now" and are applied in Plan 3, not here.

**Tech Stack:** Java 25, Quarkus 3.35.3, Hibernate ORM Panache (active-record, `PanacheEntityBase`, public fields, IDENTITY ids), PostgreSQL, Flyway, JUnit 5. Tests run against Quarkus **Dev Services** (Testcontainers Postgres) — **Docker must be running**.

**Cross-plan contract conformance (authoritative — from the overview's "Added in Plan 1b"):**
- `MeetingType` gains: `minNoticeMinutes` (int, default 0), `horizonDays` (int, default 60), `locationType` (`LocationType` enum: GOOGLE_MEET[default]/PHONE/IN_PERSON/CUSTOM), `locationDetail` (String, nullable), `requiresApproval` (boolean, default false).
- `OwnerSettings` gains: `ownerNotificationsEnabled` (boolean, default true).
- `DateOverride` — fields: `meetingTypeId` (null = global), `date` (LocalDate, column `override_date`), list of windows (`DateOverrideWindow`: startTime/endTime). **Empty windows = day off.** `DateOverride.resolve(meetingTypeId, date)` → per-type override if present, else global, else `null`.
- `SlotService.generateRawSlots` uses resolved `DateOverride` windows per date when one exists (REPLACING weekly hours), else weekly rules. (min-notice/horizon are NOT applied here.)

---

### Task 1: Flyway migration V2 (domain extensions)

**Files:**
- Create: `src/main/resources/db/migration/V2__domain_extensions.sql`

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V2__domain_extensions.sql`:

```sql
-- Feature 11: per-type min scheduling notice + booking horizon (columns only; filtered in Plan 3).
-- Feature 13: per-type meeting location (Meet/phone/in-person/custom).
-- Feature 14a: per-type approval workflow flag.
ALTER TABLE meeting_type ADD COLUMN min_notice_minutes INT         NOT NULL DEFAULT 0;
ALTER TABLE meeting_type ADD COLUMN horizon_days       INT         NOT NULL DEFAULT 60;
ALTER TABLE meeting_type ADD COLUMN location_type      VARCHAR(16) NOT NULL DEFAULT 'GOOGLE_MEET';
ALTER TABLE meeting_type ADD COLUMN location_detail    TEXT;
ALTER TABLE meeting_type ADD COLUMN requires_approval  BOOLEAN     NOT NULL DEFAULT FALSE;

-- Owner-notify opt-out.
ALTER TABLE owner_settings ADD COLUMN owner_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Feature 12: date-specific availability overrides (replace semantics, per-type -> global, empty = day off).
CREATE TABLE date_override (
    id              BIGSERIAL PRIMARY KEY,
    meeting_type_id BIGINT    REFERENCES meeting_type(id) ON DELETE CASCADE,  -- null = global
    override_date   DATE      NOT NULL
);

-- One override per (scope, date). COALESCE folds the global (null) scope to 0 so it is unique too.
CREATE UNIQUE INDEX uq_date_override_scope_date ON date_override (COALESCE(meeting_type_id, 0), override_date);

CREATE TABLE date_override_window (
    id               BIGSERIAL PRIMARY KEY,
    date_override_id BIGINT    NOT NULL REFERENCES date_override(id) ON DELETE CASCADE,
    start_time       TIME      NOT NULL,
    end_time         TIME      NOT NULL
);

CREATE INDEX idx_date_override_window_parent ON date_override_window (date_override_id);
```

- [ ] **Step 2: Verify the schema applies at startup**

Run: `mvn test -Dtest=HealthResourceTest`
Expected: PASS. Flyway runs V2 after the committed V1 against the Dev Services DB; no migration errors in the log.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V2__domain_extensions.sql
git commit -m "feat: add V2 migration (meeting_type/owner_settings columns, date_override tables)"
```

---

### Task 2: MeetingType extra columns (min-notice, horizon, location, approval)

**Files:**
- Edit: `src/main/java/com/calit/domain/MeetingType.java`
- Test: `src/test/java/com/calit/domain/MeetingTypeExtensionsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/MeetingTypeExtensionsTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MeetingTypeExtensionsTest {

    @Test
    @TestTransaction
    void persistsWithPlan1bDefaults() {
        MeetingType t = new MeetingType();
        t.name = "Defaults";
        t.slug = "mt-ext-defaults";
        t.durationMinutes = 30;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug("mt-ext-defaults");
        assertEquals(0, loaded.minNoticeMinutes);
        assertEquals(60, loaded.horizonDays);
        assertEquals(MeetingType.LocationType.GOOGLE_MEET, loaded.locationType);
        assertNull(loaded.locationDetail);
        assertFalse(loaded.requiresApproval);
    }

    @Test
    @TestTransaction
    void roundTripsNonDefaultLocationAndApproval() {
        MeetingType t = new MeetingType();
        t.name = "Phone Approval";
        t.slug = "mt-ext-phone";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 120;
        t.horizonDays = 14;
        t.locationType = MeetingType.LocationType.PHONE;
        t.locationDetail = "+31 6 1234 5678";
        t.requiresApproval = true;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug("mt-ext-phone");
        assertEquals(120, loaded.minNoticeMinutes);
        assertEquals(14, loaded.horizonDays);
        assertEquals(MeetingType.LocationType.PHONE, loaded.locationType);
        assertEquals("+31 6 1234 5678", loaded.locationDetail);
        assertTrue(loaded.requiresApproval);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=MeetingTypeExtensionsTest`
Expected: FAIL — compilation error: `minNoticeMinutes`, `horizonDays`, `LocationType`, `locationDetail`, `requiresApproval` do not exist yet.

- [ ] **Step 3: Edit the entity**

Add the imports `jakarta.persistence.EnumType` and `jakarta.persistence.Enumerated` to `src/main/java/com/calit/domain/MeetingType.java` (next to the existing `jakarta.persistence.*` imports), add the inner enum `LocationType`, and add the five new fields after the existing `secret` field.

Inner enum (place near the top of the class body, e.g. just under the class declaration):

```java
    public enum LocationType { GOOGLE_MEET, PHONE, IN_PERSON, CUSTOM }
```

New fields (place after the existing `public boolean secret = false;`):

```java
    /** Minimum scheduling notice (minutes from "now"). Stored here; enforced as a slot filter in Plan 3. */
    @Column(name = "min_notice_minutes", nullable = false)
    public int minNoticeMinutes = 0;

    /** Booking horizon: how many days into the future are bookable. Stored here; filtered in Plan 3. */
    @Column(name = "horizon_days", nullable = false)
    public int horizonDays = 60;

    /** Where the meeting happens. Only GOOGLE_MEET triggers a Meet conference link (Plan 2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 16)
    public LocationType locationType = LocationType.GOOGLE_MEET;

    /** Free-text detail for non-Meet locations (phone number, address, custom instructions). */
    @Column(name = "location_detail", columnDefinition = "text")
    public String locationDetail;

    /** When true, bookings start PENDING and need owner approval (Plan 3 workflow). */
    @Column(name = "requires_approval", nullable = false)
    public boolean requiresApproval = false;
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=MeetingTypeExtensionsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/MeetingType.java \
  src/test/java/com/calit/domain/MeetingTypeExtensionsTest.java
git commit -m "feat: add MeetingType min-notice/horizon/location/approval fields"
```

---

### Task 3: OwnerSettings owner-notify flag

**Files:**
- Edit: `src/main/java/com/calit/domain/OwnerSettings.java`
- Test: `src/test/java/com/calit/domain/OwnerSettingsExtensionsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/OwnerSettingsExtensionsTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OwnerSettingsExtensionsTest {

    @Test
    @TestTransaction
    void ownerNotificationsDefaultsTrueAndToggles() {
        // Upsert the singleton (the RestAssured tests may already have committed id=1).
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        OwnerSettings loaded = OwnerSettings.get();
        assertNotNull(loaded);
        assertTrue(loaded.ownerNotificationsEnabled); // DB default TRUE

        loaded.ownerNotificationsEnabled = false;
        loaded.persist();
        assertFalse(OwnerSettings.get().ownerNotificationsEnabled);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=OwnerSettingsExtensionsTest`
Expected: FAIL — compilation error: `ownerNotificationsEnabled` does not exist yet.

- [ ] **Step 3: Edit the entity**

Add the new field to `src/main/java/com/calit/domain/OwnerSettings.java` after the existing `timezone` field:

```java
    /** When false, the owner suppresses their own notification emails (Plan 4 gates on this). */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=OwnerSettingsExtensionsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/calit/domain/OwnerSettings.java \
  src/test/java/com/calit/domain/OwnerSettingsExtensionsTest.java
git commit -m "feat: add OwnerSettings.ownerNotificationsEnabled flag"
```

---

### Task 4: DateOverride + DateOverrideWindow entities

Replace-semantics date override: a `DateOverride` for one date carries a list of bookable windows. **An empty windows list means the day is off (blocked).** Resolution is per-type first, then global, then `null` (meaning: fall through to weekly `AvailabilityRule`).

A `@OneToMany` mapping on `DateOverride.windows` is used so the resolver returns a fully-loaded override with its windows in order — this is the simplest shape to test with Panache (no separate query needed at the call site). The collection is eager so windows are available after the entity leaves the persistence-context boundary in `SlotService`.

**Files:**
- Create: `src/main/java/com/calit/domain/DateOverride.java`
- Create: `src/main/java/com/calit/domain/DateOverrideWindow.java`
- Test: `src/test/java/com/calit/domain/DateOverrideTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/domain/DateOverrideTest.java`:

```java
package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DateOverrideTest {

    private static final LocalDate D = LocalDate.of(2026, 7, 1);

    @Test
    @TestTransaction
    void resolveReturnsNullWhenNoOverride() {
        assertNull(DateOverride.resolve(123_456L, D));
    }

    @Test
    @TestTransaction
    void perTypeOverrideWinsOverGlobal() {
        // FK columns reference real rows: persist a real MeetingType for the per-type override.
        MeetingType type = new MeetingType();
        type.name = "Override Type";
        type.slug = "do-pertype-wins";
        type.durationMinutes = 30;
        type.persist();

        // Global override for the date (09:00-10:00).
        DateOverride global = override(null, D);
        global.persist();
        window(global, "09:00", "10:00");

        // Per-type override for the same date (13:00-14:00) — should win.
        DateOverride typed = override(type.id, D);
        typed.persist();
        window(typed, "13:00", "14:00");

        DateOverride resolved = DateOverride.resolve(type.id, D);
        assertEquals(typed.id, resolved.id);
        assertEquals(1, resolved.windows.size());
        assertEquals(LocalTime.of(13, 0), resolved.windows.get(0).startTime);
    }

    @Test
    @TestTransaction
    void globalOverrideResolvesWhenNoPerTypeExists() {
        DateOverride global = override(null, D);
        global.persist();
        window(global, "08:00", "09:00");

        // A meeting type with no per-type override falls through to the global one.
        DateOverride resolved = DateOverride.resolve(987_654L, D);
        assertEquals(global.id, resolved.id);
        assertEquals(LocalTime.of(8, 0), resolved.windows.get(0).startTime);
    }

    @Test
    @TestTransaction
    void emptyWindowsOverrideResolvesAsDayOff() {
        DateOverride dayOff = override(null, D);
        dayOff.persist(); // no windows added

        DateOverride resolved = DateOverride.resolve(555L, D);
        assertEquals(dayOff.id, resolved.id);
        assertTrue(resolved.windows.isEmpty()); // empty = day off (caller blocks the day)
    }

    @Test
    @TestTransaction
    void windowsLoadInStartTimeOrder() {
        DateOverride o = override(null, D);
        o.persist();
        // Insert out of order; expect ordered load.
        window(o, "14:00", "15:00");
        window(o, "09:00", "10:00");

        DateOverride resolved = DateOverride.resolve(42L, D);
        assertEquals(2, resolved.windows.size());
        assertEquals(LocalTime.of(9, 0), resolved.windows.get(0).startTime);
        assertEquals(LocalTime.of(14, 0), resolved.windows.get(1).startTime);
    }

    // --- helpers ---

    private DateOverride override(Long meetingTypeId, LocalDate date) {
        DateOverride o = new DateOverride();
        o.meetingTypeId = meetingTypeId;
        o.date = date;
        return o;
    }

    private void window(DateOverride parent, String start, String end) {
        DateOverrideWindow w = new DateOverrideWindow();
        w.dateOverrideId = parent.id;
        w.startTime = LocalTime.parse(start);
        w.endTime = LocalTime.parse(end);
        w.persist();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=DateOverrideTest`
Expected: FAIL — compilation error: `DateOverride` / `DateOverrideWindow` do not exist.

- [ ] **Step 3: Write `DateOverrideWindow`**

`src/main/java/com/calit/domain/DateOverrideWindow.java`:

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;

@Entity
@Table(name = "date_override_window")
public class DateOverrideWindow extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "date_override_id", nullable = false)
    public Long dateOverrideId;

    @Column(name = "start_time", nullable = false)
    public LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    public LocalTime endTime;
}
```

- [ ] **Step 4: Write `DateOverride`**

`src/main/java/com/calit/domain/DateOverride.java`:

> **Implementation note (deviation from original plan):** The original plan used `@OneToMany @JoinColumn(name="date_override_id")` for `windows`. Two compounding issues required a different approach:
> 1. **Repeated-column mapping boot error** — `date_override_id` is mapped both on `DateOverride.@JoinColumn` and on `DateOverrideWindow.dateOverrideId @Column`; adding `insertable=false, updatable=false` to `@JoinColumn` fixes the boot error but does not fix issue 2.
> 2. **L1-cache stale collection** — when windows are persisted in the same `@TestTransaction` via `DateOverrideWindow.dateOverrideId` (the owning side), Hibernate's L1 cache returns the parent entity with its still-empty `windows` ArrayList even after a `JOIN FETCH` query or `EntityManager.refresh()`. The fix is to mark `windows` as `@Transient` and populate it explicitly in `resolve()` via a separate `DateOverrideWindow.list(...)` query — this always reads current rows regardless of L1 cache state.

```java
package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Replace-semantics availability override for a single date.
 * An empty {@link #windows} list means the day is OFF (blocked).
 * Resolution order: per-type override, else global override, else null
 * (null => fall through to weekly {@link AvailabilityRule}).
 *
 * <p>The {@link #windows} collection is {@link Transient}: it is populated by
 * {@link #resolve} via an explicit {@link DateOverrideWindow} query, which avoids
 * both the Hibernate "repeated column in mapping" boot error (date_override_id mapped
 * on both parent @OneToMany and child @Column) and the L1-cache stale-collection
 * problem that arises when windows are persisted in the same transaction via the
 * child's {@code dateOverrideId} field rather than through the parent collection.
 */
@Entity
@Table(name = "date_override")
public class DateOverride extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Null = global override; otherwise scoped to this meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    @Column(name = "override_date", nullable = false)
    public LocalDate date;

    /**
     * Bookable windows for this date. Empty = day off (caller emits no slots).
     * Populated by {@link #resolve}; not persisted by this entity.
     */
    @Transient
    public List<DateOverrideWindow> windows = new ArrayList<>();

    /**
     * Per-type override for (meetingTypeId, date) if present; else the global
     * (meeting_type_id IS NULL) override for the date; else null.
     *
     * <p>Windows are loaded via a separate query ordered by start_time, so ordering
     * is correct regardless of insert order.
     */
    public static DateOverride resolve(Long meetingTypeId, LocalDate date) {
        DateOverride typed = find("meetingTypeId = ?1 and date = ?2", meetingTypeId, date).firstResult();
        if (typed != null) {
            typed.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", typed.id);
            return typed;
        }
        DateOverride global = find("meetingTypeId is null and date = ?1", date).firstResult();
        if (global != null) {
            global.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", global.id);
        }
        return global;
    }
}
```

> **Note on test/entity coupling:** the test helper persists each `DateOverrideWindow` directly (setting `dateOverrideId`) rather than adding to the parent's collection, then re-reads through `resolve`. This exercises the real DB query load path. Because `windows` is populated inside `resolve` via a fresh `DateOverrideWindow.list(...)` query, it always reflects current DB state.

- [ ] **Step 5: Run it to confirm it passes**

Run: `mvn test -Dtest=DateOverrideTest`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/calit/domain/DateOverride.java \
  src/main/java/com/calit/domain/DateOverrideWindow.java \
  src/test/java/com/calit/domain/DateOverrideTest.java
git commit -m "feat: add DateOverride/DateOverrideWindow with per-type->global resolve"
```

---

### Task 5: SlotService date-override integration

Edit `generateRawSlots` so that, per date, it first calls `DateOverride.resolve(type.id, date)`:
- **Override present:** use THAT override's windows as the day's bookable windows. An empty windows list → no slots that day (day off), REPLACING weekly hours.
- **Override absent (null):** fall back to the existing weekly-rule resolution (`rulesFor`).

The per-window back-to-back slot stepping is unchanged. Min-notice/horizon filtering is NOT added here — that is Plan 3.

**Files:**
- Edit: `src/main/java/com/calit/availability/SlotService.java`
- Test: `src/test/java/com/calit/availability/SlotServiceOverrideTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/calit/availability/SlotServiceOverrideTest.java`:

```java
package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.DateOverride;
import com.calit.domain.DateOverrideWindow;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SlotServiceOverrideTest {

    @Inject
    SlotService slotService;

    private static final LocalDate WORKDAY = LocalDate.of(2026, 6, 8); // Monday

    @Test
    @TestTransaction
    void overrideWindowReplacesWeeklyHoursForThatDate() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-replace", 60);
        // Weekly rule says 09:00-11:00 (would be 2 slots).
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        // Override for this exact date says a single 13:00-14:00 window (1 slot), replacing weekly.
        DateOverride o = override(t.id, WORKDAY);
        o.persist();
        window(o, "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(13, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void emptyWindowOverrideYieldsZeroSlotsEvenWithWeeklyRule() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-dayoff", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00"); // weekly rule exists
        DateOverride dayOff = override(t.id, WORKDAY);
        dayOff.persist(); // empty windows = day off

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @TestTransaction
    void dateWithoutOverrideStillUsesWeeklyRules() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-fallback", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00"); // no override -> weekly applies

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(1).start().toLocalTime());
    }

    // --- helpers ---

    private void seedSettings(String zone) {
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = zone;
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = null;
        r.persist();
    }

    private DateOverride override(Long meetingTypeId, LocalDate date) {
        DateOverride o = new DateOverride();
        o.meetingTypeId = meetingTypeId;
        o.date = date;
        return o;
    }

    private void window(DateOverride parent, String start, String end) {
        DateOverrideWindow w = new DateOverrideWindow();
        w.dateOverrideId = parent.id;
        w.startTime = LocalTime.parse(start);
        w.endTime = LocalTime.parse(end);
        w.persist();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `mvn test -Dtest=SlotServiceOverrideTest`
Expected: FAIL — `overrideWindowReplacesWeeklyHoursForThatDate` and `emptyWindowOverrideYieldsZeroSlotsEvenWithWeeklyRule` fail because `generateRawSlots` still uses weekly rules unconditionally (the override is ignored; the replace test sees the weekly 2 slots, the day-off test sees 2 slots instead of 0). `dateWithoutOverrideStillUsesWeeklyRules` already passes.

- [ ] **Step 3: Edit `SlotService`**

In `src/main/java/com/calit/availability/SlotService.java`, add the import `com.calit.domain.DateOverride` (next to the existing `com.calit.domain.*` imports), and replace the per-date loop body so it consults the resolved override first.

Replace this existing block:

```java
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (AvailabilityRule rule : rulesFor(type.id, date.getDayOfWeek())) {
                LocalTime start = rule.startTime;
                while (!start.plusMinutes(type.durationMinutes).isAfter(rule.endTime)) {
                    LocalTime end = start.plusMinutes(type.durationMinutes);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                    start = end;
                }
            }
        }
```

with:

```java
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Window window : windowsFor(type.id, date)) {
                LocalTime start = window.start();
                while (!start.plusMinutes(type.durationMinutes).isAfter(window.end())) {
                    LocalTime end = start.plusMinutes(type.durationMinutes);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                    start = end;
                }
            }
        }
```

Add a small `Window` record and a `windowsFor` helper to the class (and keep the existing `rulesFor` helper — it is now called only from `windowsFor`):

```java
    /** A bookable [start, end) time-of-day window for one day, from either an override or a weekly rule. */
    record Window(LocalTime start, LocalTime end) {}

    /**
     * Resolves the day's bookable windows. A {@link DateOverride} for the date REPLACES the weekly
     * hours: its windows are used as-is (empty list => day off => no windows). When no override
     * exists, the weekly rules apply. (Min-notice/horizon filters are applied in Plan 3, not here.)
     */
    List<Window> windowsFor(Long meetingTypeId, LocalDate date) {
        DateOverride override = DateOverride.resolve(meetingTypeId, date);
        if (override != null) {
            return override.windows.stream()
                    .map(w -> new Window(w.startTime, w.endTime))
                    .toList();
        }
        return rulesFor(meetingTypeId, date.getDayOfWeek()).stream()
                .map(r -> new Window(r.startTime, r.endTime))
                .toList();
    }
```

The existing `rulesFor(Long, DayOfWeek)` helper is unchanged. Required imports already present (`LocalDate`, `LocalTime`, `List`); add `com.calit.domain.DateOverride`.

- [ ] **Step 4: Run it to confirm it passes**

Run: `mvn test -Dtest=SlotServiceOverrideTest`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Run the original SlotService suite (no regression)**

Run: `mvn test -Dtest=SlotServiceTest`
Expected: PASS — the original 5 weekly-rule tests still pass (none of them define an override, so the weekly fallback path is exercised unchanged).

- [ ] **Step 6: Run the full suite**

Run: `mvn test`
Expected: PASS — all tests (Plan 1 suite plus the new MeetingType/OwnerSettings/DateOverride/SlotServiceOverride tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/calit/availability/SlotService.java \
  src/test/java/com/calit/availability/SlotServiceOverrideTest.java
git commit -m "feat: integrate DateOverride replace-semantics into SlotService"
```

---

## Self-Review against spec

**1. Spec coverage (Plan 1b scope):**
| Requirement | Task |
|---|---|
| Min scheduling notice + booking horizon — columns only (feat 11) | Task 1 (`min_notice_minutes`, `horizon_days`), Task 2 (`minNoticeMinutes`, `horizonDays` fields). Filtering relative to "now" is Plan 3 (documented; NOT applied in SlotService here). |
| Date-specific availability overrides, replace semantics, per-type→global, empty=day off (feat 12) | Task 1 (`date_override`/`date_override_window` tables + unique scope/date index), Task 4 (`DateOverride`/`DateOverrideWindow` + `resolve`), Task 5 (SlotService integration: override windows REPLACE weekly hours; empty = day off). |
| Per-type meeting location: Meet/phone/in-person/custom (feat 13) | Task 1 (`location_type`/`location_detail`), Task 2 (`LocationType` enum + `locationType`/`locationDetail`). Conference-only-for-GOOGLE_MEET is Plan 2; emails Plan 4. |
| Per-type approval flag (feat 14a) | Task 1 (`requires_approval`), Task 2 (`requiresApproval`). PENDING workflow itself is Plan 3. |
| Owner-notify opt-out (—) | Task 1 (`owner_notifications_enabled`), Task 3 (`ownerNotificationsEnabled`). Gating of owner emails is Plan 4. |

Out of scope here (later plans): min-notice/horizon enforcement (Plan 3), approval workflow + PENDING hold (Plan 3), Meet/location rendering (Plans 2/4), override admin UI (Plan 5).

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to" placeholders. Every task shows full code (exact field declarations, full entity sources, the exact SlotService old→new block) and exact `mvn test -Dtest=...` commands with explicit FAIL/PASS expectations.

**3. Type consistency (against the overview's "Added in Plan 1b" contract):**
- `MeetingType` gains exactly: `int minNoticeMinutes = 0`, `int horizonDays = 60`, `@Enumerated(STRING) LocationType locationType = LocationType.GOOGLE_MEET`, `String locationDetail` (nullable), `boolean requiresApproval = false`; inner enum `LocationType { GOOGLE_MEET, PHONE, IN_PERSON, CUSTOM }` (GOOGLE_MEET default). Column names/defaults match V2 (`min_notice_minutes` 0, `horizon_days` 60, `location_type` 'GOOGLE_MEET', `location_detail` nullable TEXT, `requires_approval` FALSE).
- `OwnerSettings` gains exactly: `boolean ownerNotificationsEnabled = true` (column `owner_notifications_enabled` DEFAULT TRUE).
- `DateOverride`: `Long meetingTypeId` (null = global), `LocalDate date` (column `override_date`), `List<DateOverrideWindow> windows` (empty = day off); `DateOverrideWindow`: `Long dateOverrideId`, `LocalTime startTime`, `LocalTime endTime`. Static `resolve(Long meetingTypeId, LocalDate date)` → per-type, else global, else `null` — exactly the contract shape.
- `SlotService.generateRawSlots(MeetingType, LocalDate, LocalDate)` signature unchanged; behavior now: resolved override windows REPLACE weekly hours per date (empty = day off), else weekly `rulesFor`. Min-notice/horizon are columns only here and are filtered in Plan 3 — consistent with the overview.

**Migration ordering:** This plan's migration is **V2**; Plan 2 is **V3** and Plan 3 is **V4** (renumbered so Plan 1b applies first). Stated at the top of this file and honored by `V2__domain_extensions.sql`.

**Known assumptions (carried to Plan 3):** override windows do not cross midnight (same assumption as weekly rules); slot granularity equals duration; min-notice/horizon stored but not yet filtered.
