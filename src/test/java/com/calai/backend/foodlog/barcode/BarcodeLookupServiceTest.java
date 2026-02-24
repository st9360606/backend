package com.calai.backend.foodlog.barcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest
class BarcodeLookupServiceTest {

    @Autowired BarcodeLookupService svc;
    @Autowired BarcodeLookupCacheRepository repo;
    @Autowired ObjectMapper om;

    @MockitoBean OpenFoodFactsClient offClient;

    @Test
    void cache_hit_should_not_call_off() {
        String raw = "034000470693"; // will norm -> 0034000470693
        String norm = BarcodeNormalizer.normalizeOrThrow(raw).normalized();

        ObjectNode root = om.createObjectNode();
        root.put("status", 1);
        root.set("product", om.createObjectNode().put("product_name", "Cached Product"));

        BarcodeLookupCacheEntity e = new BarcodeLookupCacheEntity();
        e.setBarcodeNorm(norm);
        e.setStatus("FOUND");
        e.setProvider("OPENFOODFACTS");
        e.setPayload(root);
        e.setExpiresAtUtc(Instant.now().plusSeconds(3600));
        repo.save(e);

        var r = svc.lookupOff(raw, "en");

        assertThat(r.fromCache()).isTrue();
        assertThat(r.found()).isTrue();
        verify(offClient, never()).getProduct(anyString(), any());
    }
}