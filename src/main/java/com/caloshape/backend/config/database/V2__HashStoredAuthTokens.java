package com.caloshape.backend.config.database;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.Statement;

@Component
public final class V2__HashStoredAuthTokens extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.executeUpdate("""
                    UPDATE auth_tokens
                       SET token = LOWER(SHA2(token, 256)),
                           replaced_by = CASE
                               WHEN replaced_by IS NULL THEN NULL
                               ELSE LOWER(SHA2(replaced_by, 256))
                           END
                    """);
        }
    }
}
