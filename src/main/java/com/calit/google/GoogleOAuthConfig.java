package com.calit.google;

import io.smallrye.config.ConfigMapping;

/** Maps the {@code google.oauth.*} and {@code google.application-name} keys from application.properties. */
@ConfigMapping(prefix = "google")
public interface GoogleOAuthConfig {

    OAuth oauth();

    /** Application name reported to the Google Calendar client. */
    String applicationName();

    interface OAuth {
        String clientId();

        String clientSecret();

        String redirectUri();

        String scope();

        /** Shared HMAC key for signing the stateless CSRF {@code state}; identical on every replica. */
        String stateSecret();
    }
}
