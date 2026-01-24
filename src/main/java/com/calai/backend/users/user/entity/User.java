package com.calai.backend.users.user.entity;

import com.calai.backend.auth.entity.AuthProvider;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Data
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                // email 視為唯一（配合下方我們會把 email 統一 lower-case）
                @UniqueConstraint(name = "ux_users_email", columnNames = {"email"})
        }
)
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Google 的 subject，保留唯一
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    // 把 email 視為唯一、不可為空（若你 DB 目前允許 null，先清理/補上再上線）
    @Column(name = "email", nullable = true, unique = true, length = 320)
    private String email;

    @Column private String name;
    @Column private String picture;

    // 登入來源（資料表若已經有 provider 欄位，這裡就正好對上）
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private AuthProvider provider;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "deleted_at_utc")
    private Instant deletedAtUtc;

    @Column(name = "deleted_email_hash", length = 64)
    private String deletedEmailHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name="last_login_at")
    private Instant lastLoginAt;

    /** 統一以小寫寫入，避免大小寫造成重複帳號 */
    public void setEmail(String email) {
        this.email = (email == null) ? null : email.trim().toLowerCase();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
