package com.calai.backend.water.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "user_water_daily",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_date", columnNames = {"user_id", "local_date"})
        }
)

@Data
public class UserWaterDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="local_date", nullable=false)
    private LocalDate localDate;

    @Column(name="cups", nullable=false)
    private Integer cups;

    @Column(name="ml", nullable=false)
    private Integer ml;

    @Column(name="fl_oz", nullable=false)
    private Integer flOz;

    @Column(name="updated_at", nullable=false)
    private Instant updatedAt;

    // getters/setters ...
    @PrePersist @PreUpdate
    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public void applyCups(int newCups) {
        int safe = Math.max(newCups, 0);
        this.cups = safe;
        this.ml = safe * 237;
        this.flOz = safe * 8;
    }

    // getters / setters omitted for brevity
}
