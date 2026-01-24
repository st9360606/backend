package com.calai.backend.accountdelete.service;

import com.calai.backend.common.storage.LocalImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ✅ UserDataPurgeDao：刪除帳號第二階段（背景 purge）用的「分批刪除 DAO」
 * 設計重點：
 * 1) 一律走「分批刪」(LIMIT)，避免一次刪太多造成鎖太久/timeout
 * 2) MySQL 的 multi-table delete（DELETE t FROM ... JOIN ...）不支援 LIMIT
 *    → 所以「關聯 food_logs 的表」要改成「單表 DELETE + IN (subquery)」方式，才可 LIMIT ✅
 */
@RequiredArgsConstructor
@Service
public class UserDataPurgeDao {

    private final JdbcTemplate jdbc;
    private final LocalImageStorage localImageStorage;

    private static final java.util.Set<String> USER_TABLE_WHITELIST = java.util.Set.of(
            "food_log_requests",
            "usage_counters",
            "user_entitlements",
            "user_daily_activity",
            "user_water_daily",
            "fasting_plan",
            "workout_session",
            "workout_alias_event",
            "weight_timeseries",
            "auth_tokens",
            "user_profiles",
            "image_blobs"
    );

    /**
     * ✅ 直接有 user_id 的表：DELETE ... WHERE user_id=? LIMIT ?
     * 適用：
     * - usage_counters / user_entitlements / auth_tokens / workout_session / ...
     * ⚠️ 注意：
     * - table 參數必須是「你程式碼寫死的白名單」表名，不要把使用者輸入帶進來（避免 SQL Injection）
     */
    public int deleteByUserId(String table, Long userId, int limit) {
        if (!USER_TABLE_WHITELIST.contains(table)) {
            // ✅ 安全：不在白名單直接不刪
            return 0;
        }
        String sql = "DELETE FROM " + table + " WHERE user_id = ? LIMIT ?";
        return jdbc.update(sql, userId, limit);
    }

    /**
     * ✅ 沒有 user_id 但可透過 food_logs 關聯的表（例如 food_log_tasks / food_log_overrides）
     * ✅ MySQL 兼容版（可 LIMIT）：
     * - 不能用：DELETE t FROM t JOIN ... LIMIT ?  （MySQL 會 syntax error）
     * - 改用：DELETE FROM t WHERE fk IN (SELECT id FROM (SELECT ... LIMIT ?) x) LIMIT ?
     * 參數：
     * - table：要刪的表名（food_log_tasks / food_log_overrides）
     * - fkCol：外鍵欄位名（通常是 food_log_id）
     */
    public int deleteByFoodLogFkForUser(String table, String fkCol, Long userId, int limit) {

        // ✅ 關鍵技巧：subquery 外面要再包一層 SELECT ... FROM (...) x
        // 否則 MySQL 可能報：You can't specify target table for update in FROM clause
        String sql = """
            DELETE FROM %s
            WHERE %s IN (
                SELECT id FROM (
                    SELECT id
                    FROM food_logs
                    WHERE user_id = ?
                    ORDER BY created_at_utc ASC
                    LIMIT ?
                ) x
            )
            LIMIT ?
        """.formatted(table, fkCol);

        // 參數順序對應：userId, (subquery limit), (outer delete limit)
        return jdbc.update(sql, userId, limit, limit);
    }

    /**
     * ✅ weight_history：先刪照片檔（本機 LocalImageStorage），再刪 DB row
     * 適用：
     * - 你的體重照片：photo_url 通常對應 /static/weight-photos/xxxx.jpg
     * 重要：
     * - deleteByUrlQuietly() 是「安靜模式」：刪檔失敗不會讓整個 purge 掛掉
     * - 刪 DB 也用 LIMIT 分批刪，避免一次刪太多
     */
    public int deleteWeightHistoryWithPhotos(Long userId, int limit) {
        List<String> urls = jdbc.queryForList(
                "SELECT photo_url FROM weight_history WHERE user_id=? AND photo_url IS NOT NULL LIMIT ?",
                String.class, userId, limit
        );

        for (String url : urls) {
            localImageStorage.deleteByUrlQuietly(url);
        }

        return jdbc.update("DELETE FROM weight_history WHERE user_id=? LIMIT ?", userId, limit);
    }

    /**
     * ✅ 快速判斷該 user 在某 table 是否還有資料（用於「是否已清完」的判定）
     * ⚠️ 一樣：table 必須是你程式碼白名單，不要用使用者輸入
     */
    public boolean existsAnyByUserId(String table, Long userId) {
        if (!USER_TABLE_WHITELIST.contains(table)) return false; // ✅ 安全閘
        String sql = "SELECT 1 FROM " + table + " WHERE user_id=? LIMIT 1";
        List<Integer> r = jdbc.query(sql, (rs, i) -> rs.getInt(1), userId);
        return !r.isEmpty();
    }


}
