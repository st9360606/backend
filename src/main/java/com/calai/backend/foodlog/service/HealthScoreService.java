package com.calai.backend.foodlog.service;

import org.springframework.stereotype.Service;

/**
 * ⚠️ MVP 暫行的「健康分數」規則（非醫療建議）。
 * Step 3 會做：版本化、回歸測試、（可選）更完整的營養規則。
 */
@Service
public class HealthScoreService {

    public Integer score(Double fiberG, Double sugarG, Double sodiumMg) {
        // 缺核心資料：先不算
        if (fiberG == null && sugarG == null && sodiumMg == null) return null;

        int s = 6; // 基準

        if (fiberG != null) {
            if (fiberG >= 8) s += 2;
            else if (fiberG >= 4) s += 1;
        }

        if (sugarG != null) {
            if (sugarG >= 20) s -= 2;
            else if (sugarG >= 10) s -= 1;
        }

        if (sodiumMg != null) {
            if (sodiumMg >= 800) s -= 2;
            else if (sodiumMg >= 500) s -= 1;
        }

        if (s < 1) s = 1;
        if (s > 10) s = 10;
        return s;
    }
}
