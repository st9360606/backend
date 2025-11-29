package com.calai.backend.healthplan.controller;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.healthplan.dto.HealthPlanResponse;
import com.calai.backend.healthplan.dto.SaveHealthPlanRequest;
import com.calai.backend.healthplan.service.HealthPlanService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/health-plan")
public class HealthPlanController {

    private final AuthContext auth;
    private final HealthPlanService svc;

    public HealthPlanController(AuthContext auth, HealthPlanService svc) {
        this.auth = auth;
        this.svc = svc;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthPlanResponse> upsert(
            @RequestHeader(value = "X-Client-Timezone", required = false) String tz,
            @RequestBody SaveHealthPlanRequest req
    ) {
        Long uid = auth.requireUserId();
        return ResponseEntity.ok(svc.upsert(uid, req, tz));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthPlanResponse> get() {
        Long uid = auth.requireUserId();
        return ResponseEntity.ok(svc.get(uid));
    }
}
