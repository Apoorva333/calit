package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingDetailsChanged;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 12: group edit — title/description write to every group row, guests reconcile on the lead row
 * only, and the one shared Google event is patched exactly once (via the organizer's row).
 */
@QuarkusTest
class GroupEditDetailsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    static final AtomicInteger DETAILS_CHANGED = new AtomicInteger();

    void onDetailsChanged(@Observes BookingDetailsChanged e) {
        DETAILS_CHANGED.incrementAndGet();
    }

    private Instant nextMonday(int hour) {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(hour, 0).atZone(AMS).toInstant();
    }

    /** Admin (id 1, "pasha") as creator + a second accepted co-host ("volodya"), both with rules covering Monday. */
    private MeetingType groupType() {
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(v.id, "volodya");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        return MultiHostFixtures.acceptedTwoHostType(1L, v.id, "intro", 60, false);
    }

    private void stubOrganizerOnCreator() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("grp-evt", "meet", "cal"));
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWritesTitleToAllRowsPatchesEventOnceAndReconcilesGuestsOnLeadOnly() {
        stubOrganizerOnCreator();
        groupType(); // auto-confirm -> event created immediately

        Booking lead = bookingService.book(
                1L, "intro", nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        var detailsChangedBefore = DETAILS_CHANGED.get();

        // The co-host (not the creator/organizer) initiates the edit -> keyed by ITS manageToken.
        Booking cohostRow =
                rows.stream().filter(r -> !r.ownerId.equals(1L)).findFirst().orElseThrow();
        bookingService.updateDetails(cohostRow.manageToken, "Roadmap sync", "Q3 planning", List.of("ana@x.com"), true);

        // title/description written to every group row, identically.
        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertEquals("Roadmap sync", r.title);
            assertEquals("Q3 planning", r.description);
        });

        // the shared Google event is patched exactly once, via the organizer (creator, owner id 1).
        verify(calendarPort, times(1))
                .updateEventDetails(eq(1L), eq("grp-evt"), eq("Roadmap sync with Sam"), eq("Q3 planning"), anyList());

        // guests reconcile on the lead row only.
        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        List<BookingGuest> leadGuests = BookingGuest.activeForBooking(freshLead.id);
        assertEquals(1, leadGuests.size());
        assertEquals("ana@x.com", leadGuests.getFirst().email);

        Booking cohostAfter = Booking.<Booking>group(lead.groupId).stream()
                .filter(r -> !r.ownerId.equals(1L))
                .findFirst()
                .orElseThrow();
        assertTrue(BookingGuest.activeForBooking(cohostAfter.id).isEmpty(), "guests never attach to a non-lead row");

        // exactly one BookingDetailsChanged fired, keyed by the lead.
        assertEquals(detailsChangedBefore + 1, DETAILS_CHANGED.get());
    }
}
