package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "workout_alias")
public class WorkoutAlias {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // nullable: PENDING 狀態還不知道對應哪個 dictionary
    @ManyToOne(fetch=FetchType.LAZY, optional = true)
    @JoinColumn(name="dictionary_id")
    private WorkoutDictionary dictionary;

    @Column(name="lang_tag", nullable=false)
    private String langTag; // 例如 "zh-TW", "vi-VN", "en"

    @Column(name="phrase_lower", nullable=false)
    private String phraseLower;

    @Column(name="status", nullable=false)
    private String status; // "APPROVED","PENDING","REJECTED"

    @Column(name="created_by_user")
    private Long createdByUser;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt = Instant.now();

    // getters/setters ...
}
