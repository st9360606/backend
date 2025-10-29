package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
@Data
@Entity
@Table(name = "workout_dictionary")
public class WorkoutDictionary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="canonical_key", unique=true, nullable=false)
    private String canonicalKey;

    @Column(name="display_name_en", nullable=false)
    private String displayNameEn;

    @Column(name="met_value", nullable=false)
    private Double metValue;

    @Column(name="icon_key", nullable=false)
    private String iconKey;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt = Instant.now();

    // getters/setters ...
}