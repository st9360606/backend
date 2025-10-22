package com.calai.backend.fasting.entity;

public enum FastingPlan {
    P14_10("14:10", 10),
    P16_8("16:8", 8),
    P20_4("20:4", 4),
    P22_2("22:2", 2),
    P12_12("12:12", 12),
    P18_6("18:6", 6);

    public final String code;
    public final int eatingHours;

    FastingPlan(String code, int eatingHours) {
        this.code = code;
        this.eatingHours = eatingHours;
    }

    public static FastingPlan of(String code) {
        for (var p : values()) if (p.code.equals(code)) return p;
        throw new IllegalArgumentException("Unknown plan_code: " + code);
    }
}

