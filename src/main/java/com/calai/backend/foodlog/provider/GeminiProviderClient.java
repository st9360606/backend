package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    /** ✅ 依你要求：UNKNOWN_FOOD repair 只重打 1 次（同一次 task 最多 2 次呼叫） */
    private static final int MAX_UNKNOWN_FOOD_REPAIR_CALLS = 1;

    private static final String USER_PROMPT_MAIN =
            "You are a food nutrition estimation engine. "
            + "Analyze the image and return JSON strictly matching the schema. "
            + "Always output ALL required keys. If unknown, set null. "
            + "If the image contains a food item but you're uncertain, still ESTIMATE nutrition using typical values "
            + "(e.g., common food database / typical raw ingredient values). "
            + "If no food is detected, set warnings include NO_FOOD_DETECTED and set confidence <= 0.2. "
            + "Output sodium in mg, macros in g.";

    private static final String USER_PROMPT_REPAIR =
            "Your previous response may have been truncated or invalid JSON. "
            + "Re-run the analysis and output FULL valid JSON matching the schema. "
            + "Return ONLY JSON. No markdown. No extra text. "
            + "Always output ALL required keys. If unknown, set null. "
            + "If the image contains food, ESTIMATE nutrition using typical values. "
            + "Do not stop early.";

    private static final int PREVIEW_LEN = 200;

    private final RestClient http;
    private final GeminiProperties props;
    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;

    public GeminiProviderClient(RestClient http, GeminiProperties props, ObjectMapper om, ProviderTelemetry telemetry) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.telemetry = telemetry;
    }

    @Override
    public String providerCode() {
        return "GEMINI";
    }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        long t0 = System.nanoTime();

        try {
            byte[] bytes;
            try (InputStream in = storage.open(entity.getImageObjectKey()).inputStream()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length == 0) throw new IllegalStateException("EMPTY_IMAGE");

            String mime = (entity.getImageContentType() == null || entity.getImageContentType().isBlank())
                    ? "image/jpeg"
                    : entity.getImageContentType();

            // ===== 1) main call =====
            CallResult r1 = callAndExtract(bytes, mime, USER_PROMPT_MAIN, entity.getId());
            Tok tok = r1.tok;

            JsonNode parsed1 = tryParseJson(r1.text);

            // ✅ 若 parse 成功但 nutrients 全 null → 視為 UNKNOWN_FOOD（走 repair）
            if (parsed1 != null && !isAllNutrientsNull(parsed1)) {
                ObjectNode effective = normalizeToEffective(parsed1);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ no-food：永遠不重打
            if (isNoFoodLikely(r1.text)) {
                ObjectNode fb = fallbackNoFoodDetected();
                log.warn("geminiDegraded foodLogId={} type=NO_FOOD noRetry=true", entity.getId());

                ObjectNode effective = normalizeToEffective(fb);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ===== 2) unknown-food repair call (最多 1 次) =====
            String lastText = r1.text;

            for (int i = 1; i <= MAX_UNKNOWN_FOOD_REPAIR_CALLS; i++) {
                CallResult rn = callAndExtract(bytes, mime, USER_PROMPT_REPAIR, entity.getId());
                tok = Tok.mergePreferNew(tok, rn.tok);
                lastText = rn.text;

                JsonNode parsedN = tryParseJson(rn.text);
                if (parsedN != null && !isAllNutrientsNull(parsedN)) {
                    ObjectNode effective = normalizeToEffective(parsedN);
                    telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                // repair 過程中如果變 no-food：立刻停手
                if (isNoFoodLikely(rn.text)) {
                    ObjectNode fb = fallbackNoFoodDetected();
                    log.warn("geminiDegraded foodLogId={} type=NO_FOOD noRetry=true afterRepairAttempt={}",
                            entity.getId(), i);

                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                log.debug("geminiRepairAttempt foodLogId={} attempt={}/{} parseFailed",
                        entity.getId(), i, MAX_UNKNOWN_FOOD_REPAIR_CALLS);
            }

            // ✅ 最終 fallback UNKNOWN_FOOD（避免 worker 再 retry 燒錢）
            ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(lastText);
            log.warn("geminiDegraded foodLogId={} type=UNKNOWN_FOOD afterRepairCalls={}",
                    entity.getId(), MAX_UNKNOWN_FOOD_REPAIR_CALLS);

            ObjectNode effective = normalizeToEffective(fbUnknown);
            telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
            return new ProviderResult(effective, "GEMINI");

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private String requireApiKey() {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        return k.trim();
    }

    private CallResult callAndExtract(byte[] imageBytes, String mimeType, String userPrompt, String foodLogIdForLog) {
        JsonNode resp = callGenerateContent(imageBytes, mimeType, userPrompt);
        Tok tok = extractUsage(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) text = "";

        log.debug("geminiTextPreview foodLogId={} preview={}", foodLogIdForLog, safeOneLine200(text));
        return new CallResult(tok, text);
    }

    private JsonNode callGenerateContent(byte[] imageBytes, String mimeType, String userPrompt) {
        ObjectNode req = buildRequest(imageBytes, mimeType, userPrompt);

        return http.post()
                .uri("/v1beta/models/{model}:generateContent", props.getModel())
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * ✅ 穩定版：只送官方常見欄位（inlineData / mimeType）
     * 先不加 mediaResolution，避免 provider 因未知欄位回空或被忽略 structured output。
     */
    private ObjectNode buildRequest(byte[] imageBytes, String mimeType, String userPrompt) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text",
                "Return ONLY JSON that matches the schema. No markdown. No extra text."
        );

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        ArrayNode parts = c0.putArray("parts");

        // text part
        parts.addObject().put("text", userPrompt);

        // image part (camelCase)
        ObjectNode imgPart = parts.addObject();
        ObjectNode inline = imgPart.putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", b64);

        // generation config
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.set("responseJsonSchema", nutritionJsonSchema());
        gen.put("maxOutputTokens", props.getMaxOutputTokens());
        gen.put("temperature", props.getTemperature());

        return root;
    }

    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        // foodName
        ObjectNode foodName = propsNode.putObject("foodName");
        foodName.putArray("type").add("string").add("null");
        foodName.put("maxLength", 80);

        // quantity
        ObjectNode quantity = propsNode.putObject("quantity");
        quantity.put("type", "object");
        quantity.put("additionalProperties", false);

        ObjectNode qProps = quantity.putObject("properties");
        qProps.putObject("value").putArray("type").add("number").add("null");

        ObjectNode unit = qProps.putObject("unit");
        unit.putArray("type").add("string").add("null");
        unit.putArray("enum").add("SERVING").add("GRAM").add("ML");

        quantity.putArray("required").add("value").add("unit");

        // nutrients
        ObjectNode nutrients = propsNode.putObject("nutrients");
        nutrients.put("type", "object");
        nutrients.put("additionalProperties", false);

        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            nProps.putObject(k).putArray("type").add("number").add("null");
            nReq.add(k);
        }

        // confidence
        propsNode.putObject("confidence").putArray("type").add("number").add("null");

        // warnings
        ObjectNode warnings = propsNode.putObject("warnings");
        warnings.put("type", "array");
        ObjectNode items = warnings.putObject("items");
        items.put("type", "string");
        ArrayNode wEnum = items.putArray("enum");
        wEnum.add("NON_FOOD_SUSPECT");
        wEnum.add("NO_FOOD_DETECTED");
        wEnum.add("LOW_CONFIDENCE");
        wEnum.add("MIXED_MEAL");
        wEnum.add("BLURRY_IMAGE");
        wEnum.add("UNKNOWN_FOOD");

        schema.putArray("required").add("foodName").add("quantity").add("nutrients").add("confidence");
        return schema;
    }

    private static String extractJoinedTextOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) return null;

        JsonNode cand0 = resp.path("candidates").path(0);
        JsonNode parts = cand0.path("content").path("parts");
        if (!parts.isArray()) return null;

        StringBuilder sb = new StringBuilder(256);
        for (JsonNode p : parts) {
            String t = p.path("text").asText(null);
            if (t != null) sb.append(t);
        }
        String joined = sb.toString().trim();
        return joined.isEmpty() ? null : joined;
    }

    private JsonNode tryParseJson(String rawText) {
        if (rawText == null) return null;

        String s = stripFenceAndExtractObject(rawText.trim());
        if (s.isBlank() || !s.contains("{")) return null;

        s = stripBomAndNulls(s);
        s = escapeBadCharsInsideJsonStrings(s);

        try {
            return om.readTree(s);
        } catch (Exception first) {
            String fixed = balanceBracesIfNeeded(s);
            try {
                return om.readTree(fixed);
            } catch (Exception second) {
                return null;
            }
        }
    }

    private static String stripFenceAndExtractObject(String s) {
        if (s == null) return "";
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int firstNl = s.indexOf('\n', first);
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
            s = s.trim();
        }
        int l = s.indexOf('{');
        if (l < 0) return "";
        s = s.substring(l).trim();

        int r = s.lastIndexOf('}');
        if (r >= 0) s = s.substring(0, r + 1).trim();
        return s;
    }

    private static String balanceBracesIfNeeded(String s) {
        if (s == null || s.isBlank()) return s;

        boolean inString = false, escaped = false;
        int balance = 0;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { if (inString) escaped = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (ch == '{') balance++;
            else if (ch == '}') balance--;
        }

        if (balance <= 0) return s;
        return s + "}".repeat(balance);
    }

    private static boolean isNoFoodLikely(String rawText) {
        if (rawText == null) return false;
        String lower = rawText.toLowerCase(Locale.ROOT);

        return lower.contains("no food")
               || lower.contains("no meal")
               || lower.contains("not food")
               || lower.contains("undetected")
               || lower.contains("cannot identify")
               || lower.contains("unable to")
               || lower.contains("\"foodname\":null")
               || lower.contains("\"foodname\" : null");
    }

    private ObjectNode fallbackNoFoodDetected() {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        root.put("confidence", 0.1);
        root.putArray("warnings").add(FoodLogWarning.NO_FOOD_DETECTED.name());
        return root;
    }

    private ObjectNode fallbackUnknownFoodFromBrokenText(String rawText) {
        String name = extractFoodNameFromBrokenText(rawText);

        ObjectNode root = om.createObjectNode();
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        root.put("confidence", 0.2);

        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.UNKNOWN_FOOD.name());
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        return root;
    }

    private static String extractFoodNameFromBrokenText(String raw) {
        if (raw == null) return null;
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("\"foodname\"");
        if (idx < 0) return null;

        int colon = raw.indexOf(':', idx);
        if (colon < 0) return null;

        int q1 = raw.indexOf('"', colon + 1);
        if (q1 < 0) return null;

        int q2 = raw.indexOf('"', q1 + 1);
        String out = (q2 < 0) ? raw.substring(q1 + 1) : raw.substring(q1 + 1, q2);

        out = out.replace("\r", " ").replace("\n", " ").trim();
        if (out.length() > 80) out = out.substring(0, 80);
        return out.isBlank() ? null : out;
    }

    private Tok extractUsage(JsonNode resp) {
        Integer p = null, c = null, t = null;
        JsonNode usage = (resp == null) ? null : resp.path("usageMetadata");
        if (usage != null && !usage.isMissingNode() && !usage.isNull()) {
            p = usage.path("promptTokenCount").isInt() ? usage.path("promptTokenCount").asInt() : null;
            c = usage.path("candidatesTokenCount").isInt() ? usage.path("candidatesTokenCount").asInt() : null;
            t = usage.path("totalTokenCount").isInt() ? usage.path("totalTokenCount").asInt() : null;
        }
        return new Tok(p, c, t);
    }

    private static boolean isAllNutrientsNull(JsonNode root) {
        if (root == null || !root.isObject()) return true;
        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) return false;
        }
        return true;
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode out = JsonNodeFactory.instance.objectNode();

        JsonNode foodNameNode = raw.get("foodName");
        if (foodNameNode != null && !foodNameNode.isNull()) {
            String foodName = foodNameNode.asText(null);
            if (foodName != null && !foodName.isBlank()) out.put("foodName", foodName);
        }

        // quantity（保底）
        ObjectNode oq = out.putObject("quantity");
        JsonNode q = raw.get("quantity");
        if (q != null && q.isObject()) {
            JsonNode vv = q.get("value");
            JsonNode uu = q.get("unit");

            if (vv == null || vv.isNull()) oq.put("value", 1d);
            else {
                if (!vv.isNumber() || vv.asDouble() < 0) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
                oq.put("value", vv.asDouble());
            }

            String unit = (uu == null || uu.isNull()) ? "SERVING" : uu.asText("SERVING");
            if (unit == null || unit.isBlank()) unit = "SERVING";
            oq.put("unit", unit);
        } else {
            oq.put("value", 1d);
            oq.put("unit", "SERVING");
        }

        JsonNode n = raw.get("nutrients");
        if (n == null || !n.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode on = out.putObject("nutrients");
        copyNonNegativeNumberOrNull(n, on, "kcal");
        copyNonNegativeNumberOrNull(n, on, "protein");
        copyNonNegativeNumberOrNull(n, on, "fat");
        copyNonNegativeNumberOrNull(n, on, "carbs");
        copyNonNegativeNumberOrNull(n, on, "fiber");
        copyNonNegativeNumberOrNull(n, on, "sugar");
        copyNonNegativeNumberOrNull(n, on, "sodium");

        JsonNode conf = raw.get("confidence");
        if (conf == null || conf.isNull()) out.putNull("confidence");
        else {
            if (!conf.isNumber()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            double c = conf.asDouble();
            if (c < 0 || c > 1) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            out.put("confidence", c);
        }

        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            ArrayNode outW = JsonNodeFactory.instance.arrayNode();
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) outW.add(ww.name());
            }
            if (!outW.isEmpty()) out.set("warnings", outW);
        }

        return out;
    }

    private static void copyNonNegativeNumberOrNull(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) {
            to.putNull(key);
            return;
        }
        if (!v.isNumber()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        double d = v.asDouble();
        if (d < 0) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        to.put(key, d);
    }

    private static String safeOneLine200(String s) {
        if (s == null) return null;
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        return (t.length() > PREVIEW_LEN) ? t.substring(0, PREVIEW_LEN) : t;
    }

    private static String stripBomAndNulls(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s.replace("\u0000", "");
    }

    private static String escapeBadCharsInsideJsonStrings(String s) {
        if (s == null || s.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (!inString) {
                if (ch == '"') inString = true;
                if (ch == '\u0000') continue;
                if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') continue;
                out.append(ch);
                continue;
            }

            if (escaped) { out.append(ch); escaped = false; continue; }
            if (ch == '\\') { out.append(ch); escaped = true; continue; }
            if (ch == '"') { out.append(ch); inString = false; continue; }

            if (ch == '\r' || ch == '\n') { out.append("\\n"); continue; }
            if (ch < 0x20) { out.append(' '); continue; }

            out.append(ch);
        }

        return out.toString();
    }

    private record CallResult(Tok tok, String text) {}

    private record Tok(Integer promptTok, Integer candTok, Integer totalTok) {
        static Tok mergePreferNew(Tok oldTok, Tok newTok) {
            if (newTok == null) return oldTok;
            return new Tok(
                    newTok.promptTok != null ? newTok.promptTok : oldTok.promptTok,
                    newTok.candTok != null ? newTok.candTok : oldTok.candTok,
                    newTok.totalTok != null ? newTok.totalTok : oldTok.totalTok
            );
        }
    }
}
