package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class StaticAssetsTest {

    @Test
    void customStylesheetIsServed() {
        given().when().get("/calit.css")
                .then().statusCode(200)
                .contentType(containsString("css"))
                .body(containsString("--color-base-100"));
    }
}
