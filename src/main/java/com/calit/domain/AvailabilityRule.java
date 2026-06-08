package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@Entity
@Table(name = "availability_rule")
public class AvailabilityRule extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    public DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    public LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    public LocalTime endTime;

    /** Null = global default rule. Otherwise this rule overrides for that meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    public static List<AvailabilityRule> forMeetingType(Long meetingTypeId, DayOfWeek dow) {
        return list("meetingTypeId = ?1 and dayOfWeek = ?2", meetingTypeId, dow);
    }

    public static List<AvailabilityRule> globalFor(DayOfWeek dow) {
        return list("meetingTypeId is null and dayOfWeek = ?1", dow);
    }
}
