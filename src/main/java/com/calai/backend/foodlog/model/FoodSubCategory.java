package com.calai.backend.foodlog.model;

/**
 * Canonical sub-category for backend rule processing.
 * 先只放你目前低熱量放寬規則需要的分類，
 * 後續再逐步擴充，避免一開始 enum 爆炸。
 */
public enum FoodSubCategory {
    UNKNOWN,

    // beverage
    TEA,
    COFFEE,
    WATER,
    SPARKLING_WATER,
    JUICE,
    SODA,
    MILK_DRINK,

    // soup
    BROTH,
    CREAM_SOUP
}
