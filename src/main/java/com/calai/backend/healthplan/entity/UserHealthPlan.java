package com.calai.backend.healthplan.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "user_health_plan")
public class UserHealthPlan {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // meta
    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "calc_version", nullable = false, length = 32)
    private String calcVersion;

    @Column(name = "client_timezone", length = 64)
    private String clientTimezone;

    // inputs
    @Column(name = "goal_key", length = 32)
    private String goalKey;

    @Column(length = 16)
    private String gender;

    private Integer age;

    @Column(name = "height_cm", precision = 5, scale = 2)
    private BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "goal_weight_kg", precision = 5, scale = 2)
    private BigDecimal goalWeightKg;

    @Column(name = "unit_preference", nullable = false, length = 8)
    private String unitPreference;

    @Column(name = "workouts_per_week")
    private Integer workoutsPerWeek;

    // results
    @Column(nullable = false)
    private Integer kcal;

    @Column(name = "carbs_g", nullable = false)
    private Integer carbsG;

    @Column(name = "protein_g", nullable = false)
    private Integer proteinG;

    @Column(name = "fat_g", nullable = false)
    private Integer fatG;

    @Column(name = "water_ml", nullable = false)
    private Integer waterMl;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal bmi;

    @Column(name = "bmi_class", nullable = false, length = 16)
    private String bmiClass;

    // --- getters/setters（先用最傳統，確保可編譯） ---
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getCalcVersion() { return calcVersion; }
    public void setCalcVersion(String calcVersion) { this.calcVersion = calcVersion; }

    public String getClientTimezone() { return clientTimezone; }
    public void setClientTimezone(String clientTimezone) { this.clientTimezone = clientTimezone; }

    public String getGoalKey() { return goalKey; }
    public void setGoalKey(String goalKey) { this.goalKey = goalKey; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public BigDecimal getHeightCm() { return heightCm; }
    public void setHeightCm(BigDecimal heightCm) { this.heightCm = heightCm; }

    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }

    public BigDecimal getGoalWeightKg() { return goalWeightKg; }
    public void setGoalWeightKg(BigDecimal goalWeightKg) { this.goalWeightKg = goalWeightKg; }

    public String getUnitPreference() { return unitPreference; }
    public void setUnitPreference(String unitPreference) { this.unitPreference = unitPreference; }

    public Integer getWorkoutsPerWeek() { return workoutsPerWeek; }
    public void setWorkoutsPerWeek(Integer workoutsPerWeek) { this.workoutsPerWeek = workoutsPerWeek; }

    public Integer getKcal() { return kcal; }
    public void setKcal(Integer kcal) { this.kcal = kcal; }

    public Integer getCarbsG() { return carbsG; }
    public void setCarbsG(Integer carbsG) { this.carbsG = carbsG; }

    public Integer getProteinG() { return proteinG; }
    public void setProteinG(Integer proteinG) { this.proteinG = proteinG; }

    public Integer getFatG() { return fatG; }
    public void setFatG(Integer fatG) { this.fatG = fatG; }

    public Integer getWaterMl() { return waterMl; }
    public void setWaterMl(Integer waterMl) { this.waterMl = waterMl; }

    public BigDecimal getBmi() { return bmi; }
    public void setBmi(BigDecimal bmi) { this.bmi = bmi; }

    public String getBmiClass() { return bmiClass; }
    public void setBmiClass(String bmiClass) { this.bmiClass = bmiClass; }
}
