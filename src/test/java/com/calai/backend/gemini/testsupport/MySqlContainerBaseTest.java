package com.calai.backend.gemini.testsupport;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
// ✅ 關鍵：避免 Spring Context 被 cache 到 JVM 結束才關
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

        // ✅ 關鍵：不要 create-drop（避免 close 時還要連 DB 做 drop）
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");

        // ✅ 關鍵：就算遇到 DB 不可用，也不要卡 30 秒
        r.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        r.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        r.add("spring.datasource.hikari.minimum-idle", () -> "0");
        r.add("spring.datasource.hikari.idle-timeout", () -> "10000");
        r.add("spring.datasource.hikari.max-lifetime", () -> "30000");

        // ✅ 用來抓 connection leak（可先開幾天，穩了再關）
        r.add("spring.datasource.hikari.leak-detection-threshold", () -> "10000"); //2000ms 很敏感，測試稍慢就可能噴警告；穩定後你可以：調到 5000~10000，或直接移除這個 property
    }
}
