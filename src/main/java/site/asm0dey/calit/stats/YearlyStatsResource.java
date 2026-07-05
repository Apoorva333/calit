package site.asm0dey.calit.stats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.user.AppUser;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner-scoped year-end statistics. Aggregates totalHours, busiestDay, busiestSlot
 * and longestBackToBackMinutes from the requested user's CONFIRMED bookings in the
 * given UTC year. Responses are cached in Postgres for 60s; the cache UPSERT
 * increments {@code recompute_count} on every actual recompute so operators can
 * detect cache-stampede behavior.
 */
@Path("/api/me/yearly-stats")
@RolesAllowed("user")
public class YearlyStatsResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "calit.yearly-stats.cache-ttl-seconds", defaultValue = "60")
    int cacheTtlSeconds;

    private static final String SELECT_CACHE_SQL =
            "SELECT payload FROM yearly_stats_cache WHERE owner_id = ? AND year = ? AND computed_at > ?";
    private static final String UPSERT_CACHE_SQL =
            "INSERT INTO yearly_stats_cache (owner_id, year, payload, computed_at, recompute_count) " +
            "VALUES (?, ?, ?::jsonb, ?, 1) " +
            "ON CONFLICT (owner_id, year) DO UPDATE " +
            "  SET payload = EXCLUDED.payload, " +
            "      computed_at = EXCLUDED.computed_at, " +
            "      recompute_count = yearly_stats_cache.recompute_count + 1";

    @GET
    @Path("/{year}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("year") int year) {
        Long ownerId = AppUser.findByUsername(identity.getPrincipal().getName()).id;
        Instant now = Instant.now();
        Instant freshAfter = now.minus(Duration.ofSeconds(cacheTtlSeconds));

        String cached = readCache(ownerId, year, freshAfter);
        if (cached != null) {
            return Response.ok(cached, MediaType.APPLICATION_JSON).build();
        }

        String payload = computePayloadJson(ownerId, year);
        writeCache(ownerId, year, payload, now);
        return Response.ok(payload, MediaType.APPLICATION_JSON).build();
    }

    private String computePayloadJson(Long ownerId, int year) {
        Instant yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Booking> bookings = Booking.list(
                "ownerId = ?1 and status = ?2 and startUtc >= ?3 and startUtc < ?4 order by startUtc",
                ownerId, BookingStatus.CONFIRMED, yearStart, yearEnd);

        if (bookings.isEmpty()) {
            return "{}";
        }

        long totalMinutes = 0L;
        Map<LocalDate, Long> minutesByDay = new HashMap<>();
        Map<SlotKey, Long> countBySlot = new HashMap<>();

        for (Booking b : bookings) {
            LocalDateTime startUtc = LocalDateTime.ofInstant(b.startUtc, ZoneOffset.UTC);
            LocalDate startDay = startUtc.toLocalDate();
            long durationMin = Duration.between(b.startUtc, b.endUtc).toMinutes();

            totalMinutes += durationMin;
            minutesByDay.merge(startDay, durationMin, Long::sum);

            SlotKey key = new SlotKey(sundayFirstOrdinal(startUtc.getDayOfWeek()), startUtc.getHour());
            countBySlot.merge(key, 1L, Long::sum);
        }

        YearlyStats out = new YearlyStats();
        out.year = year;
        out.totalHours = totalMinutes / 60.0;

        Map.Entry<LocalDate, Long> busiestDay = minutesByDay.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<LocalDate, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElseThrow();
        YearlyStats.BusiestDay bd = new YearlyStats.BusiestDay();
        bd.date = busiestDay.getKey().toString();
        bd.hoursBooked = busiestDay.getValue() / 60.0;
        out.busiestDay = bd;

        Map.Entry<SlotKey, Long> busiestSlot = countBySlot.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<SlotKey, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(e -> -e.getKey().sundayOrdinal)
                        .thenComparing(e -> -e.getKey().hour))
                .orElseThrow();
        YearlyStats.BusiestSlot bs = new YearlyStats.BusiestSlot();
        bs.weekday = fromSundayFirstOrdinal(busiestSlot.getKey().sundayOrdinal).name();
        bs.hour = busiestSlot.getKey().hour;
        bs.bookings = busiestSlot.getValue();
        out.busiestSlot = bs;

        out.longestBackToBackMinutes = longestBackToBackMinutes(bookings);

        try {
            return objectMapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("yearly-stats serialization failed", e);
        }
    }

    private String readCache(Long ownerId, int year, Instant freshAfter) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_CACHE_SQL)) {
            ps.setLong(1, ownerId);
            ps.setInt(2, year);
            ps.setTimestamp(3, Timestamp.from(freshAfter));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("yearly-stats cache read failed", e);
        }
    }

    private void writeCache(Long ownerId, int year, String payload, Instant now) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPSERT_CACHE_SQL)) {
            ps.setLong(1, ownerId);
            ps.setInt(2, year);
            ps.setString(3, payload);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("yearly-stats cache write failed", e);
        }
    }

    private static long longestBackToBackMinutes(List<Booking> bookings) {
        long longest = 0L;
        Instant chainStart = bookings.get(0).startUtc;
        Instant chainEnd = bookings.get(0).endUtc;
        longest = Math.max(longest, Duration.between(chainStart, chainEnd).toMinutes());
        for (int i = 1; i < bookings.size(); i++) {
            Booking next = bookings.get(i);
            if (chainEnd.compareTo(next.startUtc) >= 0) {
                if (next.endUtc.isAfter(chainEnd)) {
                    chainEnd = next.endUtc;
                }
            } else {
                chainStart = next.startUtc;
                chainEnd = next.endUtc;
            }
            longest = Math.max(longest, Duration.between(chainStart, chainEnd).toMinutes());
        }
        return longest;
    }

    private static int sundayFirstOrdinal(DayOfWeek d) {
        return d == DayOfWeek.SUNDAY ? 1 : d.getValue() + 1;
    }

    private static DayOfWeek fromSundayFirstOrdinal(int n) {
        return n == 1 ? DayOfWeek.SUNDAY : DayOfWeek.of(n - 1);
    }

    private static final class SlotKey {
        final int sundayOrdinal;
        final int hour;

        SlotKey(int sundayOrdinal, int hour) {
            this.sundayOrdinal = sundayOrdinal;
            this.hour = hour;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SlotKey other)) return false;
            return sundayOrdinal == other.sundayOrdinal && hour == other.hour;
        }

        @Override
        public int hashCode() {
            return sundayOrdinal * 31 + hour;
        }
    }
}
