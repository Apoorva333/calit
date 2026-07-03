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

    /**
     * A MeetingType owned by {@code creatorId} (large horizonDays so tests never fall outside it) plus
     * two ACCEPTED {@link site.asm0dey.calit.domain.MeetingTypeHost} rows (creator + cohost). Caller
     * still seeds {@code OwnerSettings}/rules/windows separately via {@link #settings} / {@link #rule}.
     */
    public static MeetingType acceptedTwoHostType(
            long creatorId, long cohostId, String slug, int durationMinutes, boolean requiresApproval) {
        MeetingType t = new MeetingType();
        t.ownerId = creatorId;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = durationMinutes;
        t.horizonDays = 50000;
        t.requiresApproval = requiresApproval;
        t.persist();
        site.asm0dey.calit.domain.MeetingTypeHost.of(
                        t.id,
                        creatorId,
                        site.asm0dey.calit.domain.MeetingTypeHost.CREATOR,
                        site.asm0dey.calit.domain.MeetingTypeHost.ACCEPTED)
                .persist();
        site.asm0dey.calit.domain.MeetingTypeHost.of(
                        t.id,
                        cohostId,
                        site.asm0dey.calit.domain.MeetingTypeHost.COHOST,
                        site.asm0dey.calit.domain.MeetingTypeHost.ACCEPTED)
                .persist();
        return t;
    }
}
