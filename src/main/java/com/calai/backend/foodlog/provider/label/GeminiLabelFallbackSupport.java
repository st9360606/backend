package com.calai.backend.foodlog.provider.label;

import com.calai.backend.foodlog.provider.GeminiEffectiveJsonSupport;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeminiLabelFallbackSupport {

    private static final Pattern P_NUM =
            Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");

    private static final Pattern P_JSON_KV_STRING =
            Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern P_JSON_KV_NUMBER =
            Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+(?:[.,]\\d+)?)");

    private final ObjectMapper om;

    public GeminiLabelFallbackSupport(ObjectMapper om) {
        this.om = om;
    }

    public boolean looksLikeStartedJsonLabel(String text) {
        if (text == null) return false;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("{")) return false;
        return s.contains("\"foodname\"") || s.contains("\"quantity\"") || s.contains("\"nutrients\"");
    }

    public ObjectNode fallbackLabelPartialDetected(String rawText) {
        ObjectNode root = om.createObjectNode();

        String name = extractJsonStringValue(rawText, "foodName");
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        fillMissingNutrientsWithZero(n);

        // ✅ confidence 不自己猜，保持 null
        root.putNull("confidence");

        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LABEL_PARTIAL.name());
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    public boolean isLabelEmptyArgs(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return true;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        boolean allZeroOrNull = true;
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            Double v = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get(k));
            if (v != null && Math.abs(v) > 0.0001d) {
                allZeroOrNull = false;
                break;
            }
        }

        if (!allZeroOrNull) return false;

        Double conf = GeminiEffectiveJsonSupport.parseNumberNodeOrText(root.get("confidence"));
        return conf == null || conf <= 0.1d;
    }

    public boolean isLabelIncomplete(JsonNode root) {
        root = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return true;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        Double kcal = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("kcal"));
        Double protein = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("protein"));
        Double fat = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("fat"));
        Double carbs = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("carbs"));
        Double fiber = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("fiber"));
        Double sugar = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("sugar"));
        Double sodium = GeminiEffectiveJsonSupport.parseNumberNodeOrText(n.get("sodium"));
        Double conf = GeminiEffectiveJsonSupport.parseNumberNodeOrText(root.get("confidence"));

        boolean kcalMissing = (kcal == null);
        boolean allZeroOrNull =
                zeroOrNull(kcal) && zeroOrNull(protein) && zeroOrNull(fat) && zeroOrNull(carbs)
                && zeroOrNull(fiber) && zeroOrNull(sugar) && zeroOrNull(sodium);

        boolean coreAllZeroOrNull =
                zeroOrNull(kcal) && zeroOrNull(protein) && zeroOrNull(fat) && zeroOrNull(carbs);

        if (kcalMissing) return true;
        if (allZeroOrNull) return true;
        if (coreAllZeroOrNull && (conf == null || conf <= 0.35d)) return true;

        return false;
    }

    private static boolean zeroOrNull(Double v) {
        return v == null || Math.abs(v) < 0.0001d;
    }

    public void ensureLabelRequiredKeys(ObjectNode obj) {
        if (obj == null) return;

        if (!obj.has("foodName")) obj.putNull("foodName");

        if (!obj.has("quantity") || !obj.get("quantity").isObject()) {
            ObjectNode q = om.createObjectNode();
            q.put("value", 1d);
            q.put("unit", "SERVING");
            obj.set("quantity", q);
        } else {
            ObjectNode q = (ObjectNode) obj.get("quantity");
            if (!q.has("value") || q.get("value").isNull()) q.put("value", 1d);
            if (!q.has("unit") || q.get("unit").isNull()) q.put("unit", "SERVING");
        }

        if (!obj.has("nutrients") || !obj.get("nutrients").isObject()) {
            ObjectNode n = om.createObjectNode();
            fillMissingNutrientsWithZero(n);
            obj.set("nutrients", n);
        } else {
            ObjectNode n = (ObjectNode) obj.get("nutrients");
            fillMissingNutrientsWithZero(n);
        }

        // ✅ confidence / healthScore 不自己補數字
        if (!obj.has("confidence")) obj.putNull("confidence");
        if (!obj.has("healthScore")) obj.putNull("healthScore");

        if (!obj.has("warnings") || !obj.get("warnings").isArray()) {
            obj.set("warnings", om.createArrayNode());
        }

        if (!obj.has("labelMeta") || !obj.get("labelMeta").isObject()) {
            ObjectNode lm = om.createObjectNode();
            lm.putNull("servingsPerContainer");
            lm.putNull("basis");
            obj.set("labelMeta", lm);
        } else {
            ObjectNode lm = (ObjectNode) obj.get("labelMeta");
            if (!lm.has("servingsPerContainer")) lm.putNull("servingsPerContainer");
            if (!lm.has("basis")) lm.putNull("basis");
        }
    }

    public static void addWarningIfMissing(ObjectNode raw, FoodLogWarning w) {
        if (raw == null || w == null) return;
        ArrayNode arr = raw.withArray("warnings");
        for (JsonNode it : arr) {
            if (it != null && !it.isNull() && w.name().equalsIgnoreCase(it.asText())) return;
        }
        arr.add(w.name());
    }

    public ObjectNode tryFixTruncatedJson(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return null;

        String text = rawText.trim();
        String fixed = fixCommonTruncationPatterns(text);

        try {
            JsonNode parsed = om.readTree(fixed);
            parsed = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(parsed);

            if (parsed != null && parsed.isObject()) {
                ObjectNode obj = (ObjectNode) parsed;

                if (!obj.has("nutrients") || !obj.get("nutrients").isObject()) {
                    ObjectNode nutrients = om.createObjectNode();
                    fillMissingNutrientsWithZero(nutrients);
                    obj.set("nutrients", nutrients);
                } else {
                    fillMissingNutrientsWithZero((ObjectNode) obj.get("nutrients"));
                }

                if (!obj.has("confidence")) obj.putNull("confidence");
                if (!obj.has("healthScore")) obj.putNull("healthScore");

                if (!obj.has("quantity") || !obj.get("quantity").isObject()) {
                    ObjectNode quantity = om.createObjectNode();
                    quantity.put("value", 1.0);
                    quantity.put("unit", "SERVING");
                    obj.set("quantity", quantity);
                }

                if (!obj.has("warnings") || !obj.get("warnings").isArray()) {
                    obj.set("warnings", om.createArrayNode().add(FoodLogWarning.LOW_CONFIDENCE.name()));
                }

                if (!obj.has("labelMeta")) {
                    ObjectNode lm = om.createObjectNode();
                    lm.putNull("servingsPerContainer");
                    lm.putNull("basis");
                    obj.set("labelMeta", lm);
                }

                return obj;
            }
        } catch (Exception ignore) {
            // fallback below
        }

        return extractFromTruncatedText(text);
    }

    public ObjectNode tryExtractFromBrokenJsonLikeText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace('\r', ' ').replace('\n', ' ');

        String name = findJsonStringValue(s, "foodName");

        Double kcal = findJsonNumberValue(s, "kcal");
        Double protein = findJsonNumberValue(s, "protein");
        Double fat = findJsonNumberValue(s, "fat");
        Double carbs = findJsonNumberValue(s, "carbs");
        Double fiber = findJsonNumberValue(s, "fiber");
        Double sugar = findJsonNumberValue(s, "sugar");

        Double sodium = findJsonNumberValue(s, "sodium");
        if (sodium == null) sodium = extractSodiumMgFromText(s);

        boolean hasAny =
                kcal != null || protein != null || fat != null || carbs != null
                || fiber != null || sugar != null || sodium != null;

        if (!hasAny) return null;

        ObjectNode root = om.createObjectNode();
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        putOrNull(n, "kcal", kcal);
        putOrNull(n, "protein", protein);
        putOrNull(n, "fat", fat);
        putOrNull(n, "carbs", carbs);
        putOrNull(n, "fiber", fiber);
        putOrNull(n, "sugar", sugar);
        putOrNull(n, "sodium", sodium);

        root.putNull("confidence");
        root.putArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    public ObjectNode tryExtractLabelFromPlainText(String raw) {
        if (raw == null) return null;

        String s = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.isEmpty()) return null;

        String lower = s.toLowerCase(Locale.ROOT);
        if (!P_NUM.matcher(lower).find()) return null;

        Double sodium = extractSodiumMgFromText(s);
        Double kcal = findNumberAfterAny(lower, new String[]{"kcal", "calories", "熱量", "大卡", "能量"});
        Double protein = findNumberAfterAny(lower, new String[]{"protein", "proteins", "蛋白質", "蛋白"});
        Double fat = findNumberAfterAny(lower, new String[]{"total fat", "fat", "總脂肪", "脂肪"});
        Double carbs = findNumberAfterAny(lower, new String[]{"carbohydrate", "carbohydrates", "carbs", "碳水化合物", "碳水"});
        Double fiber = findNumberAfterAny(lower, new String[]{"fiber", "fibre", "膳食纖維", "纖維"});
        Double sugar = findNumberAfterAny(lower, new String[]{"total sugars", "sugars", "sugar", "糖", "總糖"});

        ParsedAmount servingAmount = findServingAmount(s);
        Double servingsPerContainer = findNumberAfterAny(
                lower,
                new String[]{"servings per container", "本包裝含", "每一包裝含", "本包裝含有"}
        );

        boolean hasAny =
                sodium != null || kcal != null || protein != null || fat != null
                || carbs != null || fiber != null || sugar != null;

        if (!hasAny) return null;

        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        // 對齊 Prompt：quantity 不再輸出 GRAM / ML
        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", inferLogicalUnitFromText(lower));

        ObjectNode n = root.putObject("nutrients");
        putOrNull(n, "kcal", kcal);
        putOrNull(n, "protein", protein);
        putOrNull(n, "fat", fat);
        putOrNull(n, "carbs", carbs);
        putOrNull(n, "fiber", fiber);
        putOrNull(n, "sugar", sugar);
        putOrNull(n, "sodium", sodium);

        root.putNull("confidence");
        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        if (servingsPerContainer != null && servingsPerContainer > 1) {
            lm.put("servingsPerContainer", servingsPerContainer);
            lm.put("basis", "PER_SERVING");
        } else if (servingAmount != null) {
            lm.putNull("servingsPerContainer");
            lm.put("basis", "PER_SERVING");
        } else {
            lm.putNull("servingsPerContainer");
            lm.putNull("basis");
        }

        return root;
    }

    private static String inferLogicalUnitFromText(String lower) {
        if (lower == null || lower.isBlank()) {
            return "SERVING";
        }
        if (lower.contains("bottle") || lower.contains("drink") || lower.contains("飲料")) {
            return "BOTTLE";
        }
        if (lower.contains("can") || lower.contains("tin")) {
            return "CAN";
        }
        if (lower.contains("bag") || lower.contains("pack") || lower.contains("package")
            || lower.contains("box") || lower.contains("袋") || lower.contains("盒")) {
            return "PACK";
        }
        if (lower.contains("piece") || lower.contains("pcs") || lower.contains("pc")
            || lower.contains("顆") || lower.contains("片") || lower.contains("個")) {
            return "PIECE";
        }
        return "SERVING";
    }

    public boolean isNoLabelLikely(String rawText) {
        if (rawText == null) return false;
        String s = rawText.trim();
        if (s.isEmpty()) return false;

        String lower = s.toLowerCase(Locale.ROOT);

        if (P_NUM.matcher(lower).find()) return false;

        boolean hasSignals =
                lower.contains("nutrition") || lower.contains("calor")
                || lower.contains("energy") || lower.contains("energi") || lower.contains("energia")
                || lower.contains("kcal") || lower.contains("kj")
                || lower.contains("protein") || lower.contains("prote")
                || lower.contains("fat") || lower.contains("lipid") || lower.contains("grasa") || lower.contains("fett") || lower.contains("lemak")
                || lower.contains("carb") || lower.contains("glucid")
                || lower.contains("sugar") || lower.contains("zucker") || lower.contains("azucar")
                || lower.contains("fiber") || lower.contains("fibre")
                || lower.contains("sodium") || lower.contains("salt") || lower.contains("salz")
                || lower.contains("serving") || lower.contains("portion") || lower.contains("per 100")
                || lower.contains("100g") || lower.contains("100 g") || lower.contains("100ml") || lower.contains("100 ml")
                || lower.contains("營養") || lower.contains("能量") || lower.contains("熱量") || lower.contains("蛋白") || lower.contains("脂肪")
                || lower.contains("碳水") || lower.contains("糖") || lower.contains("鈉") || lower.contains("鹽");

        if (hasSignals) return false;

        return lower.contains("no label")
               || lower.contains("no nutrition label")
               || lower.contains("nutrition table not found")
               || lower.contains("not a label")
               || lower.contains("cannot read")
               || lower.contains("unable to read")
               || lower.contains("cannot extract")
               || lower.contains("unable to extract")
               || lower.contains("too blurry")
               || lower.contains("blurry")
               || lower.contains("too small")
               || lower.contains("glare")
               || lower.contains("reflection")
               || lower.contains("no text detected")
               || lower.contains("無法辨識")
               || lower.contains("無法識別")
               || lower.contains("無法讀取")
               || lower.contains("看不清")
               || lower.contains("太模糊")
               || lower.contains("反光")
               || lower.contains("太小")
               || lower.contains("無法擷取")
               || lower.contains("無法提取");
    }

    public ObjectNode fallbackNoLabelDetected() {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        fillMissingNutrientsWithZero(n);

        // ✅ confidence 不自己猜
        root.putNull("confidence");

        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.NO_LABEL_DETECTED.name());
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    public static ParsedAmount findServingAmount(String text) {
        if (text == null || text.isBlank()) return null;

        String[] patterns = new String[]{
                "(?i)(serving\\s*size|serving|portion)\\D{0,20}(\\d+(?:[.,]\\d+)?)\\s*(g|gram|grams|ml|milliliter|milliliters)",
                "(?i)(每一份量|每份量|每份)\\D{0,20}(\\d+(?:[.,]\\d+)?)\\s*(公克|克|g|ml|毫升)"
        };

        for (String regex : patterns) {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (!m.find()) continue;

            double value = Double.parseDouble(m.group(2).replace(',', '.'));
            String rawUnit = m.group(3).toLowerCase(Locale.ROOT);

            String unit = (rawUnit.contains("ml") || rawUnit.contains("毫升"))
                    ? "ML"
                    : "GRAM";

            return new ParsedAmount(value, unit);
        }
        return null;
    }

    public static Double extractSodiumMgFromText(String text) {
        if (text == null) return null;

        String lowerText = text.toLowerCase(Locale.ROOT);
        String[] sodiumMarkers = {
                "\"sodium\":", "\"sodium\" :",
                "\"鈉\":", "\"钠\":",
                "sodium", "鈉", "钠", "natrium"
        };

        for (String marker : sodiumMarkers) {
            int idx = lowerText.indexOf(marker.toLowerCase(Locale.ROOT));
            if (idx < 0) continue;

            String tail = text.substring(idx + marker.length());
            ParsedMeasuredValue mv = extractFirstMeasuredValue(tail);
            if (mv == null || mv.value() == null) continue;

            String unit = (mv.unit() == null) ? "" : mv.unit().toLowerCase(Locale.ROOT);

            if (unit.contains("mg") || unit.contains("毫克")) {
                return mv.value();
            }
            if ("g".equals(unit) || unit.contains("公克") || "克".equals(unit)) {
                return mv.value() * 1000.0;
            }

            return mv.value();
        }

        return null;
    }

    private String fixCommonTruncationPatterns(String text) {
        if (text == null || text.isEmpty()) return text;

        StringBuilder fixed = new StringBuilder(text);

        int nutrientsStart = text.indexOf("\"nutrients\":{");
        if (nutrientsStart >= 0) {
            int nutrientsOpen = nutrientsStart + "\"nutrients\":{".length();
            int nutrientsClose = findMatchingBrace(text, nutrientsOpen - 1);

            if (nutrientsClose < 0) {
                Double kcal = extractNumberAfter(text, "\"kcal\":");
                Double sodium = extractSodiumMgFromText(text);

                StringBuilder nutrients = new StringBuilder();
                nutrients.append("\"nutrients\":{");
                nutrients.append("\"kcal\":").append(kcal != null ? kcal : 0.0);
                nutrients.append(",\"protein\":0.0");
                nutrients.append(",\"fat\":0.0");
                nutrients.append(",\"carbs\":0.0");
                nutrients.append(",\"fiber\":0.0");
                nutrients.append(",\"sugar\":0.0");
                nutrients.append(",\"sodium\":").append(sodium != null ? sodium : 0.0);
                nutrients.append("}");

                String beforeNutrients = text.substring(0, text.indexOf("\"nutrients\":{"));
                String afterNutrients = text.substring(text.indexOf("\"nutrients\":{") + "\"nutrients\":{".length());

                if (afterNutrients.contains("\"confidence\":")) {
                    return beforeNutrients + nutrients + afterNutrients.substring(afterNutrients.indexOf("\"confidence\":"));
                } else if (afterNutrients.contains("}")) {
                    int firstBrace = afterNutrients.indexOf('}');
                    return beforeNutrients + nutrients + afterNutrients.substring(firstBrace);
                } else {
                    return beforeNutrients + nutrients + ",\"confidence\":null,\"warnings\":[\"LOW_CONFIDENCE\"],\"labelMeta\":{\"servingsPerContainer\":null,\"basis\":null}}";
                }
            }
        }

        if (!text.endsWith("}")) {
            int openBraces = 0;
            int closeBraces = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = 0; i < fixed.length(); i++) {
                char ch = fixed.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (inString) {
                    if (ch == '\\') escaped = true;
                    else if (ch == '"') inString = false;
                    continue;
                }
                if (ch == '"') inString = true;
                else if (ch == '{') openBraces++;
                else if (ch == '}') closeBraces++;
            }

            if (openBraces > closeBraces) {
                int missing = openBraces - closeBraces;
                for (int i = 0; i < missing; i++) fixed.append('}');
            }
        }

        String result = removeTrailingCommas(fixed.toString());
        if (!result.endsWith("}")) result = result + "}";
        return result;
    }

    private ObjectNode extractFromTruncatedText(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        ObjectNode root = om.createObjectNode();

        String foodName = extractJsonStringValue(text, "foodName");
        if (foodName != null && !foodName.isEmpty()) root.put("foodName", foodName);
        else root.putNull("foodName");

        ObjectNode quantity = om.createObjectNode();
        Double qtyValue = extractJsonNumberValue(text, "value");
        String qtyUnit = extractJsonStringValue(text, "unit");

        quantity.put("value", qtyValue != null ? qtyValue : 1.0);
        quantity.put("unit", (qtyUnit != null && !qtyUnit.isEmpty()) ? qtyUnit : "SERVING");
        root.set("quantity", quantity);

        Double kcal = extractJsonNumberValue(text, "kcal");
        Double protein = extractJsonNumberValue(text, "protein");
        Double fat = extractJsonNumberValue(text, "fat");
        Double carbs = extractJsonNumberValue(text, "carbs");
        Double fiber = extractJsonNumberValue(text, "fiber");
        Double sugar = extractJsonNumberValue(text, "sugar");

        Double sodium = extractJsonNumberValue(text, "sodium");
        if (sodium == null) sodium = extractSodiumMgFromText(text);

        boolean hasAny =
                kcal != null || protein != null || fat != null || carbs != null
                || fiber != null || sugar != null || sodium != null;

        if (!hasAny) return null;

        ObjectNode nutrients = om.createObjectNode();
        putOrNull(nutrients, "kcal", kcal);
        putOrNull(nutrients, "protein", protein);
        putOrNull(nutrients, "fat", fat);
        putOrNull(nutrients, "carbs", carbs);
        putOrNull(nutrients, "fiber", fiber);
        putOrNull(nutrients, "sugar", sugar);
        putOrNull(nutrients, "sodium", sodium);
        root.set("nutrients", nutrients);

        root.putNull("confidence");
        ArrayNode warnings = root.putArray("warnings");
        warnings.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    private static ParsedMeasuredValue extractFirstMeasuredValue(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher m = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*(mg|g|ml|毫克|公克|克|毫升)?", Pattern.CASE_INSENSITIVE)
                .matcher(text);

        if (!m.find()) return null;

        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2);
            return new ParsedMeasuredValue(value, unit);
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractJsonStringValue(String text, String key) {
        if (text == null || key == null) return null;

        String[] patterns = {
                "\"" + key + "\":\"([^\"]*)\"",
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"",
                "'" + key + "':\"([^\"]*)\"",
                "'" + key + "'\\s*:\\s*\"([^\"]*)\""
        };

        for (String pattern : patterns) {
            try {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(text);
                if (m.find()) return m.group(1);
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private Double extractJsonNumberValue(String text, String key) {
        if (text == null || key == null) return null;

        String[] patterns = {
                "\"" + key + "\":\\s*(-?\\d+(?:\\.\\d+)?)",
                "\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)",
                "'" + key + "':\\s*(-?\\d+(?:\\.\\d+)?)",
                "'" + key + "'\\s*:\\s*(-?\\d+(?:\\.\\d+)?)"
        };

        for (String pattern : patterns) {
            try {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(text);
                if (m.find()) return Double.parseDouble(m.group(1));
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static String findJsonStringValue(String text, String key) {
        Pattern p = Pattern.compile(String.format(P_JSON_KV_STRING.pattern(), Pattern.quote(key)));
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static Double findJsonNumberValue(String text, String key) {
        Pattern p = Pattern.compile(String.format(P_JSON_KV_NUMBER.pattern(), Pattern.quote(key)));
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        String v = m.group(1).replace(',', '.');
        try {
            return Double.parseDouble(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void fillMissingNutrientsWithZero(ObjectNode n) {
        if (n == null) return;

        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            JsonNode v = n.get(k);
            if (v == null || v.isNull()) {
                n.put(k, 0.0);
            }
        }
    }

    private static void putOrNull(ObjectNode obj, String key, Double v) {
        if (v == null) obj.put(key, 0.0);
        else obj.put(key, v);
    }

    private static Double findNumberAfterAny(String text, String[] keys) {
        for (String k : keys) {
            int idx = text.indexOf(k);
            if (idx < 0) continue;
            String tail = text.substring(idx);
            if (tail.length() > 80) tail = tail.substring(0, 80);
            Double d = parseFirstNumber(tail);
            if (d != null) return d;
        }
        return null;
    }

    private static Double parseFirstNumber(String raw) {
        if (raw == null) return null;
        Matcher m = P_NUM.matcher(raw);
        if (!m.find()) return null;
        String s = m.group(1).replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double extractNumberAfter(String text, String marker) {
        if (text == null || marker == null) return null;
        int idx = text.indexOf(marker);
        if (idx >= 0) {
            String afterMarker = text.substring(idx + marker.length());
            return parseFirstNumber(afterMarker);
        }
        return null;
    }

    private int findMatchingBrace(String text, int openBracePos) {
        if (text == null || openBracePos < 0 || openBracePos >= text.length()) return -1;

        int balance = 1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBracePos + 1; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (inString) {
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }

            if (ch == '"') inString = true;
            else if (ch == '{') balance++;
            else if (ch == '}') {
                balance--;
                if (balance == 0) return i;
            }
        }
        return -1;
    }

    private static String removeTrailingCommas(String s) {
        if (s == null || s.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (inString) {
                out.append(ch);
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
                out.append(ch);
                continue;
            }
            if (ch == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length()) {
                    char nx = s.charAt(j);
                    if (nx == '}' || nx == ']') continue;
                }
            }
            out.append(ch);
        }

        return out.toString();
    }

    public record ParsedAmount(Double value, String unit) {}

    private record ParsedMeasuredValue(Double value, String unit) {}
}
