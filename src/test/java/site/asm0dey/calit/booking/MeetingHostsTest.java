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
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsTest {

    @Inject
    MeetingHosts meetingHosts;

    @Inject
    EntityManager em;

    @InjectMock
    CalendarPort calendarPort;

    private MeetingType multiHostType() {
        // admin id 1 is the creator; make a second enabled user as co-host.
        AppUser cohost = MultiHostFixtures.enabledUser("volodya");
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        t.bufferBeforeMinutes = 5;
        t.bufferAfterMinutes = 10;
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
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
        creator.bufferBeforeMinutes = 20; // override
        em.flush();
        assertEquals(20, meetingHosts.effectiveBufferBefore(t, 1L)); // overridden
        assertEquals(10, meetingHosts.effectiveBufferAfter(t, 1L)); // inherits type
    }
}
