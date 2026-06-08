package com.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "google_credential")
public class GoogleCredential extends PanacheEntityBase {

    public static final long SINGLETON_ID = 1L;

    /** Refresh the access token this long before its real expiry to avoid edge-of-expiry failures. */
    public static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    @Id
    public Long id;

    /** Long-lived offline refresh token. Obtained once during the consent flow. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    public String refreshToken;

    /** Short-lived access token, refreshed on demand. Null until first refresh. */
    @Column(name = "access_token", columnDefinition = "text")
    public String accessToken;

    /** Instant the current access token stops being valid. Null when no access token is cached. */
    @Column(name = "access_token_expiry")
    public Instant accessTokenExpiry;

    /** The single credential row, or null if Google is not yet connected. */
    public static GoogleCredential get() {
        return findById(SINGLETON_ID);
    }

    /** True when there is no cached access token, or it expires within the safety margin of {@code now}. */
    public boolean isAccessTokenExpired(Instant now) {
        if (accessToken == null || accessTokenExpiry == null) {
            return true;
        }
        return !now.plus(SAFETY_MARGIN).isBefore(accessTokenExpiry);
    }
}
