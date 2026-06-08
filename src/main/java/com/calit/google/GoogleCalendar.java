package com.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

@Entity
@Table(name = "google_calendar")
public class GoogleCalendar extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The Google-side calendar id (often an email address or an opaque id). */
    @Column(name = "google_calendar_id", nullable = false, unique = true)
    public String googleCalendarId;

    @Column(nullable = false)
    public String summary;

    /** Include this calendar's busy blocks when computing free/busy. */
    @Column(name = "read_for_busy", nullable = false)
    public boolean readForBusy = false;

    /** Create new booking events on this calendar. At most one row may have this true. */
    @Column(name = "write_target", nullable = false)
    public boolean writeTarget = false;

    /** All calendars whose busy time should be subtracted from availability. */
    public static List<GoogleCalendar> readForBusy() {
        return list("readForBusy = true");
    }

    /** The single calendar new events are written to, or null if none is selected yet. */
    public static GoogleCalendar writeTarget() {
        return find("writeTarget = true").firstResult();
    }

    /** Upsert by Google calendar id; returns the managed row. */
    public static GoogleCalendar findByGoogleId(String googleCalendarId) {
        return find("googleCalendarId", googleCalendarId).firstResult();
    }
}
