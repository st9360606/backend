package com.calai.backend;


import com.calai.backend.workout.service.WorkoutTextParser;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// 純單元測試，不走 Spring / DB，直接打你的解析器
class WorkoutTextParserSmokeTest {

    @Test
    void parse_should_handle_zh_minutes_and_english_token() {
        var p = WorkoutTextParser.parse("45分 running", Locale.TAIWAN);
        assertNotNull(p);
        assertEquals(45, p.minutes());
        // token 允許「running」或「running_jogging」等
    }
}
