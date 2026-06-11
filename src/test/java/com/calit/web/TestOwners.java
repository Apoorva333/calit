package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.test.TestTransaction;

/** Test helper: resolves seeded AppUser ids by username for owner-scoped seeding/asserts. */
final class TestOwners {
    private TestOwners() {}

    /** The id of the AppUser FormAuth logs in as (username "admin"). */
    @TestTransaction
    static Long loginOwnerId() {
        return AppUser.findByUsername("admin").id;
    }
}
