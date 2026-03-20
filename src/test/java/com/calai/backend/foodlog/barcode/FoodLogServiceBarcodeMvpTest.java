package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.barcode.BarcodeNormalizer;
import com.calai.backend.foodlog.barcode.OpenFoodFactsLang;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.processing.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 單元測試目標：
 * 1. 不打外網，不真的呼叫 OpenFoodFacts
 * 2. 驗證 createBarcodeMvp() 的應用層流程
 * 3. 驗證 lang 傳遞、barcode normalize、成功/失敗寫入狀態
 */
@ExtendWith(MockitoExtension.class)
class FoodLogServiceBarcodeMvpTest {

    @Mock private ProviderClient providerClient;
    @Mock private FoodLogRepository repo;
    @Mock private FoodLogTaskRepository taskRepo;
    @Mock private StorageService storage;
    @Mock private QuotaService quota;
    @Mock private IdempotencyService idem;
    @Mock private ImageBlobService blobService;
    @Mock private UserInFlightLimiter inFlight;
    @Mock private UserRateLimiter rateLimiter;
    @Mock private FoodLogEffectivePostProcessor postProcessor;
    @Mock private ClientActionMapper clientActionMapper;
    @Mock private AbuseGuardService abuseGuard;
    @Mock private EntitlementService entitlementService;
    @Mock private BarcodeLookupService barcodeLookupService;
    @Mock private TransactionTemplate txTemplate;

