package com.calai.backend.workout.controller;

import com.calai.backend.workout.dto.*;
import com.calai.backend.workout.service.WorkoutService;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {

    private final WorkoutService svc;

    public WorkoutController(WorkoutService svc) {
        this.svc = svc;
    }

    private ZoneId parseZone(String tz) {
        try {
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz);
        } catch (Exception ignored) {}
        return ZoneId.systemDefault();
    }

    /** WS2 */
    @PostMapping("/estimate")
    public EstimateResponse estimate(@RequestBody EstimateRequest req) {
        return svc.estimate(req.text());
    }

    /** WS3/WS4 */
    @PostMapping("/log")
    public LogWorkoutResponse log(
            @RequestBody LogWorkoutRequest req,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return svc.log(req, parseZone(tz));
    }

    /** WS4, WS3(Home 卡片同步) */
    @GetMapping("/today")
    public TodayWorkoutResponse today(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return svc.today(parseZone(tz));
    }

    /** WS1: 底部預設運動列表 */
    @GetMapping("/presets")
    public PresetListResponse presets() {
        // 簡化：全部 dict 直接丟給前端顯示
        // （Service 邏輯很單純，可直接搬到這裡或留在 svc）
        return svc.presets(); // 下面會補 svc.presets()
    }

    /** WS6: 使用者刪除一筆 session (合規) */
    @DeleteMapping("/{sessionId}")
    public TodayWorkoutResponse deleteSession(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz
    ) {
        return svc.deleteSession(sessionId, parseZone(tz));
    }
}
