package com.calai.backend.gemini.testsupport;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ✅ 需要 MySQL 真實行為的整合測試才繼承
 * - Docker 不可用時，自動 skip（避免本機沒開 Docker 就整包 fail）
 * - 強制 test profile（避免 activeProfiles 空造成 bean 組合漂移）
 */
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class MySqlContainerBaseTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("calai")
            .withUsername("root")
            .withPassword("root");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }
}
