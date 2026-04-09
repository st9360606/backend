package com.calai.backend.foodlog.web.advice;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.foodlog.controller.FoodLogController;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.service.FoodLogDeleteService;
import com.calai.backend.foodlog.service.FoodLogHistoryService;
import com.calai.backend.foodlog.service.FoodLogOverrideService;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.UserDailyNutritionSummaryService;
import com.calai.backend.foodlog.web.error.ModelRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FoodLogRefused422StandaloneTest {

    @Test
    void getOne_modelRefused_returns422() throws Exception {
        // 1) mock 依賴（controller 建構子需要什麼，就 mock 什麼）
        AuthContext auth = mock(AuthContext.class);
        FoodLogService service = mock(FoodLogService.class);
        FoodLogDeleteService deleteService = mock(FoodLogDeleteService.class);
        FoodLogHistoryService historyService = mock(FoodLogHistoryService.class);
        FoodLogOverrideService overrideService = mock(FoodLogOverrideService.class);
        UserDailyNutritionSummaryService dailySummaryService = mock(UserDailyNutritionSummaryService.class);

        // 2) 建 controller
        FoodLogController controller = new FoodLogController(
                auth,
                service,
                deleteService,
                historyService,
                overrideService,
                dailySummaryService
        );

        // 3) standalone MockMvc + 掛上 Advice
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new FoodLogExceptionAdvice())
                .build();

        // 4) stub 行為：呼叫 getOne 時丟拒答 exception
        when(auth.requireUserId()).thenReturn(1L);
        when(service.getOne(eq(1L), eq("abc"), any()))
                .thenThrow(new ModelRefusedException(ProviderRefuseReason.SAFETY, "blocked"));

        // 5) assert
        mvc.perform(get("/api/v1/food-logs/abc").header("X-Request-Id", "rid-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MODEL_REFUSED"))
                .andExpect(jsonPath("$.refuseReason").value("SAFETY"));
    }
}
