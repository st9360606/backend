package com.calai.backend.foodlog.repo;

public interface FoodLogDailyNutritionAggregate {
    String getTimezone();
    Double getTotalKcal();
    Double getTotalProteinG();
    Double getTotalCarbsG();
    Double getTotalFatsG();
    Double getTotalFiberG();
    Double getTotalSugarG();
    Double getTotalSodiumMg();
    Integer getMealCount();
}
