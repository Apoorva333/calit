package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
}
