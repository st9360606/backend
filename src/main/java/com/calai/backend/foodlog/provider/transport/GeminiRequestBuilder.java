package com.calai.backend.foodlog.provider.transport;

import com.calai.backend.foodlog.provider.config.GeminiEnabledComponent;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.unit.NutritionBasis;
import com.calai.backend.foodlog.unit.QuantityUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.List;

@GeminiEnabledComponent
public final class GeminiRequestBuilder {

    private final ObjectMapper om;
    private final GeminiProperties props;

    public GeminiRequestBuilder(ObjectMapper om, GeminiProperties props) {
        this.om = om;
        this.props = props;
    }

    ObjectNode buildRequest(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            boolean isLabel,
            String functionName
    ) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");

        // LABEL JSON mode 保留
        if (isLabel && !props.isLabelUseFunctionCalling()) {
            sys.putArray("parts")
                    .addObject()
                    .put("text", "Return ONLY ONE minified JSON object. No markdown. No extra text.");
        } else {
            // ✅ LABEL function calling + PHOTO/ALBUM function calling
            sys.putArray("parts")
                    .addObject()
                    .put("text", "You MUST call function " + functionName + " with arguments only. Do NOT output any text.");
        }

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");

        ArrayNode parts = c0.putArray("parts");
        parts.addObject().put("text", userPrompt);

        ObjectNode imgPart = parts.addObject();
        ObjectNode inline = imgPart.putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", b64);

        ObjectNode gen = root.putObject("generationConfig");

        // LABEL + JSON mode
        if (isLabel && !props.isLabelUseFunctionCalling()) {
            gen.put("responseMimeType", "application/json");
            gen.set("responseJsonSchema", nutritionJsonSchemaLabelStrict());
            gen.put("maxOutputTokens", props.getLabelJson().getMaxOutputTokens());
            gen.put("temperature", props.getLabelJson().getTemperature());
            return root;
        }

        // ✅ LABEL / PHOTO / ALBUM 一律走 function calling
        ArrayNode tools = root.putArray("tools");
        ObjectNode tool0 = tools.addObject();
        ArrayNode fns = tool0.putArray("functionDeclarations");

        ObjectNode fn = fns.addObject();
        fn.put("name", functionName);
        fn.put("description", "Return nutrition JSON strictly matching the schema.");

        if (isLabel) {
            fn.set("parameters", nutritionFnSchema(true));
        } else {
            // ✅ 關鍵修正：
            // PHOTO / ALBUM 不再因為 second pass / requireCoreNutrition=true
            // 就切到會強迫 core quartet 非 null 的 strict schema。
            // 否則 prompt 說不要猜，schema 卻逼模型交數字，最終還是會 hallucinate。
            fn.set("parameters", nutritionFnSchemaPhotoMain());
        }

        ObjectNode toolConfig = root.putObject("toolConfig");
        ObjectNode fcc = toolConfig.putObject("functionCallingConfig");
        fcc.put("mode", "ANY");
        fcc.putArray("allowedFunctionNames").add(functionName);

        // ✅ tuning 選擇：
        // - PHOTO / ALBUM function calling -> photoAlbum
        // - LABEL JSON mode -> 前面已 return
        // - LABEL function calling -> 暫時沿用 labelJson，避免誤吃 photoAlbum
        GeminiProperties.RequestTuning tuning =
                isLabel ? props.getLabelJson() : props.getPhotoAlbum();

        gen.put("maxOutputTokens", tuning.getMaxOutputTokens());
        gen.put("temperature", tuning.getTemperature());

