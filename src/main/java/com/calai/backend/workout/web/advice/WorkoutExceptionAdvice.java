package com.calai.backend.workout.web.advice;

import com.calai.backend.common.web.RequestIdFilter;
import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import com.calai.backend.foodlog.web.error.SubscriptionRequiredException;
import com.calai.backend.workout.controller.WorkoutController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WorkoutController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkoutExceptionAdvice {

    @ExceptionHandler(SubscriptionRequiredException.class)
    public ResponseEntity<FoodLogErrorResponse> handleSubscriptionRequired(
            SubscriptionRequiredException e,
            HttpServletRequest req
    ) {
        return ResponseEntity.status(402)
                .body(new FoodLogErrorResponse(
                        "SUBSCRIPTION_REQUIRED",
                        safeMsgOrCode(e),
                        RequestIdFilter.getOrCreate(req),
                        e.clientAction(),
                        null
                ));
    }

    private static String safeMsgOrCode(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? "SUBSCRIPTION_REQUIRED" : message;
    }
}
