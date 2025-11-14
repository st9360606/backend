package com.calai.backend.weight.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "weight_timeseries",
        uniqueConstraints = @UniqueConstraint(name="uq_weight_timeseries_user_date", columnNames = {"user_id","log_date"})
)
public class WeightTimeseries {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="log_date", nullable=false)
    private LocalDate logDate;

    @Column(name="weight_kg", nullable=false, precision = 6, scale = 1)
    private BigDecimal weightKg;

    @Column(name="timezone", nullable=false, length=64)
    private String timezone;

    @Column(name="created_at", nullable=false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
