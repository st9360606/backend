package com.calai.backend.config.swagger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ✅ 只在 dev/test 放行 swagger
 * - /swagger-ui.html
 * - /swagger-ui/**
 * - /v3/api-docs/**
 * - /v3/api-docs.yaml
 */
@Configuration
@Profile({"dev", "test", "local"})
public class SwaggerDevSecurityConfig {

    @Bean
    @Order(0) // ✅ 讓它優先匹配 swagger 相關路徑
    public SecurityFilterChain swaggerChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml"
                )
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
