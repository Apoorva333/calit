package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * On first boot (empty {@code availability_rule} table) seeds Mon–Fri 09:00–18:00 GLOBAL work
 * hours so the booking calendar isn't empty out of the box. Excluded from the test build profile
 * (tests seed their own rules and assert exact slot counts). Idempotent: only seeds when no rule exists.
 */
@ApplicationScoped
@UnlessBuildProfile("test")
public class DefaultAvailabilitySeeder {

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        if (AvailabilityRule.count() == 0) {
            for (AvailabilityRule r : weekdayDefaults()) {
                r.persist();
            }
        }
    }

    /** Mon–Fri 09:00–18:00, global (meetingTypeId == null). */
    static List<AvailabilityRule> weekdayDefaults() {
        List<AvailabilityRule> rules = new ArrayList<>();
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                   DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(18, 0);
            r.meetingTypeId = null;
            rules.add(r);
        }
        return rules;
    }
}
