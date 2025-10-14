package com.calai.backend.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Data
@Table(name = "users")
@Entity
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, unique = true)
    private String googleSub;

    @Column private String email;
    @Column private String name;
    @Column private String picture;

    // ★ 新增：登入提供者
    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private Provider provider;

    // ★ 新增：Email 是否已驗證
    @Column(name = "email_verified")
    private Boolean emailVerified;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="last_login_at")
    private Instant lastLoginAt;
}
