package com.calit.crypto;

import com.calit.google.GoogleCredential;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TokenEncryptionAtRestTest {

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void refreshTokenIsCiphertextInTheRowButPlaintextViaEntity() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.googleSub = "sub-enc-test";
        c.refreshToken = "1//super-secret-refresh";
        c.accessToken = "ya29.access-secret";
        c.accessTokenExpiry = Instant.now().plusSeconds(3600);
        c.persist();
        c.flush();

        Object raw = em.createNativeQuery(
                "select refresh_token from google_credential where id = :id")
                .setParameter("id", c.id)
                .getSingleResult();
        assertTrue(raw.toString().startsWith("enc:v1:"), "stored token must be encrypted");
        assertFalse(raw.toString().contains("super-secret-refresh"), "plaintext must not be at rest");

        GoogleCredential reloaded = GoogleCredential.findById(c.id);
        assertEquals("1//super-secret-refresh", reloaded.refreshToken);
        assertEquals("ya29.access-secret", reloaded.accessToken);
    }
}
