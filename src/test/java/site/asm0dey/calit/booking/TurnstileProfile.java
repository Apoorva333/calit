package site.asm0dey.calit.booking;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Shared Turnstile-on profile. Unions every turnstile key the turnstile tests need (provider,
 * legacy render flag, site-key, server-side secret + verify-url) so they share one Quarkus boot.
 * {@link #VERIFY_PORT} is the fixed port the CaptchaVerifier turnstile test binds its local
 * siteverify stub to; keys unused by a given test are inert.
 */
public class TurnstileProfile implements QuarkusTestProfile {
    public static final int VERIFY_PORT = 18477;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "calit.captcha.provider", "turnstile",
                "calit.turnstile.enabled", "true",
                "calit.turnstile.site-key", "1x00000000000000000000AA",
                "calit.abuse.turnstile.secret", "test-secret",
                "calit.abuse.turnstile.verify-url", "http://localhost:" + VERIFY_PORT + "/siteverify");
    }
}
