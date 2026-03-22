package com.calai.backend.testsupport.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test") // ✅ 保險：不靠檔案也能強制 test
class TestProfileSmokeTest {

    @Autowired Environment env;

    @Test
    void should_use_test_profile() {
        assertThat(env.getActiveProfiles()).contains("test");
    }
}
