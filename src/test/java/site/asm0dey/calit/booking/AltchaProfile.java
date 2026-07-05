package site.asm0dey.calit.booking;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Shared ALTCHA-on profile. One class = one Quarkus reboot under the profile-aware class orderer;
 * the three ALTCHA tests previously each declared an identical inner profile and cost three reboots.
 * {@code max-number} is a superset key harmless to tests that don't read it.
 */
public class AltchaProfile implements QuarkusTestProfile {
    public static final String KEY = "test-hmac-secret";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "calit.captcha.provider", "altcha",
                "calit.captcha.altcha.hmac-key", KEY,
                "calit.captcha.altcha.max-number", "100000");
    }
}
