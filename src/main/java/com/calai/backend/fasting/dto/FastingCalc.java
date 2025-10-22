package com.calai.backend.fasting.dto;

import com.calai.backend.fasting.entity.FastingPlan;

import java.time.*;

public final class FastingCalc {
    private FastingCalc() {}

    /** 依吃窗小時數計算 "當地" end time（顯示用；DST 變動會導致 +/−1h 體感差異） */
    public static LocalTime endTimeLocal(LocalTime start, FastingPlan plan) {
        return start.plusHours(plan.eatingHours);
    }

    /** 計算「下一次觸發」的 UTC 時間（排程用，避免 DST 問題） */
    public static Instant[] nextTriggersUtc(LocalTime startLocal, FastingPlan plan, ZoneId zone, Instant now) {
        var nowZ = ZonedDateTime.ofInstant(now, zone);
        var baseDate = nowZ.toLocalDate();

        // 找到下一個「吃窗開始」(startLocal) 與「吃窗結束」（startLocal+eatingHours）
        var startTodayZ = ZonedDateTime.of(baseDate, startLocal, zone);
        if (!startTodayZ.toInstant().isAfter(now)) {
            startTodayZ = startTodayZ.plusDays(1); // 今天已過 → 明天
        }
        var endLocal = endTimeLocal(startLocal, plan);
        // end 使用與 start 同一曆日的當地時間（依題意：以吃窗小時數加總）
        var endZ = ZonedDateTime.of(startTodayZ.toLocalDate(), endLocal, zone);
        if (!endZ.isAfter(startTodayZ)) {
            endZ = endZ.plusDays(1); // 若加總後落在前面，進位到隔日
        }
        return new Instant[] { startTodayZ.toInstant(), endZ.toInstant() };
    }
}
