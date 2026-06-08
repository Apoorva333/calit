package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRescheduled;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class ReminderScheduler {

    @ConfigProperty(name = "calit.reminders.lead-minutes", defaultValue = "1440")
    int leadMinutes;

    // --- lifecycle observers (creation/recompute/delete side) ---

    /** Auto-confirmed at book time. */
    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        scheduleReminder(e.bookingId());
    }

    /** PENDING -> CONFIRMED via owner approval. */
    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        scheduleReminder(e.bookingId());
    }

    /** Cancelled by invitee. */
    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        onCancelledOrDeclined(e.bookingId());
    }

    /** Declined by owner OR auto-expired (Plan 6 expiry tick fires BookingDeclined). */
    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        onCancelledOrDeclined(e.bookingId());
    }

    /** Auto-type reschedule stays CONFIRMED at a new time: recompute the reminder. */
    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        scheduleReminder(e.bookingId());
    }

    /**
     * Create (or replace) the unsent reminder for a now-CONFIRMED booking at
     * (startUtc - leadMinutes). Skips if that instant is already in the past.
     * Opens its own transaction (AFTER_SUCCESS observers have no active one).
     */
    public void scheduleReminder(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking booking = Booking.findById(bookingId);
            if (booking == null) {
                return;
            }
            Instant sendAt = booking.startUtc.minus(leadMinutes, ChronoUnit.MINUTES);
            // Booking made inside the lead window: nothing to remind about ahead of time.
            if (!sendAt.isAfter(Instant.now())) {
                return;
            }
            // Never double-schedule (re-confirm / reschedule recompute).
            Reminder.deleteUnsentFor(bookingId);
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
        });
    }

    /** Drop the future unsent reminder for a cancelled/declined/expired booking. */
    public void onCancelledOrDeclined(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> Reminder.deleteUnsentFor(bookingId));
    }
}
