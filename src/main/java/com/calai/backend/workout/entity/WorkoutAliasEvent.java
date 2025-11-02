package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "workout_alias_event",
        indexes = {
                @Index(name="idx_evt_created", columnList = "created_at"),
                @Index(name="idx_evt_lang_phrase", columnList = "lang_tag, phrase_lower"),
                @Index(name="idx_evt_user", columnList = "user_id")
        })
public class WorkoutAliasEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="lang_tag", nullable=false, length=16)
    private String langTag;

    @Column(name="phrase_lower", nullable=false, length=256)
    private String phraseLower;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="matched_dict_id")
    private WorkoutDictionary matchedDict;

    @Column(name="score")
    private Double score;

    @Column(name="used_generic", nullable=false)
    private boolean usedGeneric;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt = Instant.now();
}
