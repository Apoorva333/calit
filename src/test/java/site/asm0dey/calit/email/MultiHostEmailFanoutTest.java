package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingRequested;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * A group booking (booking.groupId != null) must fan out lifecycle mail: the invitee gets exactly
 * one copy (keyed on the lead row), and EACH host gets their own personalized owner-role copy
 * (their own locale/name via {@link OwnerSettings}, their own row's approve/manage tokens).
 * {@link HostConsentRequested} sends the co-host a one-click /consent/{token} link.
 */
@QuarkusTest
class MultiHostEmailFanoutTest {

    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final long CREATOR_ID = 1L; // DatabaseResetCallback baseline admin
    private static final long COHOST_ID = 2L;

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    private MeetingType twoHostType(boolean requiresApproval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(CREATOR_ID, "Creator");
            MultiHostFixtures.settings(COHOST_ID, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(CREATOR_ID, COHOST_ID, "pair-up", 30, requiresApproval);
        });
    }

    /** One row per host, sharing a groupId; returns the lead (creator's) row id. */
    private long seedGroup(MeetingType type, BookingStatus status, boolean withApprovalTokens) {
        var groupId = UUID.randomUUID();
        var start = Instant.parse("2026-06-08T09:00:00Z");
        return QuarkusTransaction.requiringNew().call(() -> {
            long leadId = -1;
            for (long hostId : List.of(CREATOR_ID, COHOST_ID)) {
                Booking b = new Booking();
                b.ownerId = hostId;
                b.meetingTypeId = type.id;
                b.inviteeName = "Sam Invitee";
                b.inviteeEmail = INVITEE_EMAIL;
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = status;
                b.groupId = groupId;
                b.manageToken = "tok-" + hostId + "-" + System.nanoTime();
                if (withApprovalTokens) {
                    b.approvalToken = "appr-" + hostId + "-" + System.nanoTime();
                }
                b.createdAt = Instant.now();
                b.persist();
                if (hostId == CREATOR_ID) {
                    leadId = b.id;
                }
            }
            return leadId;
        });
    }

    @Test
    void autoConfirmedGroupBookingSendsOneInviteeMailAndOnePerHost() {
        MeetingType type = twoHostType(false);
        long leadId = seedGroup(type, BookingStatus.CONFIRMED, false);

        emailService.handleConfirmed(new BookingConfirmed(leadId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "invitee gets exactly one copy");
        assertEquals(1, mailbox.getMailsSentTo("Creator@x.com").size(), "creator host gets one copy");
        assertEquals(1, mailbox.getMailsSentTo("Cohost@x.com").size(), "co-host gets one copy");
        assertEquals(3, mailbox.getTotalMessagesSent(), "no duplicate invitee mail, no missed host");
    }

    @Test
    void approvalGroupBookingAlwaysEmailsEveryHostEvenWhenOptedOut() {
        MeetingType type = twoHostType(true);
        // Co-host opted out of notifications -- the approval-needed mail must still reach them,
        // otherwise nobody can ever approve their row and the group deadlocks.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(COHOST_ID);
            s.ownerNotificationsEnabled = false;
            s.persist();
        });
        long leadId = seedGroup(type, BookingStatus.PENDING, true);

        emailService.handleRequested(new BookingRequested(leadId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "invitee gets exactly one copy");
        assertEquals(1, mailbox.getMailsSentTo("Creator@x.com").size(), "creator host gets the approval mail");
        assertEquals(
                1,
                mailbox.getMailsSentTo("Cohost@x.com").size(),
                "opted-out co-host still gets the approval-needed mail (actionable, not suppressible)");
        assertEquals(3, mailbox.getTotalMessagesSent());

        Mail cohostMail = mailbox.getMailsSentTo("Cohost@x.com").getFirst();
        assertTrue(cohostMail.getHtml().contains("/me/bookings/"), "carries this host's OWN approve/manage link");
    }

    @Test
    void hostConsentRequestedEmailsTheCohostWithAConsentLink() {
        MeetingType type = twoHostType(false);

        emailService.handleHostConsent(new HostConsentRequested(type.id, COHOST_ID, "tok-123"));

        List<Mail> toCohost = mailbox.getMailsSentTo("Cohost@x.com");
        assertEquals(1, toCohost.size(), "exactly one consent mail to the co-host");
        assertTrue(toCohost.getFirst().getHtml().contains("/consent/tok-123"), "carries the one-click consent link");
    }
}
