package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailService;

/**
 * Feature 14: auto-expire approval-mode (PENDING) bookings whose hold window has
 * elapsed. Runs on EVERY replica every 60s. Multi-node-safe with NO leader: each
 * tick claims expired PENDING rows with SELECT ... FOR UPDATE SKIP LOCKED, so two
 * replicas never decline the same booking. Flipping PENDING -> DECLINED drops the
 * row from the booking_no_overlap_held guard (partial on PENDING/CONFIRMED),
 * which immediately frees the held slot for a new booking.
 */
@ApplicationScoped
public class PendingExpiryScheduler {

    @ConfigProperty(name = "calit.approval.hold-hours", defaultValue = "24")
    int holdHours;

    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;

    @Inject
    EntityManager em;

    @Inject
    EmailService emailService;

    /**
     * Feature 14 expiry tick. Runs on EVERY replica every 60s, leaderless (FOR UPDATE SKIP LOCKED).
     * Per expired booking, in the SAME transaction as the claim: flip PENDING -> DECLINED, drop its
     * unsent reminder, and enqueue the declined email to the outbox (crash-safe). OutboxScheduler
     * delivers it. A node dying mid-tick loses nothing: all three commit together or not at all.
     */
    @Scheduled(every = "60s")
    void expirePendingBookings() {
        claimAndDeclineExpired();
    }

    /**
     * Claims expired PENDING bookings FOR UPDATE SKIP LOCKED and, per booking in the SAME tx:
     * flips to DECLINED, deletes any unsent reminder, enqueues the declined email. Expiry =
     * min(createdAt + holdHours, startUtc) <= now() + grace. A render failure for one poison
     * booking is caught/logged so it can't roll back the whole batch.
     */
    void claimAndDeclineExpired() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery("SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            for (Number n : ids) {
                Long id = n.longValue();
                Booking b = Booking.findById(id);
                // A sibling row of the same group may already have been declined earlier in THIS
                // batch (two rows of one group both expired -> both claimed in the same tick): skip
                // the no-op re-decline rather than re-enqueue a second declined email for the group.
                if (b.status != BookingStatus.PENDING) {
                    continue;
                }
                // Multi-host: the hold window elapsing on ANY row without full approval means the
                // whole conceptual meeting failed to convene -- decline every row in the group, not
                // just the one that happened to be claimed, and send a single declined email (fanned
                // out per host by EmailService) keyed on the group's lead row.
                if (b.groupId != null) {
                    Long creatorOwnerId = MeetingType.<MeetingType>findById(b.meetingTypeId).ownerId;
                    for (Booking r : Booking.<Booking>group(b.groupId)) {
                        r.status = BookingStatus.DECLINED; // flipped within the lock-holding transaction
                        Reminder.deleteUnsentFor(r.id); // was ReminderScheduler.onDeclined observer
                    }
                    Booking lead = Booking.leadOfGroup(b.groupId, creatorOwnerId);
                    // Guard covers a render/load failure (throws before any persist): the flip +
                    // cleanup still commit, one mail dropped. A crash (not caught) rolls back the
                    // whole tx pre-commit and the row is reclaimed next tick -- the crash-safety
                    // guarantee.
                    try {
                        emailService.enqueueDeclined(lead.id); // durable, same tx
                    } catch (Exception ex) {
                        Log.errorf(
                                ex,
                                "declined enqueue failed for group %s lead booking %d (declined, mail dropped)",
                                b.groupId,
                                lead.id);
                    }
                } else {
                    b.status = BookingStatus.DECLINED; // flipped within the lock-holding transaction
                    Reminder.deleteUnsentFor(id); // was ReminderScheduler.onDeclined observer
                    // Guard covers a render/load failure (throws before any persist): the flip + cleanup
                    // still commit, one mail dropped. A crash (not caught) rolls back the whole tx pre-commit
                    // and the row is reclaimed next tick -- the crash-safety guarantee.
                    try {
                        emailService.enqueueDeclined(id); // was EmailService.onDeclined observer; durable, same tx
                    } catch (Exception ex) {
                        Log.errorf(ex, "declined enqueue failed for booking %d (declined, mail dropped)", id);
                    }
                }
            }
        });
    }
}
