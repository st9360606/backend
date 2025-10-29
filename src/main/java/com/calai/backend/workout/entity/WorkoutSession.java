package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "workout_session")
public class WorkoutSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="dictionary_id", nullable=false)
    private WorkoutDictionary dictionary;

    @Column(name="minutes", nullable=false)
    private Integer minutes;

    @Column(name="kcal", nullable=false)
    private Integer kcal;

    @Column(name="started_at", nullable=false)
    private java.time.Instant startedAt;

    @Column(name="created_at", nullable=false, updatable=false)
    private java.time.Instant createdAt = java.time.Instant.now();

    // getters/setters ...
}