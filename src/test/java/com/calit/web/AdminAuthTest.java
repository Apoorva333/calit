package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminAuthTest {

    @Test
    void dashboardRequiresAuth() {
        given()
            .when().get("/admin")
            .then().statusCode(401);
    }

    @Test
    void dashboardServedWithBasicAuth() {
        given()
            .auth().preemptive().basic("admin", "testpass")
            .when().get("/admin")
            .then()
                .statusCode(200)
                .body(containsString("Dashboard"));
    }
}