        return root;
    }

    private ObjectNode nutritionFnSchemaPhotoMain() {
        ObjectNode root = om.createObjectNode();
        root.put("type", "OBJECT");

        ObjectNode propsNode = root.putObject("properties");

        propsNode.set("foodName", sString(true));

        ObjectNode quantity = sObject(false);
        ObjectNode qProps = quantity.putObject("properties");
        qProps.set("value", sNumber(true));
        qProps.set("unit", sEnumString(true, QuantityUnit.promptValues()));
        quantity.putArray("required").add("value").add("unit");
        propsNode.set("quantity", quantity);

        ObjectNode nutrients = sObject(false);
        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            nProps.set(k, sNumber(true));
            nReq.add(k);
        }
        propsNode.set("nutrients", nutrients);

        // ✅ 對齊 USER_PROMPT_MAIN
        propsNode.set("_reasoning", sString(true));

        propsNode.set("confidence", sNumber(true));
        propsNode.set("healthScore", sNumber(true));

        ObjectNode warnings = sArray(false, sString(false));
        propsNode.set("warnings", warnings);

        ObjectNode labelMeta = sObject(false);
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(true, NutritionBasis.promptValues()));
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        propsNode.set("labelMeta", labelMeta);

        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("_reasoning");
        req.add("confidence");
        req.add("healthScore");
        req.add("warnings");
        req.add("labelMeta");

        return root;
    }

    private ObjectNode jsonNullableEnum(List<String> enums) {
        ObjectNode node = om.createObjectNode();
        ArrayNode anyOf = node.putArray("anyOf");

        ObjectNode enumString = anyOf.addObject();
        enumString.put("type", "string");
        ArrayNode enumArr = enumString.putArray("enum");
        for (String e : enums) {
            enumArr.add(e);
        }

        anyOf.addObject().put("type", "null");
        return node;
    }

    private ObjectNode nutritionFnSchema(boolean isLabel) {
        ObjectNode root = om.createObjectNode();
        root.put("type", "OBJECT");

        ObjectNode propsNode = root.putObject("properties");

        propsNode.set("foodName", sString(true));

        ObjectNode quantity = sObject(false);
        ObjectNode qProps = quantity.putObject("properties");
        qProps.set("value", sNumber(true));
        qProps.set("unit", sEnumString(true, QuantityUnit.promptValues()));
        quantity.putArray("required").add("value").add("unit");
        propsNode.set("quantity", quantity);

        ObjectNode nutrients = sObject(false);
        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            nProps.set(k, sNumber(true));
            nReq.add(k);
        }
        propsNode.set("nutrients", nutrients);

        // ✅ 對齊 prompt JSON
        propsNode.set("_reasoning", sString(true));

        propsNode.set("confidence", sNumber(true));
        propsNode.set("healthScore", sNumber(true));

        ObjectNode warnings = sArray(false, sString(false));
        propsNode.set("warnings", warnings);

        ObjectNode labelMeta = sObject(true);
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(true, NutritionBasis.promptValues()));
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        propsNode.set("labelMeta", labelMeta);

        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("_reasoning");
        req.add("confidence");
        req.add("healthScore");

        if (isLabel) {
            req.add("warnings");
            req.add("labelMeta");
        }

        return root;
    }

    private ObjectNode sObject(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "OBJECT");
        if (nullable) {
            o.put("nullable", true);
        }
        return o;
    }

    private ObjectNode sString(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "STRING");
        if (nullable) {
            o.put("nullable", true);
        }
        return o;
    }

    private ObjectNode sNumber(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "NUMBER");
        if (nullable) {
            o.put("nullable", true);
        }
        return o;
    }

    private ObjectNode sArray(boolean nullable, ObjectNode itemsSchema) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "ARRAY");
        if (nullable) {
            o.put("nullable", true);
        }
        o.set("items", itemsSchema);
        return o;
    }

    private ObjectNode sEnumString(boolean nullable, List<String> enums) {
        ObjectNode o = sString(nullable);
        ArrayNode arr = o.putArray("enum");
        for (String e : enums) {
            arr.add(e);
        }
        return o;
    }

    private ObjectNode nutritionJsonSchemaLabelStrict() {
        ObjectNode schema = nutritionJsonSchema();

        ArrayNode req = (ArrayNode) schema.get("required");
        if (req != null) {
            if (!contains(req, "warnings")) {
                req.add("warnings");
            }
            if (!contains(req, "labelMeta")) {
                req.add("labelMeta");
            }
        }

        ObjectNode propsNode = (ObjectNode) schema.get("properties");
        ObjectNode labelMeta = (ObjectNode) propsNode.get("labelMeta");

        ArrayNode lmReq = labelMeta.putArray("required");
        lmReq.add("servingsPerContainer");
        lmReq.add("basis");

        return schema;
    }

    private boolean contains(ArrayNode arr, String s) {
        for (int i = 0; i < arr.size(); i++) {
            if (s.equals(arr.get(i).asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        ObjectNode labelMeta = propsNode.putObject("labelMeta");
        labelMeta.put("type", "object");
        labelMeta.put("additionalProperties", false);

        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.putObject("servingsPerContainer").putArray("type").add("number").add("null");
        lmProps.set("basis", jsonNullableEnum(NutritionBasis.promptValues()));

        ObjectNode foodName = propsNode.putObject("foodName");
        foodName.putArray("type").add("string").add("null");
        foodName.put("maxLength", 80);

        ObjectNode quantity = propsNode.putObject("quantity");
        quantity.put("type", "object");
        quantity.put("additionalProperties", false);

        ObjectNode qProps = quantity.putObject("properties");
        qProps.putObject("value").putArray("type").add("number").add("null");
        qProps.set("unit", jsonNullableEnum(QuantityUnit.promptValues()));
        quantity.putArray("required").add("value").add("unit");

        ObjectNode nutrients = propsNode.putObject("nutrients");
        nutrients.put("type", "object");
        nutrients.put("additionalProperties", false);

        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            nProps.putObject(k).putArray("type").add("number").add("null");
            nReq.add(k);
        }

        // ✅ 對齊 USER_PROMPT_LABEL_MAIN
        ObjectNode reasoning = propsNode.putObject("_reasoning");
        reasoning.putArray("type").add("string").add("null");

        propsNode.putObject("confidence").putArray("type").add("number").add("null");
        propsNode.putObject("healthScore").putArray("type").add("number").add("null");

        ObjectNode warnings = propsNode.putObject("warnings");
        warnings.put("type", "array");
        warnings.putObject("items").put("type", "string");

        schema.putArray("required")
                .add("foodName")
                .add("quantity")
                .add("nutrients")
                .add("_reasoning")
                .add("confidence")
                .add("healthScore");

        return schema;
    }
}
