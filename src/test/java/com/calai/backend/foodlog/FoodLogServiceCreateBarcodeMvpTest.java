package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.barcode.openfoodfacts.error.OffHttpException;
import com.calai.backend.foodlog.barcode.openfoodfacts.error.OffParseException;
import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateBarcodeMvpTest {

    private static final Instant NOW = Instant.parse("2026-03-03T10:00:00Z");

    private ProviderClient providerClient;
    private FoodLogRepository repo;
    private FoodLogTaskRepository taskRepo;
    private StorageService storage;
    private QuotaService quota;
    private IdempotencyService idem;
    private ImageBlobService blobService;
    private UserInFlightLimiter inFlight;
    private UserRateLimiter rateLimiter;
    private FoodLogEffectivePostProcessor postProcessor;
    private ClientActionMapper clientActionMapper;
    private AbuseGuardService abuseGuard;
    private EntitlementService entitlementService;
    private BarcodeLookupService barcodeLookupService;
    private TransactionTemplate txTemplate;

    private Clock clock;
    private FoodLogService service;

    @BeforeEach
    void setUp() {
        providerClient = mock(ProviderClient.class);
        repo = mock(FoodLogRepository.class);
        taskRepo = mock(FoodLogTaskRepository.class);
        storage = mock(StorageService.class);
        quota = mock(QuotaService.class);
        idem = mock(IdempotencyService.class);
        blobService = mock(ImageBlobService.class);
        inFlight = mock(UserInFlightLimiter.class);
        rateLimiter = mock(UserRateLimiter.class);
        postProcessor = mock(FoodLogEffectivePostProcessor.class);
        clientActionMapper = mock(ClientActionMapper.class);
        abuseGuard = mock(AbuseGuardService.class);
        entitlementService = mock(EntitlementService.class);
        barcodeLookupService = mock(BarcodeLookupService.class);
        txTemplate = mock(TransactionTemplate.class);

        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new FoodLogService(
                providerClient,
                repo,
                taskRepo,
                storage,
                quota,
                idem,
                blobService,
                inFlight,
                rateLimiter,
                postProcessor,
                clientActionMapper,
                clock,
                abuseGuard,
                entitlementService,
                barcodeLookupService,
                txTemplate
        );
    }

    @Test
    void createBarcodeMvp_should_throw_when_barcode_required() {
        assertThatThrownBy(() ->
                service.createBarcodeMvp(
                        1L,
                        "Asia/Taipei",
                        "device-1",
                        null,
                        "zh-TW",
                        "req-1"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BARCODE_REQUIRED");

        verifyNoInteractions(idem, entitlementService, rateLimiter, abuseGuard, barcodeLookupService, txTemplate);
    }

    @Test
    void createBarcodeMvp_should_return_existing_when_idempotency_hit() {
        Long userId = 1L;
        String requestId = "req-hit";
        String existingLogId = "log-existing";

        FoodLogEntity existing = new FoodLogEntity();
        existing.setId(existingLogId);
        existing.setUserId(userId);
        existing.setMethod("BARCODE");
        existing.setProvider("OPENFOODFACTS");
        existing.setStatus(FoodLogStatus.DRAFT);
        existing.setDegradeLevel("DG-0");
        existing.setBarcode("4710018000017");

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(existingLogId);
        when(repo.findByIdAndUserId(existingLogId, userId)).thenReturn(Optional.of(existing));

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                "4710018000017",
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        verify(idem).reserveOrGetExisting(userId, requestId, NOW);
        verify(repo).findByIdAndUserId(existingLogId, userId);

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, barcodeLookupService, txTemplate);
    }

    @Test
    void createBarcodeMvp_should_persist_failed_when_barcode_not_found() {
        Long userId = 1L;
        String requestId = "req-bc-not-found";
        String barcode = "4710018000017";

        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        when(barcodeLookupService.lookupOff(eq(barcode), anyString()))
                .thenReturn(new BarcodeLookupService.LookupResult(
                        barcode,
                        barcode,
                        false,
                        false,
                        "OPENFOODFACTS",
                        null
                ));

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-not-found");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-not-found");
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getProvider()).isEqualTo("OPENFOODFACTS");
        assertThat(saved.getBarcode()).isEqualTo(barcode);
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_NOT_FOUND");
        assertThat(saved.getLastErrorMessage()).contains("openfoodfacts not found");
        assertThat(saved.getEffective()).isNull();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(idem).attach(userId, requestId, "log-bc-not-found", NOW);
    }

    @Test
    void createBarcodeMvp_should_persist_failed_when_nutrition_unavailable() {
        Long userId = 1L;
        String requestId = "req-bc-no-nutrition";
        String barcode = "4710018000017";

        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        OffResult off = new OffResult(
                "神秘零食",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null,
                null,
                java.util.List.of("snacks")
        );

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        when(barcodeLookupService.lookupOff(eq(barcode), anyString()))
                .thenReturn(new BarcodeLookupService.LookupResult(
                        barcode,
                        barcode,
                        true,
                        false,
                        "OPENFOODFACTS",
                        off
                ));

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-no-nutrition");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-no-nutrition");
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getProvider()).isEqualTo("OPENFOODFACTS");
        assertThat(saved.getBarcode()).isEqualTo(barcode);
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_NUTRITION_UNAVAILABLE");
        assertThat(saved.getLastErrorMessage()).contains("no usable nutrition");
        assertThat(saved.getEffective()).isNull();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(idem).attach(userId, requestId, "log-bc-no-nutrition", NOW);
        verify(postProcessor, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void createBarcodeMvp_should_persist_failed_when_provider_rate_limited() {
        Long userId = 1L;
        String requestId = "req-bc-rate";
        String barcode = "4710018000017";

        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        when(barcodeLookupService.lookupOff(eq(barcode), anyString()))
                .thenThrow(new OffHttpException(429, "PROVIDER_RATE_LIMITED", "too many requests"));

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-rate");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-rate");
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(saved.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(saved.getLastErrorMessage()).contains("status=429");
        assertThat(saved.getLastErrorMessage()).contains("PROVIDER_RATE_LIMITED");
        assertThat(saved.getEffective()).isNull();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(idem).attach(userId, requestId, "log-bc-rate", NOW);
    }

    @Test
    void createBarcodeMvp_should_persist_failed_when_off_parse_exception() {
        Long userId = 1L;
        String requestId = "req-bc-parse";
        String barcode = "4710018000017";

        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        OffParseException ex = mock(OffParseException.class);
        when(ex.getCode()).thenReturn("OFF_PARSE_FAILED");
        when(ex.getMessage()).thenReturn("bad off json");

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);
        when(barcodeLookupService.lookupOff(eq(barcode), anyString())).thenThrow(ex);

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-parse");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-parse");
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_LOOKUP_FAILED");
        assertThat(saved.getLastErrorMessage()).contains("parse error");
        assertThat(saved.getLastErrorMessage()).contains("code=OFF_PARSE_FAILED");
        assertThat(saved.getLastErrorMessage()).contains("bad off json");
        assertThat(saved.getEffective()).isNull();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(idem).attach(userId, requestId, "log-bc-parse", NOW);
    }

    @Test
    void createBarcodeMvp_should_return_draft_when_lookup_success() {
        Long userId = 1L;
        String requestId = "req-bc-success";
        String barcode = "4710018000017";

        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        OffResult off = new OffResult(
                "茶葉蛋",
                155.0,
                13.0,
                11.0,
                1.0,
                null,
                null,
                320.0,
                null, null, null, null, null, null, null,
                null,
                null,
                java.util.List.of("eggs", "snacks")
        );

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        when(barcodeLookupService.lookupOff(eq(barcode), anyString()))
                .thenReturn(new BarcodeLookupService.LookupResult(
                        barcode,
                        barcode,
                        true,
                        false,
                        "OPENFOODFACTS",
                        off
                ));

        when(postProcessor.apply(any(ObjectNode.class), eq("OPENFOODFACTS"), eq("BARCODE")))
                .thenAnswer(inv -> inv.getArgument(0));

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-success");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-success");
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getProvider()).isEqualTo("OPENFOODFACTS");
        assertThat(saved.getBarcode()).isEqualTo(barcode);
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.DRAFT);
        assertThat(saved.getLastErrorCode()).isNull();
        assertThat(saved.getLastErrorMessage()).isNull();
        assertThat(saved.getEffective()).isNotNull();

        assertThat(saved.getEffective().path("foodName").asText()).isEqualTo("茶葉蛋");
        assertThat(saved.getEffective().path("confidence").asDouble()).isEqualTo(0.98d);
        assertThat(saved.getEffective().path("aiMeta").path("source").asText()).isEqualTo("OPENFOODFACTS");
        assertThat(saved.getEffective().path("aiMeta").path("barcodeRaw").asText()).isEqualTo(barcode);
        assertThat(saved.getEffective().path("aiMeta").path("barcodeNorm").asText()).isEqualTo(barcode);
        assertThat(saved.getEffective().path("aiMeta").path("offFromCache").asBoolean()).isFalse();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(postProcessor).apply(any(ObjectNode.class), eq("OPENFOODFACTS"), eq("BARCODE"));
        verify(idem).attach(userId, requestId, "log-bc-success", NOW);
    }

    @Test
    void createBarcodeMvp_should_fail_and_release_idempotency_when_rate_limited() {
        Long userId = 1L;
        String requestId = "req-bc-rate-limited";
        String barcode = "4710018000017";

        RateLimitedException ex = new RateLimitedException("RATE_LIMITED", 60, "RETRY_LATER");

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        doThrow(ex).when(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);

        assertThatThrownBy(() ->
                service.createBarcodeMvp(
                        userId,
                        "Asia/Taipei",
                        "device-1",
                        barcode,
                        "zh-TW",
                        requestId
                )
        ).isSameAs(ex);

        verify(idem).failAndReleaseIfNeeded(
                userId,
                requestId,
                true
        );

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verifyNoInteractions(abuseGuard, barcodeLookupService, txTemplate);
    }

    @Test
    void createBarcodeMvp_should_persist_failed_when_lookup_runtime_exception_happens() {
        Long userId = 1L;
        String requestId = "req-bc-runtime";
        String barcode = "4710018000017";

        RuntimeException ex = new RuntimeException("lookup boom");
        AtomicReference<FoodLogEntity> savedRef = new AtomicReference<>();

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);
        when(barcodeLookupService.lookupOff(eq(barcode), anyString())).thenThrow(ex);

        mockTxTemplateExecutePassThrough();
        mockRepoSaveAssignId(savedRef, "log-bc-runtime");

        FoodLogEnvelope result = service.createBarcodeMvp(
                userId,
                "Asia/Taipei",
                "device-1",
                barcode,
                "zh-TW",
                requestId
        );

        assertThat(result).isNotNull();

        FoodLogEntity saved = savedRef.get();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo("log-bc-runtime");
        assertThat(saved.getMethod()).isEqualTo("BARCODE");
        assertThat(saved.getProvider()).isEqualTo("OPENFOODFACTS");
        assertThat(saved.getBarcode()).isEqualTo(barcode);
        assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_LOOKUP_FAILED");
        assertThat(saved.getLastErrorMessage()).contains("openfoodfacts unknown error");
        assertThat(saved.getLastErrorMessage()).contains("lookup boom");
        assertThat(saved.getEffective()).isNull();

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());
        verify(idem).attach(userId, requestId, "log-bc-runtime", NOW);

        // 這個 case 會被內層 catch(Exception) 轉成 FAILED envelope，不會走 outer catch
        verify(idem, never()).failAndReleaseIfNeeded(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void createBarcodeMvp_should_fail_and_release_idempotency_when_persist_failed_tx_runtime_exception_happens() {
        Long userId = 1L;
        String requestId = "req-bc-runtime-outer";
        String barcode = "4710018000017";

        RuntimeException ex = new RuntimeException("tx boom");

        when(idem.reserveOrGetExisting(userId, requestId, NOW)).thenReturn(null);
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        // 先讓流程走到 persistBarcodeFailedEnvelopeTx(...)
        when(barcodeLookupService.lookupOff(eq(barcode), anyString())).thenReturn(null);

        // 再讓 txTemplate.execute 爆掉，觸發 outer catch(RuntimeException)
        when(txTemplate.execute(any())).thenThrow(ex);

        assertThatThrownBy(() ->
                service.createBarcodeMvp(
                        userId,
                        "Asia/Taipei",
                        "device-1",
                        barcode,
                        "zh-TW",
                        requestId
                )
        ).isSameAs(ex);

        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onBarcodeAttempt(userId, "device-1", NOW, ZoneOffset.UTC);
        verify(barcodeLookupService).lookupOff(eq(barcode), anyString());

        verify(idem).failAndReleaseIfNeeded(
                userId,
                requestId,
                true
        );
    }

    @SuppressWarnings("unchecked")
    private void mockTxTemplateExecutePassThrough() {
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<Object> callback = (TransactionCallback<Object>) inv.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            return callback.doInTransaction(status);
        });
    }

    private void mockRepoSaveAssignId(AtomicReference<FoodLogEntity> savedRef, String id) {
        when(repo.save(any(FoodLogEntity.class))).thenAnswer(inv -> {
            FoodLogEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(id);
            }
            savedRef.set(e);
            return e;
        });
    }
}