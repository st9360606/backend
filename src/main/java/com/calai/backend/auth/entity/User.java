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

    @Column(name="google_sub", unique = true) // 允許為 null
    private String googleSub;

    @Column private String email;
    @Column private String name;
    @Column private String picture;

    @Column(name="created_at", nullable = false, columnDefinition="timestamp default current_timestamp")
    private Instant createdAt;
    @Column(name="updated_at", nullable = false, columnDefinition="timestamp default current_timestamp on update current_timestamp")
    private Instant updatedAt;
    @Column(name="last_login_at")
    private Instant lastLoginAt;

    // ======= getters & setters =======

}