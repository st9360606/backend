package com.calai.backend.foodlog;

import com.calai.backend.auth.security.AuthContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ✅ 測試用：固定登入使用者 id=1
 * 避免測試耦合 JWT/Filter。
 */
@TestConfiguration
public class TestAuthConfig {

    @Bean
    @Primary
    public AuthContext testAuthContext() {
        // 依你 AuthContext 型別調整：
        // - 若 AuthContext 是 interface：直接 new 實作
        // - 若 AuthContext 是 class：也可用匿名子類或寫一個 TestAuthContext extends AuthContext
        return new AuthContext() {
            @Override
            public Long requireUserId() {
                return 1L;
            }
        };
    }
}
