package com.caloshape.backend.config;

import com.caloshape.backend.auth.security.AccessTokenFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final AccessTokenFilter accessTokenFilter;

    public SecurityConfig(AccessTokenFilter accessTokenFilter) {
        this.accessTokenFilter = accessTokenFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // 錯誤處理與 async error dispatch 不應再被 AuthorizationFilter 擋住。
                        .dispatcherTypeMatchers(
                                DispatcherType.ERROR,
                                DispatcherType.ASYNC,
                                DispatcherType.FORWARD
                        ).permitAll()
                        .requestMatchers("/error").permitAll()

                        // Minimal, detail-free readiness endpoint used by the deployment platform.
                        .requestMatchers(HttpMethod.GET, "/healthz").permitAll()

                        // CORS preflight 不需要 Bearer token。
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Auth API 公開。
                        .requestMatchers("/auth/**").permitAll()

                        // internal API 不走 App Bearer token，改由 X-Internal-Token 保護。
                        .requestMatchers("/internal/**").permitAll()

                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            if (res.isCommitted()) {
                                return;
                            }

                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Bearer token required\"}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            if (res.isCommitted()) {
                                return;
                            }

                            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}");
                        })
                )
                .addFilterBefore(accessTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
