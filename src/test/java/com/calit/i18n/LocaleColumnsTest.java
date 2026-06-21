package com.calit.i18n;

import com.calit.domain.OwnerSettings;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class LocaleColumnsTest {
    @Test @TestTransaction
    void ownerSettingsHasLocaleDefaultingToEn() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "Admin";
            s.ownerEmail = "admin@example.com";
            s.timezone = "UTC";
        }
        s.persist();

        OwnerSettings loaded = OwnerSettings.forOwner(1L); // admin always id 1
        assertEquals("en", loaded.locale);
    }
}
