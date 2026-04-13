package com.calai.backend.workout.controller;

import com.calai.backend.workout.dto.*;
import com.calai.backend.workout.service.WorkoutService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {

    private final WorkoutService workoutService;

    public WorkoutController(WorkoutService workoutService) {
        this.workoutService = workoutService;
    }

    /**
     * 規則：
     * 1. header 缺省/空白 -> fallback UTC（穩定，不綁 server machine timezone）
     * 2. header 有值但非法 -> 400
     */
    private ZoneId parseZone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid timezone: " + tz);
        }
    }

    /** WS1: 預設運動清單（每 30 分鐘 kcal 已依用戶體重計算） */
    @GetMapping("/presets")
    public PresetListResponse presets() {
        return workoutService.presets();
    }

    /** WS2: 自由文字估算 */
    @PostMapping("/estimate")
    public EstimateResponse estimate(@RequestBody EstimateRequest req) {
        return workoutService.estimate(req.text());
    }

    /** WS3: 寫入一筆運動紀錄（並回傳今天加總） */
    @PostMapping("/log")
    public LogWorkoutResponse log(
            @RequestBody LogWorkoutRequest req,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return workoutService.log(req, parseZone(tz));
    }

    /** WS4: 取得今天摘要（查詢前依用戶時區清理 >7 天舊資料） */
    @GetMapping("/today")
    public TodayWorkoutResponse today(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return workoutService.today(parseZone(tz));
    }

    /** 供 App fallback 取得目前用戶體重（kg） */
    @GetMapping("/me/weight")
    public WeightDto myWeight() {
        return new WeightDto(workoutService.currentUserWeightKg());
    }

    /** WS6: 刪除一筆 session（合規）→ 回傳今天最新加總 */
    @DeleteMapping("/{sessionId}")
    public TodayWorkoutResponse deleteSession(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return workoutService.deleteSession(sessionId, parseZone(tz));
    }

    @GetMapping("/progress/weekly")
    public WorkoutWeeklyProgressResponse weeklyProgress(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return workoutService.weeklyProgress(parseZone(tz));
    }
}
