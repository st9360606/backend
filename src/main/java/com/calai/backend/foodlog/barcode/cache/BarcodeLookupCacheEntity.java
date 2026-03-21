package com.calai.backend.foodlog.barcode.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "barcode_lookup_cache")
public class BarcodeLookupCacheEntity {

    private static final ObjectMapper OM = new ObjectMapper();

    @Id
    @Column(name = "barcode_norm", length = 32, nullable = false)
    private String barcodeNorm;

    @Column(name = "barcode_raw_example", length = 64)
    private String barcodeRawExample;

    /** FOUND / NOT_FOUND */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** OPENFOODFACTS / ... */
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    /**
     * ✅ 用文字存 JSON，避免 Hibernate JSON 型別與 Dialect（H2/MySQL）踩坑
     * ✅ 不要寫 columnDefinition="LONGTEXT"，讓 @Lob + Dialect 自己決定（H2 會用 CLOB、MySQL 會用 TEXT/LONGTEXT）
     */
    @Lob
    @Column(name = "payload_text")
    private String payloadText;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAtUtc == null) createdAtUtc = now;
        if (updatedAtUtc == null) updatedAtUtc = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAtUtc = Instant.now();
    }

    // ===== ✅ 你缺的就是這兩個 =====

    /** 讓你原本 service/test 可以繼續用 setPayload(JsonNode) */
    public void setPayload(JsonNode node) {
        this.payloadText = (node == null) ? null : node.toString();
    }

    /**
     * 讓你原本 service 可以繼續用 getPayload()
     * 若 payloadText 解析失敗，回 NullNode，避免 NPE 讓流程自動當作 NOT_FOUND
     */
    @Transient
    public JsonNode getPayload() {
        if (payloadText == null || payloadText.isBlank()) return NullNode.getInstance();
        try {
            return OM.readTree(payloadText);
        } catch (Exception ignore) {
            return NullNode.getInstance();
        }
    }

    // ===== getters/setters =====
    public String getBarcodeNorm() { return barcodeNorm; }
    public void setBarcodeNorm(String barcodeNorm) { this.barcodeNorm = barcodeNorm; }

    public String getBarcodeRawExample() { return barcodeRawExample; }
    public void setBarcodeRawExample(String barcodeRawExample) { this.barcodeRawExample = barcodeRawExample; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getPayloadText() { return payloadText; }
    public void setPayloadText(String payloadText) { this.payloadText = payloadText; }

    public Instant getExpiresAtUtc() { return expiresAtUtc; }
    public void setExpiresAtUtc(Instant expiresAtUtc) { this.expiresAtUtc = expiresAtUtc; }

    public Instant getCreatedAtUtc() { return createdAtUtc; }
    public void setCreatedAtUtc(Instant createdAtUtc) { this.createdAtUtc = createdAtUtc; }

    public Instant getUpdatedAtUtc() { return updatedAtUtc; }
    public void setUpdatedAtUtc(Instant updatedAtUtc) { this.updatedAtUtc = updatedAtUtc; }
}
