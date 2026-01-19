package com.calai.backend.config.swagger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ✅ PROD：讓 swagger 路徑「看起來不存在」（404）
 * - 不影響 dev/test/local（那些 profile 有 SwaggerDevSecurityConfig 放行）
 */
@Configuration
@Profile("prod")
public class SwaggerProdBlockConfig {

    @Bean
    @Order(0) // ✅ 讓它優先匹配 swagger 相關路徑
    public SecurityFilterChain swaggerBlockChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml"
                )
                // ✅ 直接回 404（不透露需要登入/權限）
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> res.sendError(HttpStatus.NOT_FOUND.value()))
                        .accessDeniedHandler((req, res, e) -> res.sendError(HttpStatus.NOT_FOUND.value()))
                );

        return http.build();
    }
}
