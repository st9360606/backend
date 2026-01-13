package com.calai.backend.foodlog.dto;

/**
 * PENDING：AI 還在跑（或排隊）
 * DRAFT：AI 給了結果，但使用者可能要改（份量、食物名、營養數值）
 * SAVED：使用者按下「保存」後，才算正式計入日彙總/統計
 * FAILED：provider 失敗，給 retry（或提示使用者重試/改用其他入口）
 * DELETED：使用者刪除或系統刪除
 */
public enum FoodLogStatus {
    PENDING, DRAFT, SAVED, FAILED, DELETED
}
