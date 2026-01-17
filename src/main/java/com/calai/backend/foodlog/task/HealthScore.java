package com.calai.backend.foodlog.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class HealthScore {

    /**
     * 回傳 1..10；資料不足回 null（不硬猜）
     */
    public Integer score(ObjectNode nutrients) {
        if (nutrients == null) return null;

        Double fiber = num(nutrients.get("fiber"));   // g
        Double sugar = num(nutrients.get("sugar"));   // g
        Double sodium = num(nutrients.get("sodium")); // mg
        Double protein = num(nutrients.get("protein"));// g
        Double fat = num(nutrients.get("fat"));       // g

        // 需要至少兩項才算（避免垃圾資料也能算）
        int present = countPresent(fiber, sugar, sodium, protein, fat);
        if (present < 2) return null;

        int s = 6;

        // fiber：+0~+2
        if (fiber != null) {
            if (fiber >= 8) s += 2;
            else if (fiber >= 4) s += 1;
        }

        // sugar：-0~-2
        if (sugar != null) {
            if (sugar >= 20) s -= 2;
            else if (sugar >= 10) s -= 1;
        }

        // sodium：-0~-2
        if (sodium != null) {
            if (sodium >= 800) s -= 2;
            else if (sodium >= 500) s -= 1;
        }

        // protein：+0~+2
        if (protein != null) {
            if (protein >= 25) s += 2;
            else if (protein >= 10) s += 1;
        }

        // total fat：-0~-2（很粗糙，v1 先這樣）
        if (fat != null) {
            if (fat >= 30) s -= 2;
            else if (fat >= 15) s -= 1;
        }

        if (s < 1) s = 1;
        if (s > 10) s = 10;
        return s;
    }

    private static Double num(JsonNode v) {
        return (v == null || v.isNull() || !v.isNumber()) ? null : v.asDouble();
    }

    private static int countPresent(Double... arr) {
        int n = 0;
        for (Double v : arr) if (v != null) n++;
        return n;
    }
}
