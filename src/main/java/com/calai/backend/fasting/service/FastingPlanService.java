package com.calai.backend.fasting.service;

import com.calai.backend.fasting.dto.*;
import com.calai.backend.fasting.entity.FastingPlan;
import com.calai.backend.fasting.entity.FastingPlanEntity;
import com.calai.backend.fasting.repo.FastingPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service @RequiredArgsConstructor
public class FastingPlanService {
    private final FastingPlanRepository repo;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public Optional<FastingPlanDto> find(Long userId) {
        return repo.findByUserId(userId).map(this::toDto);
    }

    /** 查不到時丟 404（由 Controller 捕捉轉 404），不丟 RuntimeException("404") */
    public FastingPlanDto getOr404(Long userId) {
        return find(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public FastingPlanDto upsert(Long userId, UpsertFastingPlanReq req) {
        var plan = FastingPlan.of(req.planCode());
        var start = LocalTime.parse(req.startTime(), HM);
        var end = start.plusHours(plan.eatingHours);
        var tz = (req.timeZone() == null || req.timeZone().isBlank())
                ? ZoneId.systemDefault().getId()
                : req.timeZone();

        var e = repo.findByUserId(userId).orElseGet(FastingPlanEntity::new);
        if (e.getId() == null) e.setUserId(userId);
        e.setPlanCode(plan.code);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setEnabled(Boolean.TRUE.equals(req.enabled()));
        e.setTimeZone(tz);
        repo.save(e);
        return toDto(e);
    }

    /** 註冊完成時呼叫：若不存在才建立預設 */
    @Transactional
    public FastingPlanDto ensureDefaultIfMissing(Long userId, String timeZone) {
        return find(userId).orElseGet(() ->
                upsert(userId, new UpsertFastingPlanReq("16:8", "09:00", false,
                        (timeZone == null || timeZone.isBlank()) ? ZoneId.systemDefault().getId() : timeZone))
        );
    }

    public NextTriggersResp nextTriggers(String planCode, String startTime, String timeZone, Instant now) {
        var plan = FastingPlan.of(planCode);
        var zone = ZoneId.of(timeZone);
        var start = LocalTime.parse(startTime, HM);

        var nowZ = ZonedDateTime.ofInstant(now, zone);
        var startToday = nowZ.withHour(start.getHour()).withMinute(start.getMinute()).withSecond(0).withNano(0);
        var nextStart = startToday.isAfter(nowZ) ? startToday : startToday.plusDays(1);
        var nextEnd = nextStart.plusHours(plan.eatingHours);

        return new NextTriggersResp(nextStart.toInstant().toString(), nextEnd.toInstant().toString());
    }

    private FastingPlanDto toDto(FastingPlanEntity e) {
        return new FastingPlanDto(
                e.getPlanCode(),
                HM.format(e.getStartTime()),
                HM.format(e.getEndTime()),
                e.isEnabled(),
                e.getTimeZone()
        );
    }
}
