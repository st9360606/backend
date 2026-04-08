package com.calai.backend.foodlog.service.barcode;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.barcode.normalize.BarcodeNormalizer;
import com.calai.backend.foodlog.barcode.normalize.BarcodeNutrientsNormalizer;
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
import com.calai.backend.foodlog.model.FoodLogMethod;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.UserDailyNutritionSummaryService;
import com.calai.backend.foodlog.service.query.FoodLogQueryService;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.calai.backend.foodlog.service.support.FoodLogRequestNormalizer;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;

@Service
@RequiredArgsConstructor
public class FoodLogBarcodeService {

    private final Clock clock;
    private final IdempotencyService idem;
    private final EntitlementService entitlementService;
    private final com.calai.backend.foodlog.service.limiter.UserRateLimiter rateLimiter;
    private final AbuseGuardService abuseGuard;
    private final BarcodeLookupService barcodeLookupService;
    private final TransactionTemplate txTemplate;
    private final FoodLogRepository repo;
    private final FoodLogEffectivePostProcessor postProcessor;
    private final FoodLogEnvelopeAssembler envelopeAssembler;
    private final FoodLogQueryService queryService;
    private final UserDailyNutritionSummaryService dailySummaryService;

    public FoodLogEnvelope createBarcodeMvp(
            Long userId,
            String clientTz,
            String deviceId,
            String barcode,
            String preferredLangTag,
            String requestId
    ) {
        Instant now = clock.instant();

        if (barcode == null || barcode.isBlank()) {
            throw new FoodLogAppException(FoodLogErrorCode.BARCODE_REQUIRED);
        }

        var bn = BarcodeNormalizer.normalizeOrThrow(barcode);
        String bcRaw = bn.rawInput();
        String bcNorm = bn.normalized();

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, now);
        if (existingLogId != null) {
            return queryService.getOne(userId, existingLogId, requestId);
        }

        try {
            ZoneId captureTz = FoodLogRequestNormalizer.parseClientTzOrUtc(clientTz);
            ZoneId quotaTz = FoodLogRequestNormalizer.resolveQuotaTz();
            String did = FoodLogRequestNormalizer.normalizeDeviceId(userId, deviceId);
            LocalDate localDate = ZonedDateTime.ofInstant(now, captureTz).toLocalDate();
            EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);

            rateLimiter.checkOrThrow(userId, tier, now);
            abuseGuard.onBarcodeAttempt(userId, did, now, quotaTz);

            String langKey = OpenFoodFactsLang.normalizeLangKey(preferredLangTag);

