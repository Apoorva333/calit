package com.calit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "owner_settings")
public class OwnerSettings extends PanacheEntityBase {

    public static final long SINGLETON_ID = 1L;

    @Id
    public Long id;

    @Column(name = "owner_name", nullable = false)
    public String ownerName;

    @Column(name = "owner_email", nullable = false)
    public String ownerEmail;

    @Column(nullable = false, length = 64)
    public String timezone;

    /** When false, the owner suppresses their own notification emails (Plan 4 gates on this). */
    @Column(name = "owner_notifications_enabled", nullable = false)
    public boolean ownerNotificationsEnabled = true;

    /** Returns the single settings row, or null if not yet configured. */
    public static OwnerSettings get() {
        return findById(SINGLETON_ID);
    }
}
