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
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CaloshapeBaselineMigrationIT {

    private static final String FRESH_DATABASE = "caloshape_fresh";
    private static final String EXISTING_DATABASE = "caloshape_existing";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("caloshape_admin")
            .withUsername("root")
            .withPassword("root");

    @Test
    void createsCompleteSchemaFromEmptyDatabaseAndIsIdempotent() throws Exception {
        createDatabase(FRESH_DATABASE);
        Flyway flyway = flyway(FRESH_DATABASE);

        MigrateResult firstRun = flyway.migrate();
        MigrateResult secondRun = flyway.migrate();

        assertThat(firstRun.migrationsExecuted).isEqualTo(1);
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

        MigrateResult result = flyway(EXISTING_DATABASE).migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(tableNames(EXISTING_DATABASE))
                .contains("existing_schema_sentinel", "flyway_schema_history")
                .doesNotContain("users");
    }

    private static Flyway flyway(String databaseName) {
        return Flyway.configure()
                .dataSource(jdbcUrl(databaseName), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .javaMigrations(new V1__CaloshapeSchemaBaseline())
                .load();
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
