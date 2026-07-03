package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

/**
 * Host-set logic for multi-host meeting types: membership, bookability gate, organizer choice,
 * per-host buffers, co-host eligibility. Single-host types short-circuit to the creator alone.
 */
@ApplicationScoped
public class MeetingHosts {

    public static final int MAX_HOSTS = 10;

    private final CalendarPort calendarPort;

    @Inject
    Event<HostConsentRequested> consentEvent;

    @Inject
    public MeetingHosts(CalendarPort calendarPort) {
        this.calendarPort = calendarPort;
    }

    /** Owner ids that must all be free: [creator] for single-host; creator + accepted co-hosts otherwise. */
    public List<Long> hostOwnerIds(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return List.of(type.ownerId);
        }
        List<Long> ids = new ArrayList<>();
        for (MeetingTypeHost h : MeetingTypeHost.acceptedForType(type.id)) {
            ids.add(h.ownerId);
        }
        return ids;
    }

    /** Single-host is always bookable. Multi-host requires every host ACCEPTED and every host enabled. */
    public boolean bookable(MeetingType type) {
        if (!MeetingTypeHost.isMultiHost(type.id)) {
            return true;
        }
        List<MeetingTypeHost> hosts = MeetingTypeHost.forType(type.id);
        for (MeetingTypeHost h : hosts) {
            if (!h.accepted()) {
                return false;
            }
            AppUser u = AppUser.findById(h.ownerId);
            if (u == null || !u.enabled) {
                return false;
            }
        }
        return true;
    }

    /** Creator if connected, else the lowest-id connected host, else null (no Google event). */
    public Long chooseOrganizer(MeetingType type, List<Long> hostOwnerIds) {
        if (calendarPort.isConnected(type.ownerId)) {
            return type.ownerId;
        }
        Long chosen = null;
        for (Long id : hostOwnerIds) {
            if (calendarPort.isConnected(id) && (chosen == null || id < chosen)) {
                chosen = id;
            }
        }
        return chosen;
    }

    public int effectiveBufferBefore(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferBeforeMinutes != null) ? h.bufferBeforeMinutes : type.bufferBeforeMinutes;
    }

    public int effectiveBufferAfter(MeetingType type, Long hostOwnerId) {
        MeetingTypeHost h = MeetingTypeHost.find(type.id, hostOwnerId);
        return (h != null && h.bufferAfterMinutes != null) ? h.bufferAfterMinutes : type.bufferAfterMinutes;
    }

    public boolean eligibleCohost(Long meetingTypeId, Long creatorOwnerId, AppUser candidate) {
        if (candidate == null || !candidate.enabled || !candidate.settingsComplete) {
            return false;
        }
        if (candidate.id.equals(creatorOwnerId)) {
            return false;
        }
        return MeetingTypeHost.find(meetingTypeId, candidate.id) == null;
    }

    /**
     * Ensures the CREATOR row exists, then inserts a PENDING co-host row and fires a consent
     * request. Idempotent: a candidate already present (any status) is a no-op — no duplicate
     * row, no repeat email.
     */
    @Transactional
    public void addCohost(MeetingType type, AppUser candidate) {
        MeetingTypeHost existing = MeetingTypeHost.find(type.id, candidate.id);
        if (existing != null) {
            return; // idempotent: already a host (pending or accepted) -> no-op, no repeat email
        }
        ensureCreatorRow(type);
        long hostCount = MeetingTypeHost.count("meetingTypeId", type.id);
        if (hostCount >= MAX_HOSTS) {
            throw new IllegalStateException("A meeting can have at most " + MAX_HOSTS + " hosts.");
        }
        // ponytail: constant cap, revisit only if a real use-case needs more
        assertSlugFreeForCohost(type, candidate);
        MeetingTypeHost h = MeetingTypeHost.of(type.id, candidate.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        h.consentToken = UUID.randomUUID();
        h.persist();
        consentEvent.fire(new HostConsentRequested(type.id, candidate.id, h.consentToken.toString()));
    }

    /**
     * A candidate must not already own, or co-host, another type with the same slug as {@code
     * type} — their public URL would collide once they accept.
     */
    public void assertSlugFreeForCohost(MeetingType type, AppUser candidate) {
        if (MeetingType.slugUsedByOwner(candidate.id, type.slug, null)) {
            throw new IllegalStateException(candidate.username + " already uses the slug " + type.slug
                    + " -- pick a different slug or ask them to free it.");
        }
        // also reject if candidate is a host of another multi-host type with this slug
        for (MeetingTypeHost h : MeetingTypeHost.cohostedTypesFor(candidate.id)) {
            MeetingType other = MeetingType.findById(h.meetingTypeId);
            if (other != null && !other.id.equals(type.id) && other.slug.equals(type.slug)) {
                throw new IllegalStateException(candidate.username + " already co-hosts a type with slug " + type.slug);
            }
        }
    }

    /** For create/rename of a shared type: slug must be free in every host's namespace. */
    public void assertSlugFreeAcrossHosts(MeetingType type, String newSlug) {
        for (Long hostId : hostOwnerIds(type)) {
            if (!hostId.equals(type.ownerId) && MeetingType.slugUsedByOwner(hostId, newSlug, null)) {
                throw new IllegalStateException("A host already uses the slug " + newSlug);
            }
        }
    }

    private void ensureCreatorRow(MeetingType type) {
        if (MeetingTypeHost.find(type.id, type.ownerId) == null) {
            MeetingTypeHost.of(type.id, type.ownerId, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                    .persist();
        }
    }

    /** Marks a pending co-host row accepted and clears its one-time consent token. */
    @Transactional
    public void acceptConsent(MeetingTypeHost host) {
        host.status = MeetingTypeHost.ACCEPTED;
        host.respondedAt = Instant.now();
        host.consentToken = null;
        host.persist();
    }

    /**
     * Removes a co-host; if no co-hosts remain, also removes the CREATOR row (revert to
     * single-host). Defense-in-depth guard against removing the CREATOR row directly (Task 17
     * review): {@link site.asm0dey.calit.web.AdminResource#removeCohost} already rejects this
     * before calling here (with a localized alert), so this exception is only reachable via a
     * caller that skips that check.
     */
    @Transactional
    public void removeHost(MeetingType type, Long cohostOwnerId) {
        if (cohostOwnerId.equals(type.ownerId)) {
            throw new IllegalStateException("The creator cannot be removed from their own meeting type.");
        }
        MeetingTypeHost.delete("meetingTypeId = ?1 and ownerId = ?2", type.id, cohostOwnerId);
        if (MeetingTypeHost.count("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.COHOST) == 0) {
            MeetingTypeHost.delete("meetingTypeId = ?1 and role = ?2", type.id, MeetingTypeHost.CREATOR);
        }
    }
}
