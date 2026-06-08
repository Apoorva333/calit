package com.calit.web;

import com.calit.availability.TimeSlot;
import com.calit.booking.BookingService;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
public class PublicResource {

    @CheckedTemplate
    public static class Templates {
        public static native TemplateInstance landing(List<MeetingType> types, String css);

        public static native TemplateInstance book(
                MeetingType type,
                Map<String, java.util.List<PublicResource.SlotView>> slotsByDate,
                java.util.List<com.calit.domain.BookingField> fields,
                String error,
                String css,
                String tzBar,
                String tzScript,
                boolean turnstileEnabled,
                String turnstileSiteKey);
    }

    @Inject
    BookingService bookingService;

    // Owner-configurable Turnstile (feature 16). When disabled, the template skips the widget.
    @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false")
    boolean turnstileEnabled;
    // SmallRye converts the empty-string property value to null, so bind it as Optional
    // and unwrap to "" — a non-Optional String injection would fail config validation.
    @ConfigProperty(name = "calit.turnstile.site-key")
    java.util.Optional<String> turnstileSiteKeyConfig;

    private static final int BOOK_WINDOW_DAYS = 14;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** One selectable slot: human label + the UTC instant string used as the form value. */
    public record SlotView(String label, String startUtc) {}

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance landing() {
        // listPublic() = active && !secret — secret types never reach this page.
        return Templates.landing(MeetingType.listPublic(), Layout.CSS);
    }

    @GET
    @Path("/book/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance book(@PathParam("slug") String slug) {
        MeetingType type = MeetingType.findBySlug(slug); // secret types reachable by direct link
        if (type == null) {
            throw new NotFoundException("No meeting type with slug " + slug);
        }
        Map<String, java.util.List<SlotView>> byDate = slotsByDate(type);
        // Resolved EXTRA fields (per-type-else-global), already ordered by position.
        List<BookingField> fields = BookingField.formFor(type.id);
        // turnstileEnabled drives the widget; site key is public (rendered). The approval
        // flag (type.requiresApproval) + locationType/locationDetail are read off `type`
        // directly in the template for the button wording + location line.
        return Templates.book(type, byDate, fields, null, Layout.CSS,
                              Layout.TZ_BAR, Layout.TZ_SCRIPT,
                              turnstileEnabled, turnstileSiteKey());
    }

    private String turnstileSiteKey() {
        return turnstileSiteKeyConfig.orElse("");
    }

    /** Group available slots by owner-tz date label, preserving chronological order. */
    private Map<String, java.util.List<SlotView>> slotsByDate(MeetingType type) {
        ZoneId zone = ZoneId.of(OwnerSettings.get().timezone);
        LocalDate from = LocalDate.now(zone);
        LocalDate to = from.plusDays(BOOK_WINDOW_DAYS);
        Map<String, java.util.List<SlotView>> byDate = new LinkedHashMap<>();
        for (TimeSlot slot : bookingService.availableSlots(type, from, to)) {
            String dateLabel = slot.start().format(DATE_FMT);
            byDate.computeIfAbsent(dateLabel, k -> new java.util.ArrayList<>())
                  .add(new SlotView(slot.start().format(TIME_FMT),
                                    slot.start().toInstant().toString()));
        }
        return byDate;
    }
}
