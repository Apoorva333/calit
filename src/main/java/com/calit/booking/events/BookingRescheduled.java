package com.calit.booking.events;

import java.time.Instant;

public record BookingRescheduled(Long bookingId, Instant oldStartUtc) {}
