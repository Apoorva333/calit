package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Username-alias routing for multi-host meeting types: `/{cohost}/{slug}` resolves to the SAME
 * type with the CREATOR driving owner/slot/booking context, and shows a disabled page while a
 * co-host invite is still PENDING. Mirrors BookPageTest's seed style.
 */
@QuarkusTest
class AliasRoutingTest {

    @InjectMock
    CalendarPort calendarPort;

    /** Pasha (creator) + Volodya (co-host), both onboarded, both free 09-17 every weekday. */
    @Transactional
    MeetingType seedHosts() {
        AppUser pasha = MultiHostFixtures.enabledUser("pasha");
        AppUser volodya = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(pasha.id, "Pasha Owner");
        MultiHostFixtures.settings(volodya.id, "Volodya Owner");
        for (DayOfWeek dow : DayOfWeek.values()) {
            MultiHostFixtures.rule(pasha.id, dow, 9, 17);
            MultiHostFixtures.rule(volodya.id, dow, 9, 17);
        }
        return MultiHostFixtures.meetingType(pasha.id, "intro", 30);
    }

    /** Volodya is still PENDING — the type is not yet fully bookable. */
    @Transactional
    void seedPending() {
        MeetingType t = seedHosts();
        AppUser pasha = AppUser.findByUsername("pasha");
        AppUser volodya = AppUser.findByUsername("volodya");
        MeetingTypeHost.of(t.id, pasha.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(t.id, volodya.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING)
                .persist();
    }

    /** Volodya has accepted — the type is fully bookable under both aliases. */
    @Transactional
    void seedAccepted() {
        MeetingType t = seedHosts();
        AppUser pasha = AppUser.findByUsername("pasha");
        AppUser volodya = AppUser.findByUsername("volodya");
        MeetingTypeHost.of(t.id, pasha.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(t.id, volodya.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
    }

    @Test
    void cohostAliasResolvesToCreatorTypeOnceAccepted() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedAccepted();

        given().when()
                .get("/volodya/intro")
                .then()
                .statusCode(200)
                .body(containsString("name=\"startUtc\"")) // booking form rendered, not disabled
                .body(not(containsString("CALIT_HOST_PENDING")));
    }

    @Test
    void creatorAliasShowsPendingDisabledMarkerBeforeCohostAccepts() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given().when()
                .get("/pasha/intro")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_HOST_PENDING"))
                .body(not(containsString("name=\"startUtc\"")));
    }

    @Test
    void cohostAliasDoesNotResolveWhileStillPending() {
        // A PENDING co-host isn't recognized as a host of the type at all yet — resolveForAlias
        // only matches ACCEPTED hosts, so the alias route doesn't exist until they accept (404, not
        // the disabled page — that's reserved for URLs that DO resolve to a not-yet-bookable type).
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given().when().get("/volodya/intro").then().statusCode(404);
    }

    @Test
    void cohostLandingListsSharedTypeLinkingToCreatorCanonicalUrlOnceAccepted() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedAccepted();

        given().when().get("/volodya").then().statusCode(200).body(containsString("href=\"/pasha/intro\""));
    }

    @Test
    void cohostLandingOmitsSharedTypeWhileStillPending() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given().when()
                .get("/volodya")
                .then()
                .statusCode(200)
                .body(not(containsString("href=\"/pasha/intro\"")))
                .body(not(containsString("href=\"/volodya/intro\"")));
    }
}
