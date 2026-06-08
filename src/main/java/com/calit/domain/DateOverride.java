package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Replace-semantics availability override for a single date.
 * An empty {@link #windows} list means the day is OFF (blocked).
 * Resolution order: per-type override, else global override, else null
 * (null => fall through to weekly {@link AvailabilityRule}).
 *
 * <p>The {@link #windows} collection is {@link Transient}: it is populated by
 * {@link #resolve} via an explicit {@link DateOverrideWindow} query, which avoids
 * both the Hibernate "repeated column in mapping" boot error (date_override_id mapped
 * on both parent @OneToMany and child @Column) and the L1-cache stale-collection
 * problem that arises when windows are persisted in the same transaction via the
 * child's {@code dateOverrideId} field rather than through the parent collection.
 */
@Entity
@Table(name = "date_override")
public class DateOverride extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Null = global override; otherwise scoped to this meeting type. */
    @Column(name = "meeting_type_id")
    public Long meetingTypeId;

    @Column(name = "override_date", nullable = false)
    public LocalDate overrideDate;

    /**
     * Bookable windows for this date. Empty = day off (caller emits no slots).
     * Populated by {@link #resolve}; not persisted by this entity.
     */
    @Transient
    public List<DateOverrideWindow> windows = new ArrayList<>();

    /**
     * Per-type override for (meetingTypeId, date) if present; else the global
     * (meeting_type_id IS NULL) override for the date; else null.
     *
     * <p>Windows are loaded via a separate query ordered by start_time, so ordering
     * is correct regardless of insert order.
     */
    public static DateOverride resolve(Long meetingTypeId, LocalDate date) {
        DateOverride typed = find("meetingTypeId = ?1 and overrideDate = ?2", meetingTypeId, date).firstResult();
        if (typed != null) {
            typed.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", typed.id);
            return typed;
        }
        DateOverride global = find("meetingTypeId is null and overrideDate = ?1", date).firstResult();
        if (global != null) {
            global.windows = DateOverrideWindow
                    .list("dateOverrideId = ?1 order by startTime asc", global.id);
        }
        return global;
    }
}
