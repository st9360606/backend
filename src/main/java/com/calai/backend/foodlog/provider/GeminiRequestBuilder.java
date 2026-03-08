package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;

final class GeminiRequestBuilder {

    private final ObjectMapper om;
    private final GeminiProperties props;

    GeminiRequestBuilder(ObjectMapper om, GeminiProperties props) {
        this.om = om;
        this.props = props;
    }

    ObjectNode buildRequest(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            boolean isLabel,
            String functionName,
            boolean requireCoreNutrition
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
            gen.put("maxOutputTokens", 2048);
            gen.put("temperature", 0.0);
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
            // PHOTO / ALBUM:
            // 主流程：較寬鬆
            // 第二次 finalize：較嚴格
            fn.set("parameters", requireCoreNutrition
                    ? nutritionFnSchemaPhotoStrict()
                    : nutritionFnSchemaPhotoMain());
        }

        ObjectNode toolConfig = root.putObject("toolConfig");
        ObjectNode fcc = toolConfig.putObject("functionCallingConfig");
        fcc.put("mode", "ANY");
        fcc.putArray("allowedFunctionNames").add(functionName);

        gen.put("maxOutputTokens", requireCoreNutrition ? 1024 : 1536);
        gen.put("temperature", 0.0);

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
        qProps.set("unit", sEnumString(true, List.of("SERVING", "GRAM", "ML")));
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

        propsNode.set("confidence", sNumber(true));

        ObjectNode warnings = sArray(false, sString(false));
        propsNode.set("warnings", warnings);

