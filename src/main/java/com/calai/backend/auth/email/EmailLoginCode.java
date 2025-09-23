// backend/src/main/java/com/calai/backend/auth/email/EmailLoginCode.java
package com.calai.backend.auth.email;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "email_login_codes")
public class EmailLoginCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    private String purpose; // "LOGIN"
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant createdAt;
    private Integer attemptCnt;
    private String clientIp;
    private String userAgent;

}
