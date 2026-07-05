package site.asm0dey.calit.stats;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for {@code GET /api/me/yearly-stats/{year}}. With
 * {@link JsonInclude.Include#NON_NULL} every unset field disappears from the
 * serialized JSON, so a year with no CONFIRMED bookings serializes as the
 * literal {@code {}} body the spec requires.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YearlyStats {

    public Integer year;
    public Double totalHours;
    public BusiestSlot busiestSlot;
    public BusiestDay busiestDay;
    public Long longestBackToBackMinutes;

    public static class BusiestSlot {
        public String weekday;
        public Integer hour;
        public Long bookings;
    }

    public static class BusiestDay {
        public String date;
        public Double hoursBooked;
    }
}
