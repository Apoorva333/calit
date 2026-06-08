package com.calit.booking;

import com.calit.availability.TimeSlot;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApproveDeclineTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 15);
    private static final Instant SLOT_09 = Instant.parse("2026-06-15T07:00:00Z"); // 09:00 local

    @Test
    @TestTransaction
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        seedSettings();
        approvalType("approve");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h"));

        Booking b = bookingService.book("approve", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        assertEquals(BookingStatus.PENDING, b.status);

        bookingService.approve(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("evt-ap", loaded.googleEventId);
        assertEquals("https://meet.google.com/ap-1-2", loaded.meetLink);
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1)).createEvent(anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")),
                eq(true), eq(null));
    }

    @Test
    @TestTransaction
    void declineFlipsToDeclinedFreesSlotAndCreatesNoEvent() {
        // Feature 14: decline a PENDING request -> DECLINED, slot freed, no Google event.
        seedSettings();
        MeetingType t = approvalType("decline");
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());

        Booking b = bookingService.book("decline", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "");
        // While PENDING, the 09:00 slot is held.
        assertTrue(bookingService.availableSlots(t, MONDAY, MONDAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        bookingService.decline(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.DECLINED, loaded.status);
        verify(calendarPort, never()).createEvent(anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        // DECLINED leaves the partial constraint -> 09:00 is bookable again.
        List<TimeSlot> avail = bookingService.availableSlots(t, MONDAY, MONDAY);
        assertTrue(avail.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    // --- helpers ---

    private void seedSettings() {
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType approvalType(String slug) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = true; // feature 14
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }
}
