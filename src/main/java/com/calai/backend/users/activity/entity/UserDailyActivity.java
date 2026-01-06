package com.calai.backend.users.activity.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(
        name = "user_daily_activity",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_day", columnNames = {"user_id", "local_date"})
)
public class UserDailyActivity {

    public enum IngestSource {
        HEALTH_CONNECT,
        MANUAL,
        IMPORT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "day_start_utc", nullable = false)
    private Instant dayStartUtc;

    @Column(name = "day_end_utc", nullable = false)
    private Instant dayEndUtc;

    // ✅ 規格：允許 null（未知/未授權/無資料）
    @Column(name = "steps")
    private Long steps;

    // ✅ 規格：active_kcal 允許 null
    @Column(name = "active_kcal")
    private Double activeKcal;

    // ✅ ingest_source（必填）
    @Enumerated(EnumType.STRING)
    @Column(name = "ingest_source", nullable = false, length = 32)
    private IngestSource ingestSource = IngestSource.HEALTH_CONNECT;

    // ✅ data_origin（HC 記錄來源 app）
    @Column(name = "data_origin_package")
    private String dataOriginPackage;

    @Column(name = "data_origin_name")
    private String dataOriginName;

    // ✅ 伺服器實際寫入時間（排查用）
    @Column(name = "ingested_at")
    private Instant ingestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.ingestedAt == null) this.ingestedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
