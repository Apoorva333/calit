package com.calit.booking.events;

/** Feature 14: the owner declined a PENDING request (PENDING -> DECLINED). */
public record BookingDeclined(Long bookingId) {}
