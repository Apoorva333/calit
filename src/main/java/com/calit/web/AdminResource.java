package com.calit.web;

import com.calit.booking.Booking;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/admin")
@RolesAllowed("admin")
public class AdminResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance dashboard(List<Booking> upcoming, long pendingCount, String css);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        // Upcoming confirmed bookings, soonest first. PENDING ones live in the approval queue
        // (Task 12, GET /admin/pending), not here.
        List<Booking> upcoming = Booking.list(
                "status = ?1 and startUtc >= ?2 order by startUtc",
                com.calit.booking.BookingStatus.CONFIRMED, java.time.Instant.now());
        long pendingCount = Booking.count("status = ?1", com.calit.booking.BookingStatus.PENDING);
        return Templates.dashboard(upcoming, pendingCount, Layout.CSS);
    }
}
