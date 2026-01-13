package com.calai.backend.users.auto_generate_goals.web;

import com.calai.backend.users.auto_generate_goals.dto.MissingFieldsResponse;
import com.calai.backend.users.auto_generate_goals.exception.MissingFieldsException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ✅ AutoGoals 專屬：只處理「缺欄位」這種領域錯誤。
 * IllegalArgumentException 由全站 ApiExceptionHandler 統一處理，避免重疊。
 */
@RestControllerAdvice(basePackages = "com.calai.backend.users.auto_generate_goals")
public class AutoGoalsExceptionAdvice {

    @ExceptionHandler(MissingFieldsException.class)
    public ResponseEntity<MissingFieldsResponse> handleMissing(MissingFieldsException e) {
        return ResponseEntity.badRequest().body(
                new MissingFieldsResponse(
                        "AUTO_GOALS_MISSING_FIELDS",
                        e.getMissingFields(),
                        e.getMessage()
                )
        );
    }
}
