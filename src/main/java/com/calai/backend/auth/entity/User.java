package com.calai.backend.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
@Data
@Table(name = "users") // ← 綁定既有資料表
@Entity
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, unique = true)
    private String googleSub;

    @Column private String email;
    @Column private String name;
    @Column private String picture;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="last_login_at")
    private Instant lastLoginAt;

    // ======= getters & setters =======

}