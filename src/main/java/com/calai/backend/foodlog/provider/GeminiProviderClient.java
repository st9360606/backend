package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.service.HealthScoreService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.Base64;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    private final RestClient http;
    private final GeminiProperties props;
    private final HealthScoreService healthScore;
    private final ObjectMapper om;

    public GeminiProviderClient(RestClient http, GeminiProperties props, HealthScoreService healthScore, ObjectMapper om) {
        this.http = http;
        this.props = props;
        this.healthScore = healthScore;
        this.om = om;
    }

    @Override
    public String providerCode() {
        return "GEMINI";
    }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        byte[] bytes;
        try (InputStream in = storage.open(entity.getImageObjectKey()).inputStream()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) throw new IllegalStateException("EMPTY_IMAGE");

        String mime = (entity.getImageContentType() == null || entity.getImageContentType().isBlank())
                ? "image/jpeg"
                : entity.getImageContentType();

        ObjectNode req = buildRequest(bytes, mime);

        JsonNode resp = http.post()
                .uri("/v1beta/models/{model}:generateContent", props.getModel())
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);

        String text = extractTextOrThrow(resp);

        // ✅ 只印前 200 字（你要看完整可把 200 調大）
        String preview = safeOneLine(text, 200);
        GeminiProviderClient.log.info("geminiTextPreview foodLogId={} preview={}", entity.getId(), preview);

        JsonNode parsed = parseJsonStrictOrThrow(text);

        ObjectNode effective = normalizeToEffective(parsed);

        // ✅ healthScore：先用後端 v0 規則（Step 3 再版本化）
        ObjectNode nutrients = getOrCreateObject(effective, "nutrients");
        Double fiber = doubleOrNull(nutrients.get("fiber"));
        Double sugar = doubleOrNull(nutrients.get("sugar"));
        Double sodium = doubleOrNull(nutrients.get("sodium"));
        Integer hs = healthScore.score(fiber, sugar, sodium);
        if (hs != null) effective.put("healthScore", hs);

        return new ProviderResult(effective, "GEMINI");
    }

    private String requireApiKey() {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        return k.trim();
    }

    private ObjectNode buildRequest(byte[] imageBytes, String mimeType) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode root = om.createObjectNode();

        // systemInstruction（降低 prompt injection + 要求單行 JSON）
        ObjectNode sys = root.putObject("systemInstruction");
        ArrayNode sysParts = sys.putArray("parts");
        sysParts.addObject().put("text",
                "You are a food nutrition estimation engine. " +
                "Return ONLY a single-line JSON object that matches the provided JSON Schema. " +
                "Do NOT include markdown fences. Do NOT include extra text."
        );

        // contents (user)
        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        ArrayNode parts = c0.putArray("parts");

        parts.addObject().put("text",
                "Analyze the image. Output nutrition per serving if possible. " +
                "If a field is unknown, OMIT that field (do not write null)."
        );

        ObjectNode inline = parts.addObject().putObject("inline_data");
        inline.put("mime_type", mimeType);
        inline.put("data", b64);

        // generationConfig
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.set("responseJsonSchema", nutritionJsonSchema());
        gen.put("maxOutputTokens", props.getMaxOutputTokens());
        gen.put("temperature", props.getTemperature());

        return root;
    }

    /**
     * ✅ 重要：不要用 type:["number","null"] 這種 union（容易讓輸出更飄）
     * 改成：欄位可缺省（不放 required），normalize 會接受缺值。
     */
    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        props.putObject("foodName")
                .put("type", "string")
                .put("maxLength", 80);

        ObjectNode q = props.putObject("quantity");
        q.put("type", "object");
        q.put("additionalProperties", false);
        ObjectNode qp = q.putObject("properties");
        qp.putObject("value").put("type", "number").put("minimum", 0);
        qp.putObject("unit").put("type", "string")
                .putArray("enum").add("SERVING").add("GRAM").add("ML");
        q.putArray("required").add("value").add("unit");

        ObjectNode n = props.putObject("nutrients");
        n.put("type", "object");
        n.put("additionalProperties", false);
        ObjectNode np = n.putObject("properties");
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            np.putObject(k).put("type", "number").put("minimum", 0);
        }
        // ✅ nutrients 的子欄位不 required（可缺省）

        props.putObject("confidence")
                .put("type", "number")
                .put("minimum", 0)
                .put("maximum", 1);

        ObjectNode w = props.putObject("warnings");
        w.put("type", "array");
        w.putObject("items").put("type", "string");

        // ✅ root required：至少 quantity + nutrients 要有（foodName 可缺）
        schema.putArray("required").add("quantity").add("nutrients");

        return schema;
    }

    private String extractTextOrThrow(JsonNode resp) {
        if (resp == null || resp.isNull()) throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");

        JsonNode parts = resp.path("candidates").path(0).path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                String text = p.path("text").asText(null);
                if (text != null && !text.isBlank()) return text;
            }
        }

        throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
    }

    /**
     * ✅ 超穩 parse：
     * 1) 去 code fence
     * 2) 擷取第一個 { 到最後一個 }
     * 3) 移除 BOM / NULL
     * 4) 修正 JSON string 內的 CR/LF（轉成 \\n）與控制字元
     */
    private JsonNode parseJsonStrictOrThrow(String text) {
        String s = (text == null) ? "" : text.trim();

        // 1) 去掉 ```json ... ```
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int firstNl = s.indexOf('\n', first);
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
            s = s.trim();
        }

        // 2) 擷取第一個 { 到最後一個 }
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l >= 0 && r > l) {
            s = s.substring(l, r + 1).trim();
        }

        // 3) 移除 BOM / NULL
        s = stripBomAndNulls(s);

        // 4) 修正 string 內的 CR/LF、控制字元
        s = escapeBadCharsInsideJsonStrings(s);

        try {
            return om.readTree(s);
        } catch (Exception e) {
            // ✅ 這段 log 會讓你一眼看出是「截斷」還是「字串內有怪字」
            String head = safeOneLine(s, 120);
            String tail = safeOneLine(s.length() > 120 ? s.substring(Math.max(0, s.length() - 120)) : s, 120);
            GeminiProviderClient.log.warn("geminiJsonParseFailed len={} head={} tail={}", s.length(), head, tail);
            throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        }
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode out = JsonNodeFactory.instance.objectNode();

        String foodName = raw.path("foodName").asText(null);
        if (foodName != null && !foodName.isBlank()) out.put("foodName", foodName);

        // quantity：若缺/非物件 → 給預設
        JsonNode q = raw.get("quantity");
        ObjectNode oq = out.putObject("quantity");
        if (q != null && q.isObject()) {
            double v = q.path("value").asDouble(1d);
            String u = q.path("unit").asText("SERVING");
            if (v < 0) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            oq.put("value", v);
            oq.put("unit", u);
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
        if (conf != null && conf.isNumber()) {
            double c = conf.asDouble();
            if (c < 0 || c > 1) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            out.put("confidence", c);
        }

        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) out.set("warnings", w);

        return out;
    }

    private static void copyNonNegativeNumberOrNull(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) return;
        if (!v.isNumber()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        double d = v.asDouble();
        if (d < 0) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        to.put(key, d);
    }

    private static Double doubleOrNull(JsonNode v) {
        return (v == null || v.isNull() || !v.isNumber()) ? null : v.asDouble();
    }

    private static ObjectNode getOrCreateObject(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode created = JsonNodeFactory.instance.objectNode();
        root.set(field, created);
        return created;
    }

    private static String safeOneLine(String s, int maxLen) {
        if (s == null) return null;
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen);
        return t;
    }

    private static String stripBomAndNulls(String s) {
        if (s == null) return "";
        // BOM
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        // NULLs
        return s.replace("\u0000", "");
    }

    /**
     * 把 JSON 字串內的「非法控制字元」修掉（重點：CR/LF）
     * - 只有在字串內（"..."）才會把 \r/\n 轉成 \\n
     * - 其他控制字元 (< 0x20) 轉成空白
     */
    private static String escapeBadCharsInsideJsonStrings(String s) {
        if (s == null || s.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (!inString) {
                if (ch == '"') inString = true;
                // 字串外：移除不可見控制字元（保留常見空白）
                if (ch == '\u0000') continue;
                if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') continue;
                out.append(ch);
                continue;
            }

            // inString
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }

            if (ch == '"') {
                out.append(ch);
                inString = false;
                continue;
            }

            if (ch == '\r' || ch == '\n') {
                out.append("\\n");
                continue;
            }

            if (ch < 0x20) {
                out.append(' ');
                continue;
            }

            out.append(ch);
        }

        return out.toString();
    }
}