    private FoodLogService service;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-03-20T08:00:00Z"), ZoneOffset.UTC);

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
                fixedClock,
                abuseGuard,
                entitlementService,
                barcodeLookupService,
                txTemplate
        );

        // idempotency：每次都視為新請求
        when(idem.reserveOrGetExisting(anyLong(), anyString(), any())).thenReturn(null);

        // tier / limiter / abuse guard：都放行
        when(entitlementService.resolveTier(anyLong(), any())).thenReturn(EntitlementService.Tier.NONE);
        doNothing().when(rateLimiter).checkOrThrow(anyLong(), any(), any());
        doNothing().when(abuseGuard).onBarcodeAttempt(anyLong(), anyString(), any(), any());

        // TransactionTemplate.execute(...)：直接同步執行 callback
        when(txTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            TransactionStatus txStatus = mock(TransactionStatus.class);
            return callback.doInTransaction(txStatus);
        });

        // repo.save(...)：模擬 DB 產生 id
        when(repo.save(any(FoodLogEntity.class))).thenAnswer(invocation -> {
            FoodLogEntity entity = invocation.getArgument(0);
            if (entity.getId() == null || entity.getId().isBlank()) {
                entity.setId(UUID.randomUUID().toString());
            }
            return entity;
        });

        doNothing().when(idem).attach(anyLong(), anyString(), anyString(), any());
    }

    @DisplayName("createBarcodeMvp()：多語系 barcode 成功流程")
    @ParameterizedTest(name = "[{index}] lang={0}, barcode={1}")
    @MethodSource("barcodeSamples")
    void createBarcodeMvp_should_create_draft_for_all_sample_barcodes(String langTag, String rawBarcode) {
        // arrange
        String normalizedBarcode = BarcodeNormalizer.normalizeOrThrow(rawBarcode).normalized();
        String normalizedLang = OpenFoodFactsLang.normalizeLangKey(langTag);

        OffResult off = sampleOffResult("Product-" + langTag);

        // 只有成功路徑才會用到 postProcessor，避免 strict stubbing 例外
        when(postProcessor.apply(any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(barcodeLookupService.lookupOff(eq(rawBarcode), eq(normalizedLang)))
                .thenReturn(new BarcodeLookupService.LookupResult(
                        rawBarcode,
                        normalizedBarcode,
                        true,
                        false,
                        "OPENFOODFACTS",
                        off
                ));

        // act
        FoodLogEnvelope result = service.createBarcodeMvp(
                1001L,
                "Asia/Taipei",
                "device-A",
                rawBarcode,
                langTag,
                "req-" + langTag
        );

        // assert - response
        assertNotNull(result);
        assertEquals("DRAFT", result.status());
        assertEquals("DG-0", result.degradeLevel());
        assertEquals("BARCODE", result.tierUsed());
        assertFalse(result.fromCache());

        assertNotNull(result.nutritionResult());
        assertEquals("Product-" + langTag, result.nutritionResult().foodName());
        assertNotNull(result.nutritionResult().nutrients());
        assertEquals("BARCODE", result.nutritionResult().source().method());
        assertEquals("OPENFOODFACTS", result.nutritionResult().source().provider());
        assertEquals("OPENFOODFACTS", result.nutritionResult().source().resolvedBy());

        // assert - dependency interaction
        verify(barcodeLookupService).lookupOff(eq(rawBarcode), eq(normalizedLang));
        verify(idem).attach(eq(1001L), eq("req-" + langTag), anyString(), any(Instant.class));

        ArgumentCaptor<FoodLogEntity> entityCaptor = ArgumentCaptor.forClass(FoodLogEntity.class);
        verify(repo).save(entityCaptor.capture());

        FoodLogEntity saved = entityCaptor.getValue();
        assertEquals("BARCODE", saved.getMethod());
        assertEquals("OPENFOODFACTS", saved.getProvider());
        assertEquals(normalizedBarcode, saved.getBarcode());
        assertEquals(FoodLogStatus.DRAFT, saved.getStatus());
        assertNotNull(saved.getEffective());
    }

    @Test
    @DisplayName("createBarcodeMvp()：查不到條碼時應建立 FAILED + BARCODE_NOT_FOUND")
    void createBarcodeMvp_should_create_failed_when_barcode_not_found() {
        // arrange
        String rawBarcode = "8901719134852";
        String langTag = "hi";
        String normalizedBarcode = BarcodeNormalizer.normalizeOrThrow(rawBarcode).normalized();
        String normalizedLang = OpenFoodFactsLang.normalizeLangKey(langTag);

        when(barcodeLookupService.lookupOff(eq(rawBarcode), eq(normalizedLang)))
                .thenReturn(new BarcodeLookupService.LookupResult(
                        rawBarcode,
                        normalizedBarcode,
                        false,
                        false,
                        "OPENFOODFACTS",
                        null
                ));

        // act
        FoodLogEnvelope result = service.createBarcodeMvp(
                1001L,
                "Asia/Taipei",
                "device-A",
                rawBarcode,
                langTag,
                "req-not-found"
        );

        // assert
        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertNotNull(result.error());
        assertEquals("BARCODE_NOT_FOUND", result.error().errorCode());

        ArgumentCaptor<FoodLogEntity> entityCaptor = ArgumentCaptor.forClass(FoodLogEntity.class);
        verify(repo).save(entityCaptor.capture());

        FoodLogEntity saved = entityCaptor.getValue();
        assertEquals(FoodLogStatus.FAILED, saved.getStatus());
        assertEquals("BARCODE_NOT_FOUND", saved.getLastErrorCode());
        assertEquals(normalizedBarcode, saved.getBarcode());
    }

    /**
     * 給 BARCODE 成功路徑用的 OFF 假資料。
     * 重點是：
     * - 要有 usable nutrition，否則 createBarcodeMvp 會走失敗分支
     * - package size 給 100g，讓 portion/basis 比較穩定
     */
    private static OffResult sampleOffResult(String productName) {
        return new OffResult(
                productName,

                // per 100g/ml
                500.0,   // kcalPer100g
                8.0,     // proteinPer100g
                24.0,    // fatPer100g
                60.0,    // carbsPer100g
                3.0,     // fiberPer100g
                20.0,    // sugarPer100g
                450.0,   // sodiumMgPer100g

                // per serving
                250.0,   // kcalPerServing
                4.0,     // proteinPerServing
                12.0,    // fatPerServing
                30.0,    // carbsPerServing
                1.5,     // fiberPerServing
                10.0,    // sugarPerServing
                225.0,   // sodiumMgPerServing

                // package size
                100.0,
                "g",

                // categories
                List.of("snacks", "biscuits")
        );
    }

    /**
     * 這裡直接使用你目前整理出來的測試樣本。
     * 注意：這個 UnitTest 不驗證「真實 OFF 查得到」，只驗證「你的服務流程正確」。
     */
    static Stream<Arguments> barcodeSamples() {
        return Stream.of(
                Arguments.of("en", "0038000138416"),
                Arguments.of("es", "8424644007065"),
                Arguments.of("ar", "6281007040235"),
                Arguments.of("ru", "4600300075409"),
                Arguments.of("fr", "7622210476104"),
                Arguments.of("de", "4008400402222"),
                Arguments.of("ja", "4902102084178"),
                Arguments.of("ko", "8801073140578"),
                Arguments.of("vi", "8934680025980"),
                Arguments.of("th", "8851876201303"),
                Arguments.of("ms", "9557062331128"),
                Arguments.of("zh-TW", "4710008211020"),
                Arguments.of("zh-CN", "6920152400777"),
                Arguments.of("it", "8006434558003"),
                Arguments.of("nl", "8710615077213"),
                Arguments.of("sv", "7318690166771"),
                Arguments.of("da", "5775345204226"),
                Arguments.of("nb", "7037610035033"),
                Arguments.of("he", "7290105362377"),
                Arguments.of("tr", "8690504019503"),
                Arguments.of("pl", "5900783008628"),
                Arguments.of("zh-HK", "4891028664840"),
                Arguments.of("fil", "4807770270291"),
                Arguments.of("pt-BR", "7891910000197"),
                Arguments.of("pt-PT", "5601761611075"),
                Arguments.of("fi", "6408432000478"),
                Arguments.of("ro", "5941006101566"),
                Arguments.of("cs", "8593894001151"),
                Arguments.of("hi", "8901719134852"),
                Arguments.of("jv", "8998009010231")
        );
    }
}
