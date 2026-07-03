package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlugCollisionTest {

    @Inject
    MeetingHosts meetingHosts;

    @Test
    @TestTransaction
    void addCohostRejectedWhenCandidateOwnsSameSlug() {
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.meetingType(v.id, "intro", 30); // Volodya already owns /volodya/intro
        MeetingType shared = MultiHostFixtures.meetingType(1L, "intro", 30); // Pasha's shared intro
        assertThrows(IllegalStateException.class, () -> meetingHosts.addCohost(shared, v));
    }

    @Test
    @TestTransaction
    void slugUsedByOwnerExcludesSelf() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        assertTrue(MeetingType.slugUsedByOwner(1L, "intro", null));
        assertFalse(MeetingType.slugUsedByOwner(1L, "intro", t.id)); // exclude the type itself
    }
}