            BarcodeLookupService.LookupResult r;
            try {
                r = barcodeLookupService.lookupOff(bcRaw, langKey);
            } catch (OffHttpException ex) {
                boolean providerLimited = (ex.getStatus() == 429 || ex.getStatus() == 403);

                if (providerLimited) {
                    return persistBarcodeFailedEnvelopeTx(
                            userId, bcNorm, requestId, now, captureTz, localDate,
                            "PROVIDER_RATE_LIMITED",
                            "openfoodfacts rate-limited/banned risk. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                    );
                }

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts http error. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                );

            } catch (OffParseException ex) {
                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts parse error. code=" + ex.getCode() + ", msg=" + safeMsg(ex)
                );
            } catch (Exception ex) {
                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts unknown error: " + safeMsg(ex)
                );
            }

            if (r == null) {
                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "barcode lookup returned null result"
                );
            }

            if (!r.found() || r.off() == null) {
                String failRaw = firstNonBlank(r.barcodeRaw(), bcRaw);
                String failNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

                return persistBarcodeFailedEnvelopeTx(
                        userId, failNorm, requestId, now, captureTz, localDate,
                        "BARCODE_NOT_FOUND",
                        "openfoodfacts not found. raw=" + failRaw + ", norm=" + failNorm
                );
            }

            if (!OpenFoodFactsEffectiveBuilder.hasUsableNutrition(r.off())) {
                String failRaw = firstNonBlank(r.barcodeRaw(), bcRaw);
                String failNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

                return persistBarcodeFailedEnvelopeTx(
                        userId, failNorm, requestId, now, captureTz, localDate,
                        "BARCODE_NUTRITION_UNAVAILABLE",
                        "openfoodfacts found identity but no usable nutrition. raw=" + failRaw + ", norm=" + failNorm
                );
            }

            return persistBarcodeSuccessEnvelopeTx(
                    userId, bcRaw, bcNorm, requestId, now, captureTz, localDate, r, langKey
            );

        } catch (RateLimitedException ex) {
            idem.failAndReleaseIfNeeded(userId, requestId, true);
            throw ex;
        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(userId, requestId, true);
            throw ex;
        }
    }

    private FoodLogEnvelope persistBarcodeFailedEnvelopeTx(
            Long userId,
            String bc,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            String errorCode,
            String errorMsg
    ) {
        FoodLogEnvelope out = txTemplate.execute(status ->
                buildBarcodeFailedEnvelope(userId, bc, requestId, now, captureTz, localDate, errorCode, errorMsg)
        );
        if (out == null) {
            throw new IllegalStateException("BARCODE_TX_FAILED_EMPTY_RESULT");
        }
        return out;
    }

    private FoodLogEnvelope persistBarcodeSuccessEnvelopeTx(
            Long userId,
            String bcRaw,
            String bcNorm,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            BarcodeLookupService.LookupResult r,
            String langKey
    ) {
        FoodLogEnvelope out = txTemplate.execute(status -> {
            OffResult off = r.off();

            FoodLogEntity e = new FoodLogEntity();
            e.setUserId(userId);
            e.setMethod(FoodLogMethod.BARCODE.code());
            e.setProvider("OPENFOODFACTS");
            e.setDegradeLevel("DG-0");
            e.setCapturedAtUtc(now);
            e.setCapturedTz(captureTz.getId());
            e.setCapturedLocalDate(localDate);
            e.setServerReceivedAtUtc(now);
            e.setTimeSource(TimeSource.SERVER_RECEIVED);
            e.setTimeSuspect(false);

            String resolvedRaw = firstNonBlank(bcRaw, r.barcodeRaw());
            String resolvedNorm = firstNonBlank(r.barcodeNorm(), bcNorm);
            e.setBarcode(resolvedNorm);

            ObjectNode eff = JsonNodeFactory.instance.objectNode();

            String name = (off.productName() == null || off.productName().isBlank())
                    ? "Unknown product"
                    : off.productName();
            eff.put("foodName", name);

            String basis = OpenFoodFactsEffectiveBuilder.applyPortion(eff, off, true);
            BarcodePortionCanonicalizer.canonicalize(eff, off, basis);
            BarcodeNutrientsNormalizer.fillMissingWithZero(eff);
            eff.putArray("warnings");

            boolean hasCoreNutrition = OpenFoodFactsEffectiveBuilder.hasCoreNutrition(off);
            eff.put("confidence", hasCoreNutrition ? 0.98 : 0.92);

            if (!hasCoreNutrition) {
                eff.withArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
            }

            OpenFoodFactsCategoryResolver.Resolved resolvedCategory = OpenFoodFactsCategoryResolver.resolve(off);

            ObjectNode aiMeta = ensureObj(eff, "aiMeta");
            aiMeta.put("barcodeRaw", resolvedRaw);
            aiMeta.put("barcodeNorm", resolvedNorm);
            aiMeta.put("offFromCache", r.fromCache());
            aiMeta.put("source", "OPENFOODFACTS");
            aiMeta.put("basis", basis);
            aiMeta.put("lang", langKey);
            aiMeta.put("hasCoreNutrition", hasCoreNutrition);
            aiMeta.put("foodCategory", resolvedCategory.category().name());
            aiMeta.put("foodSubCategory", resolvedCategory.subCategory().name());

            ObjectNode processed = postProcessor.apply(eff, e.getProvider(), e.getMethod());
            e.setEffective(processed);

            e.setStatus(FoodLogStatus.DRAFT);
            e.setLastErrorCode(null);
            e.setLastErrorMessage(null);

            repo.save(e);
            idem.attach(userId, requestId, e.getId(), now);
            dailySummaryService.recomputeDay(userId, localDate);

            return envelopeAssembler.assemble(e, null, requestId);
        });

        if (out == null) {
            throw new IllegalStateException("BARCODE_TX_FAILED_EMPTY_RESULT");
        }
        return out;
    }

    private FoodLogEnvelope buildBarcodeFailedEnvelope(
            Long userId,
            String bc,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            String errorCode,
            String errorMsg
    ) {
        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setMethod(FoodLogMethod.BARCODE.code());
        e.setProvider("OPENFOODFACTS");
        e.setDegradeLevel("DG-0");
        e.setCapturedAtUtc(now);
        e.setCapturedTz(captureTz.getId());
        e.setCapturedLocalDate(localDate);
        e.setServerReceivedAtUtc(now);
        e.setTimeSource(TimeSource.SERVER_RECEIVED);
        e.setTimeSuspect(false);
        e.setBarcode(bc);
        e.setStatus(FoodLogStatus.FAILED);
        e.setEffective(null);
        e.setLastErrorCode(errorCode);
        e.setLastErrorMessage(errorMsg);

        repo.save(e);
        idem.attach(userId, requestId, e.getId(), now);
        return envelopeAssembler.assemble(e, null, requestId);
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
