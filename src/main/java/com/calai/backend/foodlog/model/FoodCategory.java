package com.calai.backend.foodlog.model;

/**
 * Canonical category for backend rule processing.
 * 原則：
 * - 給規則與後端判斷使用
 * - 不直接依賴多語系 foodName 做主規則
 * - 先保持簡單，後續再擴充
 */
public enum FoodCategory {
    UNKNOWN,
    MEAL,
    SNACK,
    BEVERAGE,
    SOUP,
    DESSERT,
    SAUCE,
    PACKAGED_FOOD
}
