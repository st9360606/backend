package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "workout_alias",
        indexes = {
                @Index(name = "idx_alias_lang_phrase", columnList = "lang_tag, phrase_lower"),
                @Index(name = "idx_alias_status", columnList = "status")
        })
public class WorkoutAlias {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY, optional = true)
    @JoinColumn(name="dictionary_id")
    private WorkoutDictionary dictionary;

    @Column(name="lang_tag", nullable=false, length = 16)
    private String langTag;

    @Column(name="phrase_lower", nullable=false, length = 256)
    private String phraseLower;

    @Column(name="status", nullable=false, length = 16) // "APPROVED","PENDING","REJECTED"
    private String status;

    @Column(name="created_by_user")
    private Long createdByUser;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt = Instant.now();

    // ★ 新增：自動升級用統計欄位
    @Column(name="total_count")
    private Integer totalCount; // 最近窗口內樣本數

    @Column(name="distinct_users")
    private Integer distinctUsers; // 最近窗口內人數

    @Column(name="confidence_median")
    private Double confidenceMedian; // 中位數分數 0..1

    @Column(name="last_seen")
    private Instant lastSeen; // 最後一次命中事件時間
}
