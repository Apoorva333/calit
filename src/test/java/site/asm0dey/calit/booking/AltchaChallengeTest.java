package site.asm0dey.calit.booking;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AltchaProfile.class)
class AltchaChallengeTest {

    @Test
    void challengeEndpointReturnsSignedChallenge() {
        given().when()
                .get("/altcha/challenge")
                .then()
                .statusCode(200)
                .body("algorithm", notNullValue())
                .body("challenge", notNullValue())
                .body("salt", notNullValue())
                .body("signature", notNullValue())
                .body("maxnumber", notNullValue());
    }
}
