package com.calit.user;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Verifies form-login credentials against the DB argon2id hash. Replaces the Elytron
 * credential-comparison path that cannot consume argon2. Loads the AppUser by username,
 * verifies the password with PasswordHasher, and builds a SecurityIdentity carrying the
 * user's roles. Disabled users are rejected here (and again by EnabledUserAugmentor).
 */
@ApplicationScoped
public class AppUserIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    LoginTicketService loginTickets;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        return context.runBlocking(() -> authenticateBlocking(request));
    }

    @ActivateRequestContext
    SecurityIdentity authenticateBlocking(UsernamePasswordAuthenticationRequest request) {
        String username = request.getUsername();
        String secret = new String(request.getPassword().getPassword());

        AppUser user = AppUser.findByUsername(username);
        // 1) Normal form login: verify the argon2id hash (skipped for passwordless Google users).
        if (user != null && user.enabled && user.passwordHash != null
                && passwordHasher.verify(secret, user.passwordHash)) {
            return AppUserSecurityIdentities.of(user);
        }
        // 2) Google sign-in bridge: the "password" may be a single-use login ticket. It is consumed
        //    here (single-use) and must belong to the username it was submitted under.
        AppUser ticketUser = loginTickets.consume(secret, Instant.now());
        if (ticketUser != null && ticketUser.enabled
                && ticketUser.username.equals(Usernames.normalize(username))) {
            return AppUserSecurityIdentities.of(ticketUser);
        }
        throw new AuthenticationFailedException();
    }
}
