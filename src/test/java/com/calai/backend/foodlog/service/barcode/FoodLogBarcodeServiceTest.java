package com.calai.backend.foodlog.service.barcode;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.barcode.normalize.BarcodePortionCanonicalizer;
import com.calai.backend.foodlog.barcode.openfoodfacts.OpenFoodFactsLang;
import com.calai.backend.foodlog.barcode.openfoodfacts.error.OffHttpException;
import com.calai.backend.foodlog.barcode.openfoodfacts.error.OffParseException;
import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.barcode.openfoodfacts.support.OpenFoodFactsCategoryResolver;
import com.calai.backend.foodlog.barcode.openfoodfacts.support.OpenFoodFactsEffectiveBuilder;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.UserDailyNutritionSummaryService;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.query.FoodLogQueryService;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FoodLogBarcodeService 單元測試
 * 測試重點：
 * 1. idempotency hit
 * 2. BARCODE_REQUIRED
 * 3. rate limit -> failAndReleaseIfNeeded
 * 4. provider limited / lookup failed / not found / nutrition unavailable
 * 5. success path
 */
@ExtendWith(MockitoExtension.class)
class FoodLogBarcodeServiceTest {

    private static final Long USER_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-03-21T08:00:00Z");
    private static final String REQUEST_ID = "RID-BAR-001";
    private static final String BARCODE = "4901234567894";
    private static final String CLIENT_TZ = "Asia/Taipei";
    private static final String DEVICE_ID = "device-001";
    private static final String LANG = "zh-TW";
    private static final LocalDate LOCAL_DATE_TAIPEI = LocalDate.of(2026, 3, 21);

    @Mock private IdempotencyService idem;
    @Mock private EntitlementService entitlementService;
    @Mock private UserRateLimiter rateLimiter;
    @Mock private AbuseGuardService abuseGuard;
    @Mock private BarcodeLookupService barcodeLookupService;
    @Mock private TransactionTemplate txTemplate;
    @Mock private FoodLogRepository repo;
    @Mock private FoodLogEffectivePostProcessor postProcessor;
    @Mock private FoodLogEnvelopeAssembler envelopeAssembler;
    @Mock private FoodLogQueryService queryService;
    @Mock private UserDailyNutritionSummaryService dailySummaryService;

