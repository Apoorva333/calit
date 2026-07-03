package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 16: Main (single-host) vs Shared (multi-host) meeting-type navigation. Admin (owner id 1)
 * is the fixed test invariant; scenarios cover the owner both as CREATOR and as COHOST of a
 * multi-host type.
 */
@QuarkusTest
class SharedPageTest {

    /** Admin (id 1) creates a two-host type with a fresh co-host — admin's role is CREATOR. */
    @Transactional
    MeetingType seedAdminCreatedSharedType() {
        AppUser cohost = MultiHostFixtures.enabledUser("shared-cohost");
        return MultiHostFixtures.acceptedTwoHostType(1L, cohost.id, "shared-created", 30, false);
    }

    /** A different owner creates a two-host type with admin (id 1) as co-host — admin's role is COHOST. */
    @Transactional
    MeetingType seedAdminAsCohost() {
        AppUser creator = MultiHostFixtures.enabledUser("shared-creator");
        return MultiHostFixtures.acceptedTwoHostType(creator.id, 1L, "shared-cohosted", 30, false);
    }

    @Test
    void mainListOmitsMultiHostTypeButShowsSharedLink() {
        MeetingType t = seedAdminCreatedSharedType();

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(not(containsString(t.slug)))
                .body(containsString("href=\"/me/shared\""));
    }

    @Test
    void mainListHidesSharedLinkWhenOwnerHasNoMultiHostType() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(not(containsString("href=\"/me/shared\"")));
    }

    @Test
    void sharedPageListsCreatedTypeWithCreatorBadge() {
        MeetingType t = seedAdminCreatedSharedType();

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared")
                .then()
                .statusCode(200)
                .body(containsString(t.name))
                .body(containsString("Creator"));
    }

    @Test
    void sharedPageListsCohostedTypeWithCohostBadge() {
        MeetingType t = seedAdminAsCohost();

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared")
                .then()
                .statusCode(200)
                .body(containsString(t.name))
                .body(containsString("Co-host"));
    }
}
