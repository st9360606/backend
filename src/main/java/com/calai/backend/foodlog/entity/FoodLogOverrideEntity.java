package com.calai.backend.foodlog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "food_log_overrides",
        indexes = @Index(name = "idx_food_log_overrides_log", columnList = "food_log_id,edited_at_utc")
)
public class FoodLogOverrideEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "food_log_id", length = 36, nullable = false)
    private String foodLogId;

    @Column(name = "field_key", length = 32, nullable = false)
    private String fieldKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value_json", columnDefinition = "JSON")
    private JsonNode oldValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value_json", columnDefinition = "JSON", nullable = false)
    private JsonNode newValueJson;

    @Column(name = "editor_type", length = 16, nullable = false)
    private String editorType; // USER/ADMIN/SYSTEM

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "edited_at_utc", nullable = false)
    private Instant editedAtUtc;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (editedAtUtc == null) editedAtUtc = Instant.now();
    }

    public static FoodLogOverrideEntity create(
            String foodLogId,
            String fieldKey,
            JsonNode oldValue,
            JsonNode newValue,
            String editorType,
            String reason,
            Instant editedAtUtc
    ) {
        FoodLogOverrideEntity e = new FoodLogOverrideEntity();
        e.foodLogId = foodLogId;
        e.fieldKey = fieldKey;
        e.oldValueJson = oldValue;
        e.newValueJson = newValue;
        e.editorType = editorType;
        e.reason = reason;
        e.editedAtUtc = editedAtUtc;
        return e;
    }
}
