package com.calai.backend.workout.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(
        name = "workout_session",
        indexes = {
                @Index(name = "idx_workout_session_user_local_date", columnList = "user_id, local_date"),
                @Index(name = "idx_workout_session_user_started_at", columnList = "user_id, started_at"),
                @Index(name = "idx_workout_session_user_local_date_started_at", columnList = "user_id, local_date, started_at")
        }
)
public class WorkoutSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dictionary_id", nullable = false)
    private WorkoutDictionary dictionary;

    @Column(name = "minutes", nullable = false)
    private Integer minutes;

    @Column(name = "kcal", nullable = false)
    private Integer kcal;

    /**
     * 真正發生的 server instant（UTC timeline）
     */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * 以建立當下使用者時區切出的本地日期
     */
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    /**
     * 建立這筆 session 時採用的時區 ID，例如 Asia/Taipei
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /**
     * row 建立時間（UTC），由 service + Clock 控制
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
