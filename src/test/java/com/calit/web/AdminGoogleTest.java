package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminGoogleTest {

    @Test
    void googlePageLinksToConnectEndpoint() {
        given()
            .auth().preemptive().basic("admin", "testpass")
            .when().get("/admin/google")
            .then()
                .statusCode(200)
                .body(containsString("/api/google/connect"))
                .body(containsString("Connect Google"));
    }

    @Test
    void googlePageRequiresAuth() {
        given().when().get("/admin/google").then().statusCode(401);
    }
}
