package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.provider.gemini.spi.GeminiModeProcessor;
import com.calai.backend.foodlog.provider.gemini.GeminiProviderClient;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiProviderClientTest {

    @Test
    void providerCode_should_return_GEMINI() {
        GeminiModeProcessor p1 = mock(GeminiModeProcessor.class);
        GeminiProviderClient client = new GeminiProviderClient(List.of(p1));

        assertEquals("GEMINI", client.providerCode());
    }

    @Test
    void process_should_dispatch_to_first_supported_processor() throws Exception {
        GeminiModeProcessor photoProcessor = mock(GeminiModeProcessor.class);
        GeminiModeProcessor labelProcessor = mock(GeminiModeProcessor.class);

        GeminiProviderClient client = new GeminiProviderClient(List.of(photoProcessor, labelProcessor));

        FoodLogEntity entity = new FoodLogEntity();
        entity.setMethod("PHOTO");

        StorageService storage = mock(StorageService.class);

        ObjectMapper om = new ObjectMapper();
        ObjectNode effective = om.createObjectNode();
        effective.put("foodName", "Paella");

        ProviderClient.ProviderResult expected =
                new ProviderClient.ProviderResult(effective, "GEMINI");

        when(photoProcessor.supports("PHOTO")).thenReturn(true);
        when(photoProcessor.process(entity, storage)).thenReturn(expected);

        ProviderClient.ProviderResult actual = client.process(entity, storage);

        assertNotNull(actual);
        assertEquals("GEMINI", actual.provider());
        assertEquals("Paella", actual.effective().path("foodName").asText());

        verify(photoProcessor, times(1)).supports("PHOTO");
        verify(photoProcessor, times(1)).process(entity, storage);
        verifyNoInteractions(labelProcessor);
    }

    @Test
    void process_should_throw_when_no_processor_supports_method() {
        GeminiModeProcessor p1 = mock(GeminiModeProcessor.class);
        GeminiModeProcessor p2 = mock(GeminiModeProcessor.class);

        when(p1.supports("UNKNOWN")).thenReturn(false);
        when(p2.supports("UNKNOWN")).thenReturn(false);

        GeminiProviderClient client = new GeminiProviderClient(List.of(p1, p2));

        FoodLogEntity entity = new FoodLogEntity();
        entity.setMethod("UNKNOWN");

        StorageService storage = mock(StorageService.class);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> client.process(entity, storage)
        );

        assertEquals("UNSUPPORTED_METHOD_FOR_GEMINI_PROVIDER", ex.getMessage());
    }
}
