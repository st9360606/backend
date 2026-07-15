package com.caloshape.backend.config.database;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

@Component
public final class V1__CaloshapeSchemaBaseline extends BaseJavaMigration {

    private static final List<String> SCHEMA_RESOURCES = List.of(
            "sql/caloshape/users.sql",
            "sql/caloshape/email_login_codes.sql",
            "sql/caloshape/barcode_lookup_cache.sql",
            "sql/caloshape/email_outbox.sql",
            "sql/caloshape/entitlement_transfer_audit.sql",
            "sql/caloshape/image_blobs.sql",
            "sql/caloshape/usage_counters.sql",
            "sql/caloshape/user_ai_quota_state.sql",
            "sql/caloshape/user_daily_activity.sql",
            "sql/caloshape/user_daily_nutrition_summary.sql",
            "sql/caloshape/user_daily_workout_summary.sql",
            "sql/caloshape/user_entitlements.sql",
            "sql/caloshape/user_notifications.sql",
            "sql/caloshape/user_referral_codes.sql",
            "sql/caloshape/user_water_daily.sql",
            "sql/caloshape/weight_history.sql",
            "sql/caloshape/weight_timeseries.sql",
            "sql/caloshape/membership_reward_ledger.sql",
            "sql/caloshape/referral_claims.sql",
            "sql/caloshape/referral_case_snapshot.sql",
            "sql/caloshape/referral_risk_signals.sql",
            "sql/caloshape/account_deletion_requests.sql",
            "sql/caloshape/auth_tokens.sql",
            "sql/caloshape/fasting_plan.sql",
            "sql/caloshape/user_profiles.sql",
            "sql/caloshape/food_logs.sql",
            "sql/caloshape/food_log_requests.sql",
            "sql/caloshape/food_log_overrides.sql",
            "sql/caloshape/food_log_tasks.sql",
            "sql/caloshape/deletion_jobs.sql",
            "sql/caloshape/workout_dictionary.sql",
            "sql/caloshape/workout_alias.sql",
            "sql/caloshape/workout_alias_event.sql",
            "sql/caloshape/workout_session.sql"
    );

    @Override
    public Integer getChecksum() {
        CRC32 checksum = new CRC32();
        for (String resourcePath : SCHEMA_RESOURCES) {
            updateChecksum(checksum, resourcePath.getBytes(StandardCharsets.UTF_8));
            try (InputStream input = new ClassPathResource(resourcePath).getInputStream()) {
                updateChecksum(checksum, input.readAllBytes());
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot checksum schema resource: " + resourcePath, exception);
            }
        }
        return (int) checksum.getValue();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        for (String resourcePath : SCHEMA_RESOURCES) {
            ScriptUtils.executeSqlScript(
                    context.getConnection(),
                    new ClassPathResource(resourcePath)
            );
        }
    }

    static List<String> schemaResources() {
        return SCHEMA_RESOURCES;
    }

    private static void updateChecksum(CRC32 checksum, byte[] bytes) {
        checksum.update(bytes, 0, bytes.length);
    }
}
