package com.calai.backend.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfig {
    /**
     * 平常由 Spring Boot 自動配置產生；若 IDE 誤判或某些環境缺少屬性，這個保底 Bean 會讓注入成立。
     * 會沿用 application.yml 的 spring.mail.* 設定（由 Boot 填入 JavaMailSenderImpl）。
     */
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender javaMailSender() {
        return new JavaMailSenderImpl();
    }
}
