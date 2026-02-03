package com.calai.backend.foodlog.entity;


import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "food_logs")
public class FoodLogEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FoodLogStatus status;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "degrade_level", length = 8)
    private String degradeLevel;

    @Column(name = "captured_at_utc", nullable = false)
    private Instant capturedAtUtc;

    @Column(name = "captured_tz", nullable = false, length = 64)
    private String capturedTz;

    @Column(name = "captured_local_date", nullable = false)
    private LocalDate capturedLocalDate;

    @Column(name = "server_received_at_utc", nullable = false)
    private Instant serverReceivedAtUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_source", nullable = false, length = 16)
    private TimeSource timeSource;

    @Column(name = "time_suspect", nullable = false)
    private boolean timeSuspect;

    @Column(name = "image_object_key", columnDefinition = "TEXT")
    private String imageObjectKey;

    @Column(name = "image_sha256", length = 64)
    private String imageSha256;

    @Column(name = "image_content_type", length = 64)
    private String imageContentType;

    @Column(name = "image_size_bytes")
    private Long imageSizeBytes;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective", columnDefinition = "JSON")
    private JsonNode effective;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "deleted_by", length = 16)
    private String deletedBy;

    @Column(name = "deleted_at_utc")
    private Instant deletedAtUtc;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }

    public void applyEffectivePatch(String fieldKey, JsonNode newValue) {
        ObjectNode root;
        if (this.effective == null || this.effective.isNull() || !this.effective.isObject()) {
            root = JsonNodeFactory.instance.objectNode();
        } else {
            root = this.effective.deepCopy();
        }

        switch (fieldKey) {
            case "FOOD_NAME" -> {
                if (newValue == null || newValue.isNull()) root.remove("foodName");
                else root.set("foodName", newValue);
            }
            case "QUANTITY" -> {
                if (newValue == null || newValue.isNull()) root.remove("quantity");
                else root.set("quantity", newValue);
            }
            case "HEALTH_SCORE" -> {
                if (newValue == null || newValue.isNull()) root.remove("healthScore");
                else root.set("healthScore", newValue);
            }
            case "NUTRIENTS" -> {
                if (newValue == null || newValue.isNull()) {
                    root.remove("nutrients");
                } else {
                    ObjectNode merged;

                    JsonNode existing = root.get("nutrients");
                    if (existing != null && existing.isObject()) {
                        // ✅ 不需要 (ObjectNode) cast
                        merged = existing.deepCopy();
                    } else {
                        merged = JsonNodeFactory.instance.objectNode();
                    }

                    if (!newValue.isObject()) {
                        throw new IllegalArgumentException("OVERRIDE_VALUE_INVALID");
                    }

                    // ✅ Jackson 新版建議用 properties() 取代 fields()
                    for (Map.Entry<String, JsonNode> e : newValue.properties()) {
                        merged.set(e.getKey(), e.getValue());
                    }

                    root.set("nutrients", merged);
                }
            }
            default -> throw new IllegalArgumentException("FIELD_KEY_INVALID");
        }

        this.effective = root;
    }
}
