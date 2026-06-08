package com.calit.booking;

import com.calit.availability.SlotService;
import com.calit.availability.TimeSlot;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import com.calit.google.BusyInterval;
import com.calit.google.CalendarPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingRequested;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType.LocationType;
import com.calit.google.CreatedEvent;
import jakarta.enterprise.event.Event;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class BookingService {

    @Inject
    SlotService slotService;

    @Inject
    CalendarPort calendarPort;

    @Inject
    TurnstileVerifier turnstileVerifier;

    @Inject
    Event<BookingRequested> bookingRequestedEvent;

    @Inject
    Event<BookingConfirmed> bookingConfirmedEvent;

    @ConfigProperty(name = "calit.abuse.per-email-daily-cap", defaultValue = "10")
    long perEmailDailyCap;

    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to) {
        return availableSlots(type, from, to, null);
    }

    /**
     * Bookable slots = raw work-hour slots (Plan 1b override semantics already applied) whose
     * buffered interval does not overlap any busy interval — busy = Google free/busy
     * (only when {@code isConnected()}) + all PENDING/CONFIRMED bookings — and which also survive
     * the min-notice and horizon filters relative to {@code now} (feature 11). {@code excludeBookingId}
     * omits one booking from the busy set (used by reschedule so a booking can move within its own window).
     */
    public List<TimeSlot> availableSlots(MeetingType type, LocalDate from, LocalDate to,
                                         Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Interval> busy = busyIntervals(fromInstant, toInstant, excludeBookingId);

        // Feature 11 bounds, captured once relative to request time.
        Instant now = Instant.now();
        Instant earliest = now.plusSeconds(60L * type.minNoticeMinutes);
        Instant latest = now.plus(type.horizonDays, ChronoUnit.DAYS);

        List<TimeSlot> raw = slotService.generateRawSlots(type, from, to);
        List<TimeSlot> available = new ArrayList<>();
        for (TimeSlot slot : raw) {
            Instant slotStart = slot.start().toInstant();
            // Feature 11: drop too-soon (before now+minNotice) and too-far (after now+horizon) slots.
            if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) {
                continue;
            }
            Interval buffered = new Interval(
                    slotStart.minusSeconds(60L * type.bufferBeforeMinutes),
                    slot.end().toInstant().plusSeconds(60L * type.bufferAfterMinutes));
            if (!buffered.overlapsAny(busy)) {
                available.add(slot);
            }
        }
        return available;
    }

    /**
     * Google busy intervals (only when connected — degraded mode skips freeBusy) plus all
     * PENDING+CONFIRMED bookings in the window (minus an excluded one). PENDING is included so a
     * pending approval request holds its slot (feature 14).
     */
    List<Interval> busyIntervals(Instant from, Instant to, Long excludeBookingId) {
        List<Interval> busy = new ArrayList<>();
        if (calendarPort.isConnected()) {
            for (BusyInterval bi : calendarPort.freeBusy(from, to)) {
                busy.add(new Interval(bi.start(), bi.end()));
            }
        }
        for (Booking b : Booking.<Booking>heldOverlapping(from, to)) {
            if (excludeBookingId != null && excludeBookingId.equals(b.id)) {
                continue;
            }
            busy.add(new Interval(b.startUtc, b.endUtc));
        }
        return busy;
    }

    @Transactional
    public Booking book(String meetingTypeSlug, Instant startUtc,
                        String inviteeName, String inviteeEmail,
                        Map<String, String> answers, String turnstileToken, String honeypot) {
        MeetingType type = MeetingType.findBySlug(meetingTypeSlug);
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + meetingTypeSlug);
        }

        // Feature 16: all three abuse guards run first, inside book(). The Plan 5 web layer
        // just forwards the cf-turnstile-response (turnstileToken) and website (honeypot) form values.
        turnstileVerifier.verify(turnstileToken);          // -> AbuseException (400) when enabled & invalid
        if (honeypot != null && !honeypot.isBlank()) {     // a bot filled the hidden field
            throw new AbuseException("Honeypot field was filled.");  // -> AbuseException (400)
        }
        enforcePerEmailDailyCap(inviteeEmail);             // -> RateLimitException (429) over cap

        Map<String, String> submitted = answers == null ? Map.of() : answers;

        // Feature 10: every required custom field must have a non-blank value. Built-in
        // name/email are method params, not BookingField rows, so they are not in this loop.
        validateRequiredFields(type, submitted);

        Instant endUtc = startUtc.plusSeconds(60L * type.durationMinutes);

        // App-level availability re-check: nice errors + buffer/min-notice/horizon enforcement
        // (the DB constraint only guards raw-time overlap, not buffers).
        assertSlotAvailable(type, startUtc, null);

        Booking booking = new Booking();
        booking.meetingTypeId = type.id;
        booking.inviteeName = inviteeName;
        booking.inviteeEmail = inviteeEmail;
        booking.startUtc = startUtc;
        booking.endUtc = endUtc;
        booking.createdAt = Instant.now();
        booking.manageToken = UUID.randomUUID().toString();
        booking.answers = submitted;
        // Feature 14: approval types hold the slot as PENDING; auto types are CONFIRMED immediately.
        booking.status = type.requiresApproval ? BookingStatus.PENDING : BookingStatus.CONFIRMED;

        // NFR cross-node guard: persist + flush now so a concurrent replica's overlapping
        // held (PENDING|CONFIRMED) row trips the `booking_no_overlap_held` exclusion constraint
        // here, surfaced as the same 409 the app-level check uses (instead of a 500).
        try {
            booking.persistAndFlush();
        } catch (PersistenceException ex) {
            if (isNoOverlapViolation(ex)) {
                throw new BookingConflictException(
                        "Slot " + startUtc + " is not available for " + type.slug);
            }
            throw ex;
        }

        if (type.requiresApproval) {
            // Feature 14: PENDING request — NO Google event yet; the owner approves/declines later.
            bookingRequestedEvent.fire(new BookingRequested(booking.id));
            return booking;
        }

        // Auto type: create the Google event when connected (degraded mode skips it entirely).
        // If createEvent throws, the @Transactional boundary rolls back this booking (no orphan row).
        // `createGoogleEvent` (shared with `approve`, added in Task 7) applies the feature-13
        // location logic: createMeetLink=(locationType==GOOGLE_MEET), locationText=locationDetail.
        if (calendarPort.isConnected()) {
            createGoogleEvent(type, booking);
        }

        bookingConfirmedEvent.fire(new BookingConfirmed(booking.id));
        return booking;
    }

    /**
     * Creates the Google event for a CONFIRMED booking and stores its ids. Applies the feature-13
     * location logic: {@code createMeetLink = (locationType == GOOGLE_MEET)},
     * {@code locationText = locationDetail}. Shared by {@code book} (auto branch) and {@code approve}.
     * Caller must guard with {@code calendarPort.isConnected()} (degraded mode skips this entirely).
     */
    private void createGoogleEvent(MeetingType type, Booking booking) {
        OwnerSettings owner = OwnerSettings.get();
        CreatedEvent created = calendarPort.createEvent(
                type.name + " with " + booking.inviteeName,
                "Booked via calit.",
                booking.startUtc, booking.endUtc,
                List.of(booking.inviteeEmail, owner.ownerEmail),
                type.locationType == LocationType.GOOGLE_MEET,
                type.locationDetail);
        booking.googleEventId = created.googleEventId();
        booking.meetLink = created.meetLink();
    }

    /**
     * Feature 16: rejects the booking (HTTP 429) if this invitee email already created at least
     * {@code perEmailDailyCap} bookings during today's owner-tz day window.
     */
    private void enforcePerEmailDailyCap(String inviteeEmail) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate today = Instant.now().atZone(zone).toLocalDate();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        if (Booking.countByEmailCreatedBetween(inviteeEmail, dayStart, dayEnd) >= perEmailDailyCap) {
            throw new RateLimitException(
                    "Daily booking cap reached for " + inviteeEmail);
        }
    }

    /**
     * Feature 10: rejects the booking (HTTP 422) if any required field in
     * {@code BookingField.formFor(type.id)} is missing or blank in the submitted answers.
     */
    private void validateRequiredFields(MeetingType type, Map<String, String> answers) {
        for (BookingField field : BookingField.formFor(type.id)) {
            if (field.required) {
                String value = answers.get(field.fieldKey);
                if (value == null || value.isBlank()) {
                    throw new BookingValidationException(
                            "Required field '" + field.fieldKey + "' is missing or blank");
                }
            }
        }
    }

    /** True if {@code ex} (or a cause) is the no-overlap exclusion-constraint violation. */
    private boolean isNoOverlapViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && "booking_no_overlap_held".equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** Throws BookingConflictException unless an available slot starts exactly at {@code startUtc}. */
    private void assertSlotAvailable(MeetingType type, Instant startUtc, Long excludeBookingId) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate day = startUtc.atZone(zone).toLocalDate();
        boolean ok = availableSlots(type, day, day, excludeBookingId).stream()
                .anyMatch(s -> s.start().toInstant().equals(startUtc));
        if (!ok) {
            throw new BookingConflictException(
                    "Slot " + startUtc + " is not available for " + type.slug);
        }
    }
}
