package com.calai.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 你的實際目錄：<working-dir>/uploads/weight-photos
        Path uploadDir = Paths.get("uploads/weight-photos").toAbsolutePath().normalize();

        // 必須是 file: URI，且通常建議尾巴要有 /
        String location = uploadDir.toUri().toString(); // e.g. file:/C:/.../uploads/weight-photos/

        registry.addResourceHandler("/static/weight-photos/**")
                .addResourceLocations(location)
                .setCachePeriod(3600); // 可選：快取 1 小時（MVP OK）
    }
}
