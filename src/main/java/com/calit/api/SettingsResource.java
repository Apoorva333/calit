package com.calit.api;

import com.calit.domain.OwnerSettings;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/settings")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SettingsResource {

    public record SettingsRequest(String ownerName, String ownerEmail, String timezone) {}

    @PUT
    @Transactional
    public OwnerSettings update(SettingsRequest req) {
        OwnerSettings s = OwnerSettings.get();
        if (s == null) {
            s = new OwnerSettings();
            s.id = OwnerSettings.SINGLETON_ID;
        }
        s.ownerName = req.ownerName();
        s.ownerEmail = req.ownerEmail();
        s.timezone = req.timezone();
        s.persist();
        return s;
    }
}
