package site.asm0dey.calit.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hand-rolled RFC 5545 VCALENDAR/VEVENT builder for a single booking.
 * Emitted as an .ics attachment on every app-sent email so the recipient
 * can add the meeting to any calendar (independent of Google).
 *
 * <p>METHOD:REQUEST means this is an iTIP invitation. Such a request MUST carry at least one
 * ATTENDEE — Gmail shows "Unable to load event" for a REQUEST with none. The owner is the
 * ORGANIZER and the invitee is the ATTENDEE; both carry a CN so each side's mail client
 * matches the recipient and renders the event card.
 */
public final class IcsBuilder {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {
    }

    /** A named calendar participant: display name (CN) + email (mailto). */
    public record Party(String name, String email) {
    }

    /**
     * @param uid       stable unique id (we pass the booking's manageToken so updates match)
     * @param summary   event title (the meeting type name)
     * @param location  Meet link or locationDetail; null/blank emits no LOCATION line
     * @param organizer the owner — emitted as ORGANIZER with a CN
     * @param attendee  the invitee — emitted as ATTENDEE with a CN (required for a valid REQUEST)
     * @param start     meeting start (UTC instant)
     * @param end       meeting end (UTC instant)
     */
    /** Invitee/owner invitation — RSVP requested (client shows Yes/No), METHOD:REQUEST, SEQUENCE 0. */
    public static String build(String uid, String summary, String location,
                               Party organizer, Party attendee,
                               Instant start, Instant end) {
        return build(uid, summary, location, organizer, attendee, start, end, "REQUEST", 0, true);
    }

    /**
     * @param uid          stable unique id (we pass the booking's manageToken so updates match)
     * @param summary      event title (the meeting type name)
     * @param location     Meet link or locationDetail; null/blank emits no LOCATION line
     * @param organizer    the owner — emitted as ORGANIZER with a CN
     * @param attendee     the invitee — emitted as ATTENDEE with a CN (required for a valid REQUEST)
     * @param start        meeting start (UTC instant)
     * @param end          meeting end (UTC instant)
     * @param method       "REQUEST" (invite/update) or "CANCEL" (removal)
     * @param sequence     iTIP SEQUENCE — monotonic per UID so updates/cancels supersede
     * @param attendeeRsvp true emits RSVP=TRUE (calendar shows Yes/No); false emits RSVP=FALSE
     *                     (guests respond only via calit's decline link, not a calendar reply)
     */
    public static String build(String uid, String summary, String location,
                               Party organizer, Party attendee,
                               Instant start, Instant end, String method, int sequence, boolean attendeeRsvp) {
        boolean cancel = "CANCEL".equals(method);
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:").append(escape(method)).append("\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(escape(uid)).append("\r\n");
        sb.append("SEQUENCE:").append(sequence).append("\r\n");
        sb.append("STATUS:").append(cancel ? "CANCELLED" : "CONFIRMED").append("\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(start)).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(end)).append("\r\n");
        sb.append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (location != null && !location.isBlank()) {
            sb.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        sb.append("ORGANIZER;CN=").append(cn(organizer.name()))
                .append(":mailto:").append(escape(organizer.email())).append("\r\n");
        sb.append("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=")
                .append(attendeeRsvp ? "TRUE" : "FALSE")
                .append(";CN=").append(cn(attendee.name()))
                .append(":mailto:").append(escape(attendee.email())).append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    /** RFC 5545 text escaping for any interpolated property value; strips CR so no value injects a line. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /**
     * Quoted CN param value (RFC 5545 §3.2). Double-quoting lets a name contain ';' or ','
     * without escaping; we only need to strip CR/LF and replace any inner double-quote.
     */
    private static String cn(String v) {
        String safe = (v == null ? "" : v).replace("\r", "").replace("\n", " ").replace("\"", "'");
        return "\"" + safe + "\"";
    }
}
