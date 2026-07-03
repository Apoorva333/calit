package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * Task 14: reminders and pending-expiry must be group-aware. A group's reminder belongs to the
 * lead row only (no N reminders for one conceptual meeting); an expiry tick on any still-PENDING
 * row of a group declines the WHOLE group and sends exactly one declined email (fanned out by
 * {@link site.asm0dey.calit.email.EmailService#enqueueDeclined} per host, keyed on the lead row).
 */
@QuarkusTest
class GroupSchedulerTest {

    private static final long CREATOR_ID = 1L; // DatabaseResetCallback baseline admin
    private static final long COHOST_ID = 2L;
    private static final String INVITEE_EMAIL = "invitee-groupsched@example.com";

    @Inject
    ReminderScheduler reminderScheduler;

    @Inject
    PendingExpiryScheduler expiryScheduler;

    private MeetingType twoHostType(boolean requiresApproval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost-sched");
            MultiHostFixtures.settings(CREATOR_ID, "Creator");
            MultiHostFixtures.settings(COHOST_ID, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(CREATOR_ID, COHOST_ID, "group-sched", 30, requiresApproval);
        });
    }

    private record GroupIds(long leadId, long cohostId) {}

    private GroupIds seedGroup(
            long meetingTypeId,
            Instant start,
            BookingStatus leadStatus,
            Instant leadCreatedAt,
            BookingStatus cohostStatus,
            Instant cohostCreatedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var groupId = UUID.randomUUID();
            long leadId = seedRow(meetingTypeId, CREATOR_ID, groupId, start, leadStatus, leadCreatedAt);
            long cohostId = seedRow(meetingTypeId, COHOST_ID, groupId, start, cohostStatus, cohostCreatedAt);
            return new GroupIds(leadId, cohostId);
        });
    }

    private long seedRow(
            long meetingTypeId, long ownerId, UUID groupId, Instant start, BookingStatus status, Instant createdAt) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = meetingTypeId;
        b.inviteeName = "Sam Invitee";
        b.inviteeEmail = INVITEE_EMAIL;
        b.startUtc = start;
        b.endUtc = start.plus(30, ChronoUnit.MINUTES);
        b.status = status;
        b.groupId = groupId;
        b.manageToken = "tok-" + ownerId + "-" + System.nanoTime();
        b.createdAt = createdAt;
        b.persist();
        return b.id;
    }

    @Test
    void onlyLeadRowGetsReminderOnGroupConfirm() {
        MeetingType type = twoHostType(false);
        var start = Instant.now().plus(500, ChronoUnit.HOURS); // well beyond the default 1440-min lead
        GroupIds ids = seedGroup(
                type.id, start, BookingStatus.CONFIRMED, Instant.now(), BookingStatus.CONFIRMED, Instant.now());

        // Simulate both rows firing scheduleReminder -- BookingConfirmed only fires once with the
        // lead id in practice, but the guard must hold even if a non-lead row's event somehow fires.
        reminderScheduler.scheduleReminder(ids.leadId());
        reminderScheduler.scheduleReminder(ids.cohostId());

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, Reminder.count("bookingId", ids.leadId()), "lead row gets exactly one reminder");
            assertEquals(0, Reminder.count("bookingId", ids.cohostId()), "non-lead row never gets its own reminder");
        });
    }

    @Test
    void groupExpiryDeclinesWholeGroupWhenOneRowStillPendingAtDeadline() {
        MeetingType type = twoHostType(true);
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        GroupIds ids = seedGroup(
                type.id,
                start,
                BookingStatus.CONFIRMED,
                Instant.now().minus(2, ChronoUnit.HOURS), // lead already approved
                BookingStatus.PENDING,
                Instant.now().minus(25, ChronoUnit.HOURS)); // cohost still pending, past the 24h hold

        expiryScheduler.expirePendingBookings();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(
                    BookingStatus.DECLINED,
                    ((Booking) Booking.findById(ids.leadId())).status,
                    "already-approved lead row is also declined -- the whole group dies together");
            assertEquals(
                    BookingStatus.DECLINED,
                    ((Booking) Booking.findById(ids.cohostId())).status,
                    "expired PENDING row is declined");
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee gets exactly one declined email");
            assertEquals(
                    1, EmailOutbox.count("recipient", "Creator@x.com"), "lead host gets exactly one declined email");
            assertEquals(1, EmailOutbox.count("recipient", "Cohost@x.com"), "co-host gets exactly one declined email");
        });
    }
}
