package com.calai.backend.workout.service;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.userprofile.entity.UserProfile;
import com.calai.backend.userprofile.repo.UserProfileRepository;
import com.calai.backend.userprofile.common.Units; // 你後端已有 lbsToKg(...) 等轉換邏輯:contentReference[oaicite:10]{index=10}
import com.calai.backend.workout.dto.*;
import com.calai.backend.workout.entity.WorkoutAlias;
import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.entity.WorkoutSession;
import com.calai.backend.workout.repo.WorkoutAliasRepo;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import com.calai.backend.workout.repo.WorkoutSessionRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkoutService {
    private final WorkoutDictionaryRepo dictRepo;
    private final WorkoutAliasRepo aliasRepo;
    private final WorkoutSessionRepo sessionRepo;
    private final UserProfileRepository profileRepo;
    private final AuthContext auth;

    public WorkoutService(
            WorkoutDictionaryRepo dictRepo,
            WorkoutAliasRepo aliasRepo,
            WorkoutSessionRepo sessionRepo,
            UserProfileRepository profileRepo,
            AuthContext auth
    ) {
        this.dictRepo = dictRepo;
        this.aliasRepo = aliasRepo;
        this.sessionRepo = sessionRepo;
        this.profileRepo = profileRepo;
        this.auth = auth;
    }

    private double userWeightKgOrThrow(Long uid) {
        UserProfile p = profileRepo.findByUserId(uid)
                .orElseThrow(() -> new IllegalStateException("PROFILE_NOT_FOUND"));

        // 使用者的體重可能是 kg 或 lbs，兩個欄位在 user_profiles 都有:contentReference[oaicite:11]{index=11}
        if (p.getWeightKg() != null) {
            return p.getWeightKg();
        }
        if (p.getWeightLbs() != null) {
            return Units.lbsToKg(p.getWeightLbs());
        }
        throw new IllegalStateException("WEIGHT_REQUIRED");
    }

    private String userLocaleOrDefault(Long uid) {
        return profileRepo.findByUserId(uid)
                .map(UserProfile::getLocale)
                .filter(l -> l != null && !l.isBlank())
                .orElse("en");
    }

    // 解析「15 min walking」or「30分鐘 散步」等等
    private static record ParsedInput(String activityText, Integer minutes) {}

    private ParsedInput parseUserText(String text, String localeTag) {
        // 抓分鐘
        Pattern pMin = Pattern.compile("(\\d{1,3})\\s*(min|mins|minute|minutes|phút|分鐘|分|분)",
                Pattern.CASE_INSENSITIVE);
        Matcher mMin = pMin.matcher(text);
        Integer minutes = null;
        if (mMin.find()) {
            minutes = Integer.valueOf(mMin.group(1));
        }

        // 抓小時
        if (minutes == null) {
            Pattern pHr = Pattern.compile("(\\d{1,2})\\s*(h|hr|hour|hours|giờ|小時|小时|시간)",
                    Pattern.CASE_INSENSITIVE);
            Matcher mHr = pHr.matcher(text);
            if (mHr.find()) {
                minutes = Integer.valueOf(mHr.group(1)) * 60;
            }
        }

        if (minutes == null) {
            return new ParsedInput(null, null); // 直接 fail → Scan Failed
        }

        // 去掉第一個時間片段，剩下的字串當作活動名稱
        String raw = text;
        raw = mMin.replaceFirst("");
        raw = raw.replaceAll("\\s+", " ").trim();
        if (raw.isBlank()) raw = "workout";

        return new ParsedInput(raw, minutes);
    }

    private int calcKcal(double met, double userKg, int minutes) {
        // 不當醫療建議，只是 estimated calories burned
        double burned = met * userKg * (minutes / 60.0);
        return (int) Math.round(burned);
    }

    private ZoneId parseZoneOrSystem(String tzHeader) {
        try {
            if (tzHeader != null && !tzHeader.isBlank()) return ZoneId.of(tzHeader);
        } catch (Exception ignored) {}
        return ZoneId.systemDefault();
    }

    private TodayWorkoutResponse buildToday(Long uid, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();

        var list = sessionRepo.findUserSessionsBetween(uid, start, end);

        int total = list.stream().mapToInt(WorkoutSession::getKcal).sum();

        var dtos = list.stream().map(ws -> {
            ZonedDateTime zdt = ws.getStartedAt().atZone(zone);
            String tl = zdt.format(DateTimeFormatter.ofPattern("h:mm a"));
            String name = ws.getDictionary().getDisplayNameEn(); // TODO: locale aware later
            return new WorkoutSessionDto(
                    ws.getId(),
                    name,
                    ws.getMinutes(),
                    ws.getKcal(),
                    tl
            );
        }).toList();

        return new TodayWorkoutResponse(total, dtos);
    }

    /** WS2+WS5: /estimate */
    @Transactional
    public EstimateResponse estimate(String textRaw) {
        Long uid = auth.requireUserId();
        double userKg = userWeightKgOrThrow(uid);
        String localeTag = userLocaleOrDefault(uid);

        ParsedInput parsed = parseUserText(textRaw, localeTag);
        if (parsed.activityText() == null || parsed.minutes() == null) {
            // 時間都抓不到，直接失敗
            return new EstimateResponse("not_found", null, null, null, null);
        }

        String phraseLower = parsed.activityText().toLowerCase(Locale.ROOT);

        // 先找已審核的 alias (APPROVED)
        var matchOpt = aliasRepo.findApprovedAlias(phraseLower, localeTag);
        if (matchOpt.isEmpty()) {
            // WS5: 建一筆 PENDING，管理員之後人工對應 dictionary
            WorkoutAlias pending = new WorkoutAlias();
            pending.setLangTag(localeTag);
            pending.setPhraseLower(phraseLower);
            pending.setStatus("PENDING");
            pending.setCreatedByUser(uid);
            // dictionary 暫時未知(null)，等審核員手動掛上去再設為 APPROVED
            aliasRepo.save(pending);

            return new EstimateResponse("not_found", null, null, null, null);
        }

        var alias = matchOpt.get();
        WorkoutDictionary dict = alias.getDictionary();
        int kcal = calcKcal(dict.getMetValue(), userKg, parsed.minutes());

        return new EstimateResponse(
                "ok",
                dict.getId(),
                parsed.activityText(), // 保留使用者輸入語言
                parsed.minutes(),
                kcal
        );
    }

    /** WS6: 刪除一筆 session -> 回傳今天最新加總，滿足 Play「可刪除健康紀錄」 */
    @Transactional
    public TodayWorkoutResponse deleteSession(Long sessionId, ZoneId zone) {
        Long uid = auth.requireUserId();
        var ws = sessionRepo.findByIdAndUserId(sessionId, uid)
                .orElseThrow(() -> new IllegalStateException("NOT_FOUND"));
        sessionRepo.delete(ws);
        return buildToday(uid, zone);
    }

    public double currentUserWeightKg() {
        Long uid = auth.requireUserId();
        return userWeightKgOrThrow(uid);
    }

    /** WS1: 預設清單（依用戶體重計算 30 分鐘 kcal） */
    @Transactional(readOnly = true)
    public PresetListResponse presets() {
        Long uid = auth.requireUserId();
        double userKg = userWeightKgOrThrow(uid);

        var all = dictRepo.findAll(); // List<WorkoutDictionary>
        var list = all.stream().map(d -> {
            int kcal30 = calcKcal(d.getMetValue(), userKg, 30);
            return new PresetWorkoutDto(
                    d.getId(),
                    d.getDisplayNameEn(),
                    kcal30,
                    d.getIconKey()
            );
        }).toList();

        return new PresetListResponse(list);
    }

    /** 依用戶時區計算 7 天保留的截止點（當地今天 0 點回推 7 天）並清除更舊資料。*/
    private void purgeOldSessions(Long uid, ZoneId zone) {
        // 用戶當地的「今天 00:00」
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        // 要保留「前 7 天，不含今天」→ 刪除 7 天前 00:00 之前的資料
        Instant cutoff = todayStart.minus(java.time.Duration.ofDays(7));

        // 只刪「該用戶」且「早於 cutoff」的資料
        sessionRepo.deleteOlderThan(uid, cutoff);
    }

    /** WS3+WS4: /log */
    @Transactional
    public LogWorkoutResponse log(LogWorkoutRequest req, ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone); // ★ 先清理

        double userKg = userWeightKgOrThrow(uid);
        WorkoutDictionary dict = dictRepo.findById(req.activityId()).orElseThrow();

        int minutes = req.minutes();
        int kcal = calcKcal(dict.getMetValue(), userKg, minutes);

        WorkoutSession ws = new WorkoutSession();
        ws.setUserId(uid);
        ws.setDictionary(dict);
        ws.setMinutes(minutes);
        ws.setKcal(kcal);
        ws.setStartedAt(Instant.now());
        sessionRepo.save(ws);

        TodayWorkoutResponse today = buildToday(uid, zone);
        return new LogWorkoutResponse(
                new WorkoutSessionDto(
                        ws.getId(), dict.getDisplayNameEn(), ws.getMinutes(), ws.getKcal(),
                        ZonedDateTime.ofInstant(ws.getStartedAt(), zone)
                                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                ),
                today
        );
    }

    /** WS4: /today */
    @Transactional // ← 改這裡：拿掉 readOnly=true
    public TodayWorkoutResponse today(ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone); // 這裡會做 delete，所以不可在 readOnly Tx
        return buildToday(uid, zone);
    }

    /**
     * 依用戶時區清理：保留「前 7 天，不含今天」。
     * 觸發條件：排程或 Controller 進入點。
     */
    @Transactional
    public void purgeOldSessionsPublic(Long userId, ZoneId zone) {
        // 用戶當地「今天 00:00」
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        // 保留前 7 天，不含今天 → 刪除早於此 cutoff 的資料
        Instant cutoff = todayStart.minus(Duration.ofDays(7));
        sessionRepo.deleteOlderThan(userId, cutoff);
    }
}

