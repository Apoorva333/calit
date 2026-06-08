package com.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class MeetingTypeResourceTest {

    @Test
    void fullFlowCreatesSettingsTypeRuleAndPreviewsSlots() {
        // 1. Configure owner settings.
        given().contentType("application/json")
                .body("{\"ownerName\":\"Owner\",\"ownerEmail\":\"o@example.com\",\"timezone\":\"Europe/Amsterdam\"}")
                .when().put("/api/settings")
                .then().statusCode(200);

        // 2. Create a 60-minute meeting type (unique slug per run).
        String slug = "api-intro-" + System.nanoTime();
        Integer typeId = given().contentType("application/json")
                .body("{\"name\":\"API Intro\",\"slug\":\"" + slug + "\",\"durationMinutes\":60}")
                .when().post("/api/meeting-types")
                .then().statusCode(201)
                .extract().path("id");

        // 3. Add a per-type Monday 09:00-11:00 rule (scoped to this type to avoid contaminating
        //    global-rule counts in other tests that share the same Dev Services DB).
        given().contentType("application/json")
                .body("{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"meetingTypeId\":" + typeId + "}")
                .when().post("/api/availability")
                .then().statusCode(201);

        // 4. Preview slots over a Monday (2026-06-08 is a Monday) -> expect 2.
        given().when().get("/api/meeting-types/" + slug + "/slots?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200).body("size()", is(2));
    }

    @Test
    void secretTypeIsHiddenFromPublicListButBookableByLink() {
        // Ensure settings exist for slot preview.
        given().contentType("application/json")
                .body("{\"ownerName\":\"Owner\",\"ownerEmail\":\"o@example.com\",\"timezone\":\"Europe/Amsterdam\"}")
                .when().put("/api/settings")
                .then().statusCode(200);

        String slug = "secret-api-" + System.nanoTime();
        given().contentType("application/json")
                .body("{\"name\":\"Secret\",\"slug\":\"" + slug + "\",\"durationMinutes\":60,\"secret\":true}")
                .when().post("/api/meeting-types")
                .then().statusCode(201);

        // Hidden from the public list.
        given().when().get("/api/meeting-types")
                .then().statusCode(200).body("slug", not(hasItem(slug)));

        // Visible in the admin list.
        given().when().get("/api/meeting-types/all")
                .then().statusCode(200).body("slug", hasItem(slug));

        // Still reachable by direct slug (booking link path) — 200, not 404.
        given().when().get("/api/meeting-types/" + slug + "/slots?from=2026-06-08&to=2026-06-08")
                .then().statusCode(200);
    }
}
