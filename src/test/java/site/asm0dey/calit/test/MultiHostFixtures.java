package site.asm0dey.calit.test;

import site.asm0dey.calit.domain.MeetingType;

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
}
