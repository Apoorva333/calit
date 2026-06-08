package com.calit.booking.events;

/** An auto (no-approval) booking was confirmed immediately. */
public record BookingConfirmed(Long bookingId) {}
