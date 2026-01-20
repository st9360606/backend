package com.calai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile({"prod", "dev", "local"})
public class ActuatorSecurityConfig {

    @Bean
    @Order(0) // ✅ 優先匹配 actuator
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(reg -> reg.anyRequest().hasRole("ACTUATOR"))
                .httpBasic(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public UserDetailsService actuatorUsers(
            @Value("${app.actuator.user:actuator}") String user,
            @Value("${app.actuator.pass:change-me}") String pass
    ) {
        return new InMemoryUserDetailsManager(
                User.withUsername(user)
                        .password("{noop}" + pass) // MVP：先 noop；上線可改成 bcrypt
                        .roles("ACTUATOR")
                        .build()
        );
    }
}
