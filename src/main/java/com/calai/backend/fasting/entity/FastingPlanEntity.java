package com.calai.backend.fasting.entity;

import com.calai.backend.common.LocalTimeStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.*;

@Entity
@Table(name = "fasting_plan")
@Getter @Setter @NoArgsConstructor
public class FastingPlanEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String planCode; // '16:8'

    @Column(nullable = false, length = 5)
    @Convert(converter = LocalTimeStringConverter.class)
    private LocalTime startTime;

    @Column(nullable = false, length = 5)
    @Convert(converter = LocalTimeStringConverter.class)
    private LocalTime endTime;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false, length = 64)
    private String timeZone;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate void touch() { this.updatedAt = OffsetDateTime.now(); }
}
