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
            String functionName
    ) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");

        // LABEL：若不使用 function calling，則改走 JSON schema mode
        if (isLabel && !props.isLabelUseFunctionCalling()) {
            sys.putArray("parts")
                    .addObject()
                    .put("text", "Return ONLY ONE minified JSON object. No markdown. No extra text.");
        } else {
            String sysText = isLabel
                    ? ("You MUST call function " + functionName + " with arguments only. Do NOT output any text.")
                    : "Return ONLY minified JSON. No markdown. No extra text.";
            sys.putArray("parts").addObject().put("text", sysText);
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

        // LABEL + function calling mode
        if (isLabel) {
            ArrayNode tools = root.putArray("tools");
            ObjectNode tool0 = tools.addObject();
            ArrayNode fns = tool0.putArray("functionDeclarations");

            ObjectNode fn = fns.addObject();
            fn.put("name", functionName);
            fn.put("description", "Return nutrition JSON strictly matching the schema.");
            fn.set("parameters", nutritionFnSchema(true));

            ObjectNode toolConfig = root.putObject("toolConfig");
            ObjectNode fcc = toolConfig.putObject("functionCallingConfig");
            fcc.put("mode", "ANY");
            fcc.putArray("allowedFunctionNames").add(functionName);

            gen.put("maxOutputTokens", 2048);
            gen.put("temperature", 0.0);
            return root;
        }

        // Photo / Album：維持原設定
        gen.put("responseMimeType", "application/json");
        gen.set("responseJsonSchema", nutritionJsonSchema());
        gen.put("maxOutputTokens", props.getMaxOutputTokens());
        gen.put("temperature", props.getTemperature());
        return root;
    }

    ObjectNode buildTextOnlyRequest(String systemInstruction, String userPrompt) {
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text", systemInstruction);

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        c0.putArray("parts").addObject().put("text", userPrompt);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.put("maxOutputTokens", 768);
        gen.put("temperature", 0.0);

        return root;
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
