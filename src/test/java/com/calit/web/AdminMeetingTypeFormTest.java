package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class AdminMeetingTypeFormTest {

    @Test
    void createFormExposesBufferInputs() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("name=\"bufferBeforeMinutes\""))
                .body(containsString("name=\"bufferAfterMinutes\""));
    }

    @Test
    void createPersistsSeparateBuffers() {
        String slug = "buffers-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Buffered Call")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("bufferBeforeMinutes", "10")
            .formParam("bufferAfterMinutes", "15")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types")
            .then().statusCode(200);

        MeetingType t = MeetingType.findBySlug(slug);
        assertNotNull(t);
        assertEquals(10, t.bufferBeforeMinutes);
        assertEquals(15, t.bufferAfterMinutes);
    }
}