        ObjectNode labelMeta = sObject(false);
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(true, List.of("PER_SERVING", "WHOLE_PACKAGE")));
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        propsNode.set("labelMeta", labelMeta);

        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("confidence");
        req.add("warnings");
        req.add("labelMeta");

        return root;
    }

    private ObjectNode nutritionFnSchemaPhotoStrict() {
        ObjectNode root = om.createObjectNode();
        root.put("type", "OBJECT");

        ObjectNode propsNode = root.putObject("properties");

        propsNode.set("foodName", sString(false));

        ObjectNode quantity = sObject(false);
        ObjectNode qProps = quantity.putObject("properties");
        qProps.set("value", sNumber(false));
        qProps.set("unit", sEnumString(false, List.of("SERVING")));
        quantity.putArray("required").add("value").add("unit");
        propsNode.set("quantity", quantity);

        ObjectNode nutrients = sObject(false);
        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");

        nProps.set("kcal", sNumber(false));
        nReq.add("kcal");

        nProps.set("protein", sNumber(false));
        nReq.add("protein");

        nProps.set("fat", sNumber(false));
        nReq.add("fat");

        nProps.set("carbs", sNumber(false));
        nReq.add("carbs");

        nProps.set("fiber", sNumber(true));
        nReq.add("fiber");

        nProps.set("sugar", sNumber(true));
        nReq.add("sugar");

        nProps.set("sodium", sNumber(true));
        nReq.add("sodium");

        propsNode.set("nutrients", nutrients);

        propsNode.set("confidence", sNumber(false));

        ObjectNode warnings = sArray(false, sString(false));
        propsNode.set("warnings", warnings);

        ObjectNode labelMeta = sObject(false);
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(false, List.of("WHOLE_PACKAGE")));
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        propsNode.set("labelMeta", labelMeta);

        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("confidence");
        req.add("warnings");
        req.add("labelMeta");

        return root;
    }

    ObjectNode buildTextOnlyRequest(
            String systemInstruction,
            String userPrompt,
            boolean requireCoreNutrition,
            boolean useStrictJsonSchema
    ) {
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text", systemInstruction);

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        c0.putArray("parts").addObject().put("text", userPrompt);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.put("temperature", 0.0);

        if (useStrictJsonSchema) {
            if (requireCoreNutrition) {
                gen.set("responseJsonSchema", nutritionJsonSchemaPhotoTextStrict());
                gen.put("maxOutputTokens", 1024);
            } else {
                gen.set("responseJsonSchema", nutritionJsonSchemaLabelStrict());
                gen.put("maxOutputTokens", 768);
            }
        } else {
            // whole-package knowledge lookup：不要再走 strict schema
            // 保留 application/json，但讓模型自由完成整包 JSON
            gen.put("maxOutputTokens", requireCoreNutrition ? 1536 : 768);
        }
        return root;
    }



    private ObjectNode nutritionJsonSchemaPhotoTextStrict() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        // ✅ 把最重要的欄位順序提前：foodName -> quantity -> nutrients -> confidence -> warnings
        ObjectNode foodName = propsNode.putObject("foodName");
        foodName.putArray("type").add("string").add("null");
        foodName.put("maxLength", 120);

        ObjectNode quantity = propsNode.putObject("quantity");
        quantity.put("type", "object");
        quantity.put("additionalProperties", false);

        ObjectNode qProps = quantity.putObject("properties");
        qProps.putObject("value").putArray("type").add("number").add("null");

        ObjectNode unit = qProps.putObject("unit");
        unit.putArray("type").add("string").add("null");
        unit.putArray("enum").add("SERVING").add("GRAM").add("ML");

        quantity.putArray("required").add("value").add("unit");

        ObjectNode nutrients = propsNode.putObject("nutrients");
        nutrients.put("type", "object");
        nutrients.put("additionalProperties", false);

        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");

        // ✅ 核心四大：一定要 number，不准 null
        nProps.putObject("kcal").put("type", "number");
        nReq.add("kcal");

        nProps.putObject("protein").put("type", "number");
        nReq.add("protein");

        nProps.putObject("fat").put("type", "number");
        nReq.add("fat");

        nProps.putObject("carbs").put("type", "number");
        nReq.add("carbs");

        // ✅ 其他可為 null
        nProps.putObject("fiber").putArray("type").add("number").add("null");
        nReq.add("fiber");

        nProps.putObject("sugar").putArray("type").add("number").add("null");
        nReq.add("sugar");

        nProps.putObject("sodium").putArray("type").add("number").add("null");
        nReq.add("sodium");

        propsNode.putObject("confidence").putArray("type").add("number").add("null");

        ObjectNode warnings = propsNode.putObject("warnings");
        warnings.put("type", "array");
        warnings.putObject("items").put("type", "string");

        // ✅ labelMeta 對 photo fallback 不是必填，只是附加資訊
        ObjectNode labelMeta = propsNode.putObject("labelMeta");
        labelMeta.putArray("type").add("object").add("null");
        labelMeta.put("additionalProperties", false);

        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.putObject("servingsPerContainer").putArray("type").add("number").add("null");

        ObjectNode basis = lmProps.putObject("basis");
        basis.putArray("type").add("string").add("null");
        basis.putArray("enum").add("PER_SERVING").add("WHOLE_PACKAGE");

        labelMeta.putArray("required")
                .add("servingsPerContainer")
                .add("basis");

        schema.putArray("required")
                .add("foodName")
                .add("quantity")
                .add("nutrients")
                .add("confidence")
                .add("warnings");

        return schema;
    }

    private ObjectNode nutritionFnSchema(boolean isLabel) {
        // Gemini functionDeclaration.parameters 使用的是 Google Schema（非 JSON Schema）
        ObjectNode root = om.createObjectNode();
        root.put("type", "OBJECT");

        ObjectNode propsNode = root.putObject("properties");

        propsNode.set("foodName", sString(true));

        ObjectNode quantity = sObject(false);
        ObjectNode qProps = quantity.putObject("properties");
        qProps.set("value", sNumber(true));
        qProps.set("unit", sEnumString(true, List.of("SERVING", "GRAM", "ML")));
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

        propsNode.set("confidence", sNumber(true));

        ObjectNode warnings = sArray(false, sString(false));
        propsNode.set("warnings", warnings);

        ObjectNode labelMeta = sObject(true);
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(true, List.of("PER_SERVING", "WHOLE_PACKAGE")));
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        propsNode.set("labelMeta", labelMeta);

        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("confidence");

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

        ObjectNode basis = lmProps.putObject("basis");
        basis.putArray("type").add("string").add("null");
        basis.putArray("enum").add("PER_SERVING").add("WHOLE_PACKAGE");

        ObjectNode foodName = propsNode.putObject("foodName");
        foodName.putArray("type").add("string").add("null");
        foodName.put("maxLength", 80);

        ObjectNode quantity = propsNode.putObject("quantity");
        quantity.put("type", "object");
        quantity.put("additionalProperties", false);

        ObjectNode qProps = quantity.putObject("properties");
        qProps.putObject("value").putArray("type").add("number").add("null");

        ObjectNode unit = qProps.putObject("unit");
        unit.putArray("type").add("string").add("null");
        unit.putArray("enum").add("SERVING").add("GRAM").add("ML");

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

        propsNode.putObject("confidence").putArray("type").add("number").add("null");

        ObjectNode warnings = propsNode.putObject("warnings");
        warnings.put("type", "array");
        ObjectNode items = warnings.putObject("items");
        items.put("type", "string");

        schema.putArray("required")
                .add("foodName")
                .add("quantity")
                .add("nutrients")
                .add("confidence");

        return schema;
    }
}
