package com.calai.backend.workout.config;

import com.calai.backend.workout.nlp.Blacklist;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 啟動時把 workout_dictionary 的 canonicalKey / displayNameEn / iconKey
 * 全部載入 Blacklist 白名單，並註冊底線→空白變體。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportsWhitelistBootstrap {

    private final WorkoutDictionaryRepo repo;

    @PostConstruct
    public void load() {
        try {
            var all = repo.findAll();
            Set<String> tokens = new HashSet<>(all.size() * 4);
            all.forEach(wd -> {
                String canonical = safe(wd.getCanonicalKey());
                String displayEn = safe(wd.getDisplayNameEn());
                String iconKey   = safe(wd.getIconKey());

                if (!canonical.isEmpty()) {
                    tokens.add(canonical);                 // e.g. table_tennis
                    tokens.add(canonical.replace('_',' ')); // e.g. table tennis
                }
                if (!displayEn.isEmpty()) tokens.add(displayEn); // e.g. "Table Tennis"

                if (!iconKey.isEmpty()) {
                    tokens.add(iconKey);                   // e.g. table_tennis
                    tokens.add(iconKey.replace('_',' '));  // e.g. table tennis
                }
            });
            Blacklist.registerSportsWhitelist(tokens);
            log.info("[SportsWhitelist] loaded {} tokens from workout_dictionary", tokens.size());
        } catch (Exception e) {
            // 不讓啟動失敗；但把錯誤印出好追查
            log.warn("[SportsWhitelist] failed to load whitelist from DB: {}", e.toString());
        }
    }

    private static String safe(Object o) {
        return Objects.toString(o, "").trim();
    }
}
