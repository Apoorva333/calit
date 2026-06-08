package com.calit.api;

import com.calit.domain.AvailabilityRule;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Path("/api/availability")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AvailabilityResource {

    public record RuleRequest(DayOfWeek dayOfWeek, String startTime, String endTime, Long meetingTypeId) {}

    @POST
    @Transactional
    public Response create(RuleRequest req) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = req.dayOfWeek();
        r.startTime = LocalTime.parse(req.startTime());
        r.endTime = LocalTime.parse(req.endTime());
        r.meetingTypeId = req.meetingTypeId();
        r.persist();
        return Response.status(Response.Status.CREATED).entity(r).build();
    }
}
