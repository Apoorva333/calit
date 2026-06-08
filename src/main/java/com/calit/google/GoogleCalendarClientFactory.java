package com.calit.google;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds an authorized Google Calendar service client from a bearer access token.
 * Pure wiring of Google client types; the testable behavior lives in GoogleCalendarPort
 * (mocked downstream) and GoogleTokenService (stubbed in tests).
 */
@ApplicationScoped
public class GoogleCalendarClientFactory {

    /** Scope reference kept to document the required grant; the token already carries it. */
    public static final String SCOPE = CalendarScopes.CALENDAR;

    private final GoogleOAuthConfig config;

    @Inject
    public GoogleCalendarClientFactory(GoogleOAuthConfig config) {
        this.config = config;
    }

    /** Build a Calendar service authorized with the given bearer access token. */
    public Calendar build(String accessToken) {
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);
        return new Calendar.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName(config.applicationName())
                .build();
    }
}
