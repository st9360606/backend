package com.calai.backend.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
@EnableConfigurationProperties(org.springframework.boot.autoconfigure.mail.MailProperties.class)
public class MailConfig {
    @Bean
    public JavaMailSender javaMailSender(org.springframework.boot.autoconfigure.mail.MailProperties p) {
        var s = new org.springframework.mail.javamail.JavaMailSenderImpl();
        s.setHost(p.getHost());
        s.setPort(p.getPort());
        s.setUsername(p.getUsername());
        s.setPassword(p.getPassword());
        if (p.getDefaultEncoding() != null) s.setDefaultEncoding(p.getDefaultEncoding().name());
        s.getJavaMailProperties().putAll(p.getProperties());
        return s;
    }
}

