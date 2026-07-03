package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 20: the co-host typeahead JSON endpoint. Admin (owner id 1) is the fixed test invariant —
 * every scenario starts from a plain single-host type admin owns.
 */
@QuarkusTest
class HostSuggestTest {

    @Transactional
    MeetingType seedAdminType(String slug) {
        return MultiHostFixtures.meetingType(1L, slug, 30);
    }

    @Transactional
    AppUser seedCandidate(String username) {
        return MultiHostFixtures.enabledUser(username);
    }

    @Transactional
    AppUser seedDisabledCandidate(String username) {
        AppUser u = AppUser.create(username, "x", false);
        u.settingsComplete = true;
        u.enabled = false;
        u.persist();
        return u;
    }

    @Transactional
    AppUser seedIncompleteCandidate(String username) {
        AppUser u = AppUser.create(username, "x", false);
        u.settingsComplete = false;
        u.persist();
        return u;
    }

    @Transactional
    void seedAcceptedCohostRow(Long typeId, Long ownerId) {
        MeetingTypeHost.of(typeId, ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
    }

    @Test
    void suggestsEligibleCandidateAndExcludesIneligibleOnes() {
        var uniq = System.nanoTime();
        MeetingType t = seedAdminType("host-suggest-" + uniq);
        AppUser eligible = seedCandidate("volodya" + uniq);
        AppUser disabled = seedDisabledCandidate("voldisabled" + uniq);
        AppUser incomplete = seedIncompleteCandidate("volincomplete" + uniq);
        AppUser alreadyHost = seedCandidate("volhost" + uniq);
        seedAcceptedCohostRow(t.id, alreadyHost.id);

        String body = given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/hosts?q=vol&typeId=" + t.id)
                .then()
                .statusCode(200)
                .contentType("application/json")
                .extract()
                .asString();

        org.hamcrest.MatcherAssert.assertThat(body, containsString(eligible.username));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString(disabled.username)));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString(incomplete.username)));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString(alreadyHost.username)));
    }

    @Test
    void excludesSelf() {
        var uniq = System.nanoTime();
        MeetingType t = seedAdminType("host-suggest-self-" + uniq);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/hosts?q=admin&typeId=" + t.id)
                .then()
                .statusCode(200)
                .body(not(containsString("\"admin\"")));
    }

    @Test
    void unauthenticatedIsRejected() {
        // Form-auth redirects unauthenticated browser requests to /login (302/303) rather than
        // a bare 401 — same convention asserted for other /me* routes in ReservedRouteTest.
        given().redirects()
                .follow(false)
                .when()
                .get("/me/hosts?q=vol&typeId=1")
                .then()
                .statusCode(anyOf(is(302), is(303), is(401)));
    }

    @Test
    void blankQueryReturnsEmptyList() {
        var uniq = System.nanoTime();
        MeetingType t = seedAdminType("host-suggest-blank-" + uniq);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/hosts?q=&typeId=" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("[]"));
    }

    @Test
    void meetingTypeDetailPageContainsTypeaheadScriptMarker() {
        MeetingType t = seedAdminType("host-suggest-marker-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("CALIT_HOST_TYPEAHEAD"));
    }
}
