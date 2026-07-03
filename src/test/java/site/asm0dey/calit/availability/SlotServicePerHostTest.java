package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;
import static site.asm0dey.calit.test.MultiHostFixtures.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlotServicePerHostTest {

    @Inject
    SlotService slotService;

    @Test
    @TestTransaction
    void rawSlotsUseHostWindowsNotCreatorWindows() {
        // creator = admin id 1 (no availability), cohost has Monday 09:00-10:00
        AppUser cohost = enabledUser("volodya");
        settings(cohost.id, "V");

        MeetingType t = meetingType(1L, "intro", 30);
        t.horizonDays = 50000;
        t.persist();

        var monday = LocalDate.now(ZoneId.of("Europe/Amsterdam"))
                .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        rule(cohost.id, DayOfWeek.MONDAY, 9, 10);

        List<TimeSlot> slots = slotService.generateRawSlots(t, cohost.id, monday, monday);
        assertEquals(2, slots.size()); // 09:00 and 09:30 (30-min grid within 09:00-10:00)
    }
}
