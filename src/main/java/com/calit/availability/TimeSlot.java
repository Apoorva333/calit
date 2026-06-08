package com.calit.availability;

import java.time.ZonedDateTime;

public record TimeSlot(ZonedDateTime start, ZonedDateTime end) {}
