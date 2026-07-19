package com.caloshape.backend.config.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CaloshapeBaselineMigrationIT {

    private static final String FRESH_DATABASE = "caloshape_fresh";
    private static final String EXISTING_DATABASE = "caloshape_existing";
    private static final String RAW_TOKEN_DATABASE = "caloshape_raw_tokens";
    private static final String REHEARSAL_DATABASE = "caloshape_rehearsal";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("caloshape_admin")
            .withUsername("root")
            .withPassword("root");

    @Test
    void createsCompleteSchemaFromEmptyDatabaseAndIsIdempotent() throws Exception {
        createDatabase(FRESH_DATABASE);
        Flyway flyway = flywayWithCurrentMigrations(FRESH_DATABASE);

        MigrateResult firstRun = flyway.migrate();
        MigrateResult secondRun = flyway.migrate();

        assertThat(firstRun.migrationsExecuted).isEqualTo(2);
        assertThat(secondRun.migrationsExecuted).isZero();
        assertThat(tableNames(FRESH_DATABASE))
                .containsAll(expectedApplicationTables())
                .contains("flyway_schema_history");
        assertThat(rowCount(FRESH_DATABASE, "workout_dictionary")).isPositive();
        assertThat(rowCount(FRESH_DATABASE, "workout_alias")).isPositive();
    }

    @Test
    void baselinesExistingSchemaWithoutExecutingInitialization() throws Exception {
        createDatabase(EXISTING_DATABASE);
        try (Connection connection = connection(EXISTING_DATABASE);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE existing_schema_sentinel (id BIGINT PRIMARY KEY)");
        }

        MigrateResult result = flywayWithV1Only(EXISTING_DATABASE).migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(tableNames(EXISTING_DATABASE))
                .contains("existing_schema_sentinel", "flyway_schema_history")
                .doesNotContain("users");
    }

    @Test
    void hashesRawAuthTokensWhenMigratingAnExistingV1DatabaseAndIsIdempotent() throws Exception {
        createDatabase(RAW_TOKEN_DATABASE);
        flywayWithV1Only(RAW_TOKEN_DATABASE).migrate();

        String rawAccessToken = "access-token-created-before-v2";
        String rawReplacementToken = "replacement-token-created-before-v2";
        insertRawAuthToken(RAW_TOKEN_DATABASE, rawAccessToken, rawReplacementToken);

        Flyway flyway = flywayWithCurrentMigrations(RAW_TOKEN_DATABASE);
        MigrateResult firstV2Run = flyway.migrate();
        MigrateResult secondV2Run = flyway.migrate();

        assertThat(firstV2Run.migrationsExecuted).isEqualTo(1);
        assertThat(secondV2Run.migrationsExecuted).isZero();
        assertThat(authTokenValue(RAW_TOKEN_DATABASE, "token"))
                .isEqualTo(sha256(rawAccessToken))
                .isNotEqualTo(rawAccessToken);
        assertThat(authTokenValue(RAW_TOKEN_DATABASE, "replaced_by"))
                .isEqualTo(sha256(rawReplacementToken))
                .isNotEqualTo(rawReplacementToken);
    }

    @Test
    void backupRestoreRehearsalRestoresThePreChangeDatabaseState() throws Exception {
        createDatabase(REHEARSAL_DATABASE);
        flywayWithCurrentMigrations(REHEARSAL_DATABASE).migrate();
        insertUser(REHEARSAL_DATABASE, "before-backup@example.com");

        execInContainer("mysqldump -uroot -proot --databases " + REHEARSAL_DATABASE + " > /tmp/caloshape-rehearsal.sql");

        insertUser(REHEARSAL_DATABASE, "after-backup@example.com");
        assertThat(userExists(REHEARSAL_DATABASE, "after-backup@example.com")).isTrue();

        execInContainer("mysql -uroot -proot -e 'DROP DATABASE " + REHEARSAL_DATABASE + "'");
        execInContainer("mysql -uroot -proot < /tmp/caloshape-rehearsal.sql");

        assertThat(userExists(REHEARSAL_DATABASE, "before-backup@example.com")).isTrue();
        assertThat(userExists(REHEARSAL_DATABASE, "after-backup@example.com")).isFalse();
        assertThat(tableNames(REHEARSAL_DATABASE)).contains("flyway_schema_history");
        assertThat(rowCount(REHEARSAL_DATABASE, "flyway_schema_history")).isEqualTo(2);
    }

    private static Flyway flywayWithCurrentMigrations(String databaseName) {
        return Flyway.configure()
                .dataSource(jdbcUrl(databaseName), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .javaMigrations(
                        new V1__CaloshapeSchemaBaseline(),
                        new V2__HashStoredAuthTokens()
                )
                .load();
    }

    private static Flyway flywayWithV1Only(String databaseName) {
        return Flyway.configure()
                .dataSource(jdbcUrl(databaseName), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .javaMigrations(new V1__CaloshapeSchemaBaseline())
                .load();
    }

    private static void insertRawAuthToken(
            String databaseName,
            String rawAccessToken,
            String rawReplacementToken
    ) throws SQLException {
        try (Connection connection = connection(databaseName);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (email, provider, status)
                    VALUES ('migration-test@example.com', 'EMAIL', 'ACTIVE')
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO auth_tokens (
                        token, user_id, type, expires_at, replaced_by
                    ) VALUES (
                        ?, 1, 'ACCESS', ?, ?
                    )
                    """)) {
                insert.setString(1, rawAccessToken);
                insert.setTimestamp(2, java.sql.Timestamp.from(Instant.now().plusSeconds(3_600)));
                insert.setString(3, rawReplacementToken);
                insert.executeUpdate();
            }
        }
    }

    private static String authTokenValue(String databaseName, String column) throws SQLException {
        try (Connection connection = connection(databaseName);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + column + " FROM auth_tokens LIMIT 1")) {
            result.next();
            return result.getString(1);
        }
    }

    private static void insertUser(String databaseName, String email) throws SQLException {
        try (Connection connection = connection(databaseName);
             var insert = connection.prepareStatement("""
                     INSERT INTO users (email, provider, status)
                     VALUES (?, 'EMAIL', 'ACTIVE')
                     """)) {
            insert.setString(1, email);
            insert.executeUpdate();
        }
    }

    private static boolean userExists(String databaseName, String email) throws SQLException {
        try (Connection connection = connection(databaseName);
             var query = connection.prepareStatement("SELECT 1 FROM users WHERE email=?")) {
            query.setString(1, email);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void execInContainer(String shellCommand) throws Exception {
        var result = MYSQL.execInContainer("sh", "-c", shellCommand);
        assertThat(result.getExitCode())
                .withFailMessage("Container command failed: %s%n%s", shellCommand, result.getStderr())
                .isZero();
    }

    private static String sha256(String raw) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    private static void createDatabase(String databaseName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + databaseName);
        }
    }

    private static Connection connection(String databaseName) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl(databaseName),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }

    private static String jdbcUrl(String databaseName) {
        return MYSQL.getJdbcUrl().replace("/caloshape_admin", "/" + databaseName);
    }

    private static Set<String> tableNames(String databaseName) throws SQLException {
        try (Connection connection = connection(databaseName);
             ResultSet tables = connection.getMetaData()
                     .getTables(databaseName, null, "%", new String[]{"TABLE"})) {
            Set<String> names = new HashSet<>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private static long rowCount(String databaseName, String tableName) throws SQLException {
        try (Connection connection = connection(databaseName);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Set<String> expectedApplicationTables() {
        return V1__CaloshapeSchemaBaseline.schemaResources().stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1, path.length() - ".sql".length()))
                .collect(Collectors.toSet());
    }
}
