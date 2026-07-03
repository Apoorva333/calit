package site.asm0dey.calit.test;

import site.asm0dey.calit.domain.MeetingType;
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
}
