package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class GoogleCalendarResourceTest {

    @InjectMock
    CalendarListPort calendarListPort;

    @BeforeEach
    @Transactional
    void cleanUp() {
        GoogleCalendar.deleteAll();
    }

    @Test
    void listsGoogleCalendars() {
        Mockito.when(calendarListPort.listCalendars()).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("work@example.com", "Work"),
                new CalendarListPort.RemoteCalendar("personal@example.com", "Personal")));

        given().when().get("/api/google/calendars")
                .then().statusCode(200)
                .body("googleCalendarId", hasItem("work@example.com"))
                .body("summary", hasItem("Personal"));
    }

    @Test
    void savesReadWriteSelectionAndEnforcesSingleWriteTarget() {
        String writeId = "write-" + System.nanoTime() + "@example.com";
        String readId = "read-" + System.nanoTime() + "@example.com";

        String body = "{\"calendars\":["
                + "{\"googleCalendarId\":\"" + readId + "\",\"summary\":\"Read\",\"readForBusy\":true,\"writeTarget\":false},"
                + "{\"googleCalendarId\":\"" + writeId + "\",\"summary\":\"Write\",\"readForBusy\":false,\"writeTarget\":true}"
                + "]}";

        given().contentType("application/json").body(body)
                .when().post("/api/google/calendars")
                .then().statusCode(200);

        // The write target query returns exactly the one flagged calendar.
        given().when().get("/api/google/calendars/write-target")
                .then().statusCode(200)
                .body("googleCalendarId", is(writeId));
    }
}