    private FoodLogBarcodeService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new FoodLogBarcodeService(
                fixedClock,
                idem,
                entitlementService,
                rateLimiter,
                abuseGuard,
                barcodeLookupService,
                txTemplate,
                repo,
                postProcessor,
                envelopeAssembler,
                queryService,
                dailySummaryService
        );
    }

    @Nested
    @DisplayName("createBarcodeMvp")
    class CreateBarcodeMvpTests {

        @Test
        @DisplayName("barcode 為 null 或 blank 應拋 BARCODE_REQUIRED")
        void should_throw_when_barcode_missing() {
            assertThatThrownBy(() ->
                    service.createBarcodeMvp(USER_ID, CLIENT_TZ, DEVICE_ID, "   ", LANG, REQUEST_ID)
            )
                    .isInstanceOfSatisfying(FoodLogAppException.class, ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo(FoodLogErrorCode.BARCODE_REQUIRED);
                        assertThat(ex.code()).isEqualTo("BARCODE_REQUIRED");
                        assertThat(ex.getMessage()).isEqualTo("BARCODE_REQUIRED");
                    });

            verifyNoInteractions(idem, queryService, barcodeLookupService, repo, txTemplate, dailySummaryService);
        }

        @Test
        @DisplayName("idempotency 命中時應直接 queryService.getOne")
        void should_return_existing_when_idempotency_hit() {
            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn("log-123");
            when(queryService.getOne(USER_ID, "log-123", REQUEST_ID)).thenReturn(expected);

            FoodLogEnvelope actual = service.createBarcodeMvp(
                    USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
            );

            assertThat(actual).isSameAs(expected);

            verify(queryService).getOne(USER_ID, "log-123", REQUEST_ID);
            verifyNoInteractions(barcodeLookupService, repo, txTemplate, dailySummaryService);
        }

        @Test
        @DisplayName("rateLimiter 丟 RateLimitedException 時應 release idempotency 並往外拋")
        void should_release_idem_when_rate_limited() {
            RateLimitedException ex = mock(RateLimitedException.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            doThrow(ex).when(rateLimiter).checkOrThrow(USER_ID, null, NOW);

            assertThatThrownBy(() ->
                    service.createBarcodeMvp(USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID)
            ).isSameAs(ex);

            verify(idem).failAndReleaseIfNeeded(USER_ID, REQUEST_ID, true);
            verifyNoInteractions(barcodeLookupService, repo, txTemplate, dailySummaryService);
        }

        @Test
        @DisplayName("OFF 429/403 應轉成 PROVIDER_RATE_LIMITED failed envelope")
        void should_persist_failed_when_provider_rate_limited() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);
            OffHttpException ex = mock(OffHttpException.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(ex.getStatus()).thenReturn(429);
            when(ex.getMessage()).thenReturn("too many requests");
            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenThrow(ex);
            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            FoodLogEnvelope actual = service.createBarcodeMvp(
                    USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
            );

            assertThat(actual).isSameAs(expected);

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());
            FoodLogEntity saved = captor.getValue();

            assertThat(saved.getMethod()).isEqualTo("BARCODE");
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
            assertThat(saved.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
            assertThat(saved.getBarcode()).isEqualTo(BARCODE);

            verify(idem).attach(USER_ID, REQUEST_ID, saved.getId(), NOW);
            verifyNoInteractions(dailySummaryService);
        }

        @Test
        @DisplayName("lookup 回 null 時應轉成 BARCODE_LOOKUP_FAILED")
        void should_persist_failed_when_lookup_result_is_null() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenReturn(null);
            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            FoodLogEnvelope actual = service.createBarcodeMvp(
                    USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
            );

            assertThat(actual).isSameAs(expected);

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());

            FoodLogEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
            assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_LOOKUP_FAILED");
            assertThat(saved.getLastErrorMessage()).contains("returned null result");

            verifyNoInteractions(dailySummaryService);
        }

        @Test
        @DisplayName("找不到商品時應轉成 BARCODE_NOT_FOUND")
        void should_persist_failed_when_barcode_not_found() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);
            BarcodeLookupService.LookupResult lookup = mock(BarcodeLookupService.LookupResult.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenReturn(lookup);

            when(lookup.found()).thenReturn(false);
            when(lookup.barcodeRaw()).thenReturn(BARCODE);
            when(lookup.barcodeNorm()).thenReturn(BARCODE);

            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            FoodLogEnvelope actual = service.createBarcodeMvp(
                    USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
            );

            assertThat(actual).isSameAs(expected);

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());

            FoodLogEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
            assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_NOT_FOUND");
            assertThat(saved.getBarcode()).isEqualTo(BARCODE);

            verifyNoInteractions(dailySummaryService);
        }

        @Test
        @DisplayName("找到商品但無 usable nutrition 時應轉成 BARCODE_NUTRITION_UNAVAILABLE")
        void should_persist_failed_when_nutrition_unavailable() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);
            BarcodeLookupService.LookupResult lookup = mock(BarcodeLookupService.LookupResult.class);
            OffResult off = mock(OffResult.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenReturn(lookup);

            when(lookup.found()).thenReturn(true);
            when(lookup.off()).thenReturn(off);
            when(lookup.barcodeRaw()).thenReturn(BARCODE);
            when(lookup.barcodeNorm()).thenReturn(BARCODE);

            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            try (MockedStatic<OpenFoodFactsEffectiveBuilder> builderMock =
                         Mockito.mockStatic(OpenFoodFactsEffectiveBuilder.class)) {

                builderMock.when(() -> OpenFoodFactsEffectiveBuilder.hasUsableNutrition(off))
                        .thenReturn(false);

                FoodLogEnvelope actual = service.createBarcodeMvp(
                        USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
                );

                assertThat(actual).isSameAs(expected);
            }

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());

            FoodLogEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
            assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_NUTRITION_UNAVAILABLE");

            verifyNoInteractions(dailySummaryService);
        }

        @Test
        @DisplayName("成功路徑應儲存 DRAFT 並 attach idempotency")
        void should_persist_draft_when_lookup_success() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);
            BarcodeLookupService.LookupResult lookup = mock(BarcodeLookupService.LookupResult.class);
            OffResult off = mock(OffResult.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenReturn(lookup);

            when(lookup.found()).thenReturn(true);
            when(lookup.off()).thenReturn(off);
            when(lookup.barcodeRaw()).thenReturn(BARCODE);
            when(lookup.barcodeNorm()).thenReturn(BARCODE);
            when(lookup.fromCache()).thenReturn(true);

            when(off.productName()).thenReturn("Test Product");

            when(postProcessor.apply(any(ObjectNode.class), eq("OPENFOODFACTS"), eq("BARCODE")))
                    .thenAnswer(inv -> inv.getArgument(0));

            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            try (MockedStatic<OpenFoodFactsEffectiveBuilder> builderMock =
                         Mockito.mockStatic(OpenFoodFactsEffectiveBuilder.class);
                 MockedStatic<BarcodePortionCanonicalizer> portionMock =
                         Mockito.mockStatic(BarcodePortionCanonicalizer.class);
                 MockedStatic<OpenFoodFactsCategoryResolver> categoryMock =
                         Mockito.mockStatic(OpenFoodFactsCategoryResolver.class)) {

                builderMock.when(() -> OpenFoodFactsEffectiveBuilder.hasUsableNutrition(off))
                        .thenReturn(true);
                builderMock.when(() -> OpenFoodFactsEffectiveBuilder.hasCoreNutrition(off))
                        .thenReturn(true);
                builderMock.when(() -> OpenFoodFactsEffectiveBuilder.applyPortion(
                                any(ObjectNode.class), same(off), eq(true)))
                        .thenReturn("WHOLE_PACKAGE");

                portionMock.when(() -> BarcodePortionCanonicalizer.canonicalize(
                                any(ObjectNode.class), same(off), anyString()))
                        .thenAnswer(inv -> null);

                categoryMock.when(() -> OpenFoodFactsCategoryResolver.resolve(off))
                        .thenReturn(newResolvedWithFirstEnums());

                FoodLogEnvelope actual = service.createBarcodeMvp(
                        USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
                );

                assertThat(actual).isSameAs(expected);
            }

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());

            FoodLogEntity saved = captor.getValue();
            assertThat(saved.getMethod()).isEqualTo("BARCODE");
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.DRAFT);
            assertThat(saved.getProvider()).isEqualTo("OPENFOODFACTS");
            assertThat(saved.getBarcode()).isEqualTo(BARCODE);
            assertThat(saved.getEffective()).isNotNull();
            assertThat(saved.getEffective().path("foodName").asText()).isEqualTo("Test Product");
            assertThat(saved.getEffective().path("aiMeta").path("source").asText()).isEqualTo("OPENFOODFACTS");

            verify(idem).attach(USER_ID, REQUEST_ID, saved.getId(), NOW);
            verify(dailySummaryService).recomputeDay(USER_ID, LOCAL_DATE_TAIPEI);
        }

        @Test
        @DisplayName("OffParseException 應轉成 BARCODE_LOOKUP_FAILED")
        void should_persist_failed_when_parse_error() {
            stubTxExecuteInline();

            FoodLogEnvelope expected = mock(FoodLogEnvelope.class);
            OffParseException ex = mock(OffParseException.class);

            when(idem.reserveOrGetExisting(USER_ID, REQUEST_ID, NOW)).thenReturn(null);
            when(ex.getCode()).thenReturn("OFF_PARSE_ERR");
            when(ex.getMessage()).thenReturn("bad payload");

            when(barcodeLookupService.lookupOff(BARCODE, OpenFoodFactsLang.normalizeLangKey(LANG)))
                    .thenThrow(ex);

            when(envelopeAssembler.assemble(any(FoodLogEntity.class), isNull(), eq(REQUEST_ID)))
                    .thenReturn(expected);

            FoodLogEnvelope actual = service.createBarcodeMvp(
                    USER_ID, CLIENT_TZ, DEVICE_ID, BARCODE, LANG, REQUEST_ID
            );

            assertThat(actual).isSameAs(expected);

            ArgumentCaptor<FoodLogEntity> captor = ArgumentCaptor.forClass(FoodLogEntity.class);
            verify(repo).save(captor.capture());

            FoodLogEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(FoodLogStatus.FAILED);
            assertThat(saved.getLastErrorCode()).isEqualTo("BARCODE_LOOKUP_FAILED");
            assertThat(saved.getLastErrorMessage()).contains("parse error");

            verifyNoInteractions(dailySummaryService);
        }
    }

    /**
     * 只在「會走 persistBarcodeFailedEnvelopeTx / persistBarcodeSuccessEnvelopeTx」的測試中使用。
     */
    private void stubTxExecuteInline() {
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> cb = (TransactionCallback<Object>) inv.getArgument(0);
            return cb.doInTransaction(new SimpleTransactionStatus());
        });
    }

    /**
     * 不直接依賴你 category / subCategory enum 的名字，
     * 直接用 record constructor + enum 第一個常數建一個 Resolved。
     */
    private OpenFoodFactsCategoryResolver.Resolved newResolvedWithFirstEnums() {
        try {
            var ctor = OpenFoodFactsCategoryResolver.Resolved.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);

            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object category = paramTypes[0].getEnumConstants()[0];
            Object subCategory = paramTypes[1].getEnumConstants()[0];

            return (OpenFoodFactsCategoryResolver.Resolved) ctor.newInstance(category, subCategory);
        } catch (Exception ex) {
            throw new RuntimeException("Unable to build Resolved for test", ex);
        }
    }
}
