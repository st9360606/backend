package com.caloshape.backend.auth.entity;

import com.caloshape.backend.users.user.entity.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 只讀外鍵，避免為了拿 userId 去觸發 Lazy Proxy */
    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TokenType type;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "replaced_by", length = 64, columnDefinition = "CHAR(64)")
    private String replacedBy;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    public enum TokenType { ACCESS, REFRESH }
}
