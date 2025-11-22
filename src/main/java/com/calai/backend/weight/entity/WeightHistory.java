package com.calai.backend.weight.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor
@Entity @Table(
        name = "weight_history",
        uniqueConstraints = @UniqueConstraint(name="uq_weight_history_user_date", columnNames = {"user_id","log_date"})
)
public class WeightHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    /** 使用者當地日期（由 X-Client-Timezone 計算） */
    @Column(name="log_date", nullable=false)
    private LocalDate logDate;

    /** 唯一真實來源（kg, 保留一位小數） */
    @Column(name="weight_kg", nullable=false, precision = 6, scale = 1)
    private BigDecimal weightKg;

    /** 同步保存 lbs（保留一位小數） */
    @Column(name="weight_lbs", nullable=false, precision = 6, scale = 1)
    private BigDecimal weightLbs;   // ★ 新增欄位對應

    /** 使用者當次時區（保存字串，便於稽核/後續修正） */
    @Column(name="timezone", nullable=false, length=64)
    private String timezone;

    /** 可為 null */
    @Column(name="photo_url")
    private String photoUrl;

    @Column(name="created_at", nullable=false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name="updated_at", nullable=false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

    @PreUpdate void onUpdate(){ updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
}
