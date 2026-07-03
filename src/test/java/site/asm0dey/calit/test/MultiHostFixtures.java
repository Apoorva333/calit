package site.asm0dey.calit.test;

import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * Shared test seed helpers for the multi-host feature. Kept minimal — only what current tests
 * need (YAGNI); later multi-host tasks add more helpers here rather than inlining per test class.
 */
public final class MultiHostFixtures {
    private MultiHostFixtures() {}

    public static MeetingType meetingType(long ownerId, String slug, int durationMinutes) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = durationMinutes;
        t.persist();
        return t;
    }

    /** Enabled, onboarded (settingsComplete) user — the minimum shape for a co-host candidate. */
    public static AppUser enabledUser(String username) {
        AppUser u = AppUser.create(username, "x", false);
        u.settingsComplete = true;
        u.persist();
        return u;
    }

    public static OwnerSettings settings(long ownerId, String name) {
        OwnerSettings o = new OwnerSettings();
        o.ownerId = ownerId;
        o.ownerName = name;
        o.ownerEmail = name + "@x.com";
        o.timezone = "Europe/Amsterdam";
        o.persist();
        return o;
    }

    public static AvailabilityRule rule(long ownerId, java.time.DayOfWeek day, int startHour, int endHour) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.dayOfWeek = day;
        r.startTime = java.time.LocalTime.of(startHour, 0);
        r.endTime = java.time.LocalTime.of(endHour, 0);
        r.persist();
        return r;
    }
}
