package com.calai.backend.healthplan.service;

import com.calai.backend.healthplan.dto.HealthPlanResponse;
import com.calai.backend.healthplan.dto.SaveHealthPlanRequest;
import com.calai.backend.healthplan.entity.UserHealthPlan;
import com.calai.backend.healthplan.repo.UserHealthPlanRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class HealthPlanService {

    private final UserHealthPlanRepo repo;

    public HealthPlanService(UserHealthPlanRepo repo) {
        this.repo = repo;
    }

    @Transactional
    public HealthPlanResponse upsert(Long userId, SaveHealthPlanRequest req, String clientTimezone) {
        validate(req);

        UserHealthPlan e = repo.findById(userId).orElseGet(UserHealthPlan::new);
        e.setUserId(userId);

        // meta
        e.setSource(nz(req.source(), "ONBOARDING"));
        e.setCalcVersion(nz(req.calcVersion(), "healthcalc_v1"));
        e.setClientTimezone(clientTimezone);

        // inputs
        e.setGoalKey(req.goalKey());
        e.setGender(req.gender());
        e.setAge(req.age());
        e.setHeightCm(req.heightCm());
        e.setWeightKg(req.weightKg());
        e.setGoalWeightKg(req.goalWeightKg());
        e.setUnitPreference(nz(req.unitPreference(), "KG"));
        e.setWorkoutsPerWeek(req.workoutsPerWeek());

        // results
        e.setKcal(req.kcal());
        e.setCarbsG(req.carbsG());
        e.setProteinG(req.proteinG());
        e.setFatG(req.fatG());
        e.setWaterMl(req.waterMl());
        e.setBmi(req.bmi());
        e.setBmiClass(req.bmiClass());

        repo.save(e);

        return new HealthPlanResponse(
                userId,
                e.getSource(),
                e.getCalcVersion(),
                e.getKcal(),
                e.getCarbsG(),
                e.getProteinG(),
                e.getFatG(),
                e.getWaterMl(),
                e.getBmi(),
                e.getBmiClass()
        );
    }

    @Transactional(readOnly = true)
    public HealthPlanResponse get(Long userId) {
        UserHealthPlan e = repo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("health_plan_not_found"));
        return new HealthPlanResponse(
                userId,
                e.getSource(),
                e.getCalcVersion(),
                e.getKcal(),
                e.getCarbsG(),
                e.getProteinG(),
                e.getFatG(),
                e.getWaterMl(),
                e.getBmi(),
                e.getBmiClass()
        );
    }

    private void validate(SaveHealthPlanRequest req) {
        // 你可以依產品調整範圍；先做「防爆炸」校驗
        requireNotNull(req.kcal(), "kcal");
        requireNotNull(req.carbsG(), "carbsG");
        requireNotNull(req.proteinG(), "proteinG");
        requireNotNull(req.fatG(), "fatG");
        requireNotNull(req.waterMl(), "waterMl");
        requireNotNull(req.bmi(), "bmi");
        requireNotNull(req.bmiClass(), "bmiClass");

        if (req.kcal() < 800 || req.kcal() > 6000) throw bad("kcal_out_of_range");
        if (req.carbsG() < 0 || req.carbsG() > 1200) throw bad("carbs_out_of_range");
        if (req.proteinG() < 0 || req.proteinG() > 600) throw bad("protein_out_of_range");
        if (req.fatG() < 0 || req.fatG() > 400) throw bad("fat_out_of_range");
        if (req.waterMl() < 0 || req.waterMl() > 10000) throw bad("water_out_of_range");

        BigDecimal bmi = req.bmi();
        if (bmi.compareTo(new BigDecimal("10")) < 0 || bmi.compareTo(new BigDecimal("80")) > 0) {
            throw bad("bmi_out_of_range");
        }
    }

    private static void requireNotNull(Object v, String field) {
        if (v == null) throw bad(field + "_required");
    }
    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(msg);
    }
    private static String nz(String v, String dft) {
        return (v == null || v.isBlank()) ? dft : v;
    }
}
