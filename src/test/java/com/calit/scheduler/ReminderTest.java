package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ReminderTest {

    @Test
    @TestTransaction
    void persistsAndReadsBackAllFields() {
        // reminder.booking_id REFERENCES booking(id), so seed a real booking first (Plan 6 deviation).
        Long bookingId = seedBooking();
        Instant sendAt = Instant.parse("2026-06-07T07:00:00Z");
        Reminder r = new Reminder();
        r.bookingId = bookingId;
        r.sendAt = sendAt;
        r.kind = Reminder.KIND_REMINDER;
        r.sentAt = null;
        r.persist();

        Reminder loaded = Reminder.findById(r.id);
        assertEquals(bookingId, loaded.bookingId);
        assertEquals(sendAt, loaded.sendAt);
        assertEquals("REMINDER", loaded.kind);
        assertNull(loaded.sentAt);
    }

    @Test
    @TestTransaction
    void deleteUnsentForRemovesOnlyUnsentRowsOfThatBooking() {
        // reminder.booking_id REFERENCES booking(id), so seed real bookings (Plan 6 deviation).
        // Distinct start slots so the booking_no_overlap_held exclusion guard doesn't trip.
        Long a = seedBookingAt(Instant.parse("2026-06-08T07:00:00Z"));
        Long b = seedBookingAt(Instant.parse("2026-06-08T09:00:00Z"));
        Instant base = Instant.parse("2026-06-07T07:00:00Z");
        persist(a, base, null);                 // unsent for a -> deleted
        persist(a, base, base.minusSeconds(1)); // already sent for a -> kept
        persist(b, base, null);                 // unsent for a different booking -> kept

        Reminder.deleteUnsentFor(a);

        assertEquals(0, Reminder.count("bookingId = ?1 and sentAt is null", a));
        assertEquals(1, Reminder.count("bookingId = ?1 and sentAt is not null", a));
        assertEquals(1, Reminder.count("bookingId = ?1 and sentAt is null", b));
    }

    private Long seedBooking() {
        return seedBookingAt(Instant.parse("2026-06-08T07:00:00Z"));
    }

    private Long seedBookingAt(Instant start) {
        MeetingType t = new MeetingType();
        t.name = "rem-" + System.nanoTime();
        t.slug = "rem-" + System.nanoTime();
        t.durationMinutes = 30;
        t.persist();
        Booking b = new Booking();
        b.meetingTypeId = t.id;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = start;
        b.endUtc = start.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }

    private void persist(Long bookingId, Instant sendAt, Instant sentAt) {
        Reminder r = new Reminder();
        r.bookingId = bookingId;
        r.sendAt = sendAt;
        r.kind = Reminder.KIND_REMINDER;
        r.sentAt = sentAt;
        r.persist();
    }
}
