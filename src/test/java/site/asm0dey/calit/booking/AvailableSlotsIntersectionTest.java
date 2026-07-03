package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static site.asm0dey.calit.test.MultiHostFixtures.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AvailableSlotsIntersectionTest {

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

    @InjectMock
    CalendarPort calendarPort;

    private final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    /** creator (id 1) free 09-12, cohost free 10-12 -> intersection 10-12. */
    private MeetingType twoHostType() {
        settings(1L, "pasha");
        AppUser v = enabledUser("volodya");
        settings(v.id, "volodya");
        var mon = DayOfWeek.MONDAY;
        rule(1L, mon, 9, 12);
        rule(v.id, mon, 10, 12);
        return acceptedTwoHostType(1L, v.id, "intro", 60, false);
    }

    @Test
    @TestTransaction
    void intersectsHostWindows() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        List<TimeSlot> slots = bookingService.availableSlots(t, mon, mon);
        // 60-min slots in 10:00-12:00 -> 10:00 and 11:00 only (09:00 excluded: cohost busy)
        assertEquals(2, slots.size());
        assertEquals(
                java.time.LocalTime.of(10, 0),
                slots.get(0).start().withZoneSameInstant(AMS).toLocalTime());
    }

    @Test
    @TestTransaction
    void brokenHostCalendarFailsClosedToEmpty() {
        MeetingType t = twoHostType();
        // cohost id is the 2nd host; make its freeBusy throw
        Long cohostId = meetingHosts.hostOwnerIds(t).stream()
                .filter(id -> id != 1L)
                .findFirst()
                .orElseThrow();
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.freeBusy(eq(cohostId), any(), any()))
                .thenThrow(new CalendarUnavailableException("needs reconnect"));
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }

    @Test
    @TestTransaction
    void notBookableTypeYieldsNoSlots() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        MeetingTypeHost.find(t.id, meetingHosts.hostOwnerIds(t).get(1)).status = MeetingTypeHost.PENDING;
        MeetingTypeHost.find(t.id, 1L).persistAndFlush();
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }
}
