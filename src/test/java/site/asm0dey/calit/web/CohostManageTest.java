package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 17: co-host add/remove on the meeting-type detail form, plus the create/edit slug guards
 * carried forward from Task 6. Admin (owner id 1) is the fixed test invariant; every scenario
 * starts from a plain single-host type admin owns.
 */
@QuarkusTest
class CohostManageTest {

    @Transactional
    MeetingType seedAdminType(String slug) {
        return MultiHostFixtures.meetingType(1L, slug, 30);
    }

    @Transactional
    AppUser seedCandidate(String username) {
        return MultiHostFixtures.enabledUser(username);
    }

    @Transactional
    void seedOwnType(long ownerId, String slug) {
        MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    @Transactional
    void seedPendingCohost(Long typeId, Long ownerId) {
        MeetingTypeHost.of(typeId, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(typeId, ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING)
                .persist();
    }

    @Test
    void addCohostCreatesPendingRowAndListsOnDetailPage() {
        MeetingType t = seedAdminType("cohost-add-" + System.nanoTime());
        AppUser candidate = seedCandidate("candidate-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", candidate.username)
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString(candidate.username));

        MeetingTypeHost host = MeetingTypeHost.find(t.id, candidate.id);
        assertNotNull(host, "cohost row must be created");
        assertEquals(MeetingTypeHost.PENDING, host.status);
        assertEquals(MeetingTypeHost.COHOST, host.role);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString(candidate.username));
    }

    @Test
    void addCohostWithSlugCollisionShowsErrorAndCreatesNoRow() {
        var slug = "collide-" + System.nanoTime();
        AppUser candidate = seedCandidate("collider-" + System.nanoTime());
        seedOwnType(candidate.id, slug); // candidate already owns /candidate/<slug>
        MeetingType t = seedAdminType(slug); // admin's new shared type uses the same slug

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", candidate.username)
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        assertNull(MeetingTypeHost.find(t.id, candidate.id), "no cohost row on rejected add");
    }

    @Test
    void addCohostWithUnknownUsernameShowsErrorAndCreatesNoRow() {
        MeetingType t = seedAdminType("cohost-unknown-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", "no-such-user-" + System.nanoTime())
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        assertEquals(0, MeetingTypeHost.count("meetingTypeId = ?1 and role = ?2", t.id, MeetingTypeHost.COHOST));
    }

    @Test
    void removeDeletesHostRow() {
        MeetingType t = seedAdminType("cohost-remove-" + System.nanoTime());
        AppUser candidate = seedCandidate("removee-" + System.nanoTime());
        seedPendingCohost(t.id, candidate.id);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts/" + candidate.id + "/remove")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(t.id, candidate.id), "cohost row removed");
    }

    @Test
    void createRejectsSlugCollidingWithCohostedType() {
        AppUser creator = seedCandidate("other-creator-" + System.nanoTime());
        var slug = "shared-slug-" + System.nanoTime();
        MeetingType shared = seedOwnTypeReturning(creator.id, slug);
        seedAcceptedCohostRow(shared.id, 1L); // admin (id 1) co-hosts creator's type

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Own New Type")
                .formParam("slug", slug) // collides with the type admin already co-hosts
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "+1")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        assertNull(MeetingType.findBySlug(1L, slug), "no new own-type created on collision");
    }

    @Test
    void editRejectsRenameCollidingWithCohostedType() {
        AppUser creator = seedCandidate("other-creator2-" + System.nanoTime());
        var collidingSlug = "shared-slug2-" + System.nanoTime();
        MeetingType shared = seedOwnTypeReturning(creator.id, collidingSlug);
        seedAcceptedCohostRow(shared.id, 1L); // admin co-hosts creator's type

        MeetingType own = seedAdminType("own-type-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", own.name)
                .formParam("slug", collidingSlug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "+1")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + own.id + "/edit")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        MeetingType reloaded = MeetingType.findById(own.id);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                collidingSlug, reloaded.slug, "slug must not be renamed on collision");
    }

    @Transactional
    MeetingType seedOwnTypeReturning(long ownerId, String slug) {
        return MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    @Transactional
    void seedAcceptedCohostRow(Long typeId, Long ownerId) {
        MeetingTypeHost.of(typeId, ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
    }
}
