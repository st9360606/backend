package com.calai.backend.auth.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
@Data
@Entity @Table(name="auth_tokens")
public class AuthToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable=false, unique=true, length=64) private String token;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id", nullable=false)
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private TokenType type;
    @Column(nullable=false) private Instant expiresAt;
    @Column(nullable=false) private Instant createdAt = Instant.now();
    @Column(nullable=false) private boolean revoked = false;
    private String replacedBy;
    private String deviceId;
    private String clientIp;
    private String userAgent;
    public enum TokenType { ACCESS, REFRESH }


}