package com.calai.backend.workout.service;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.users.profile.common.Units;
import com.calai.backend.users.profile.entity.UserProfile;
import com.calai.backend.users.profile.repo.UserProfileRepository;
import com.calai.backend.workout.dto.*;
import com.calai.backend.workout.entity.WorkoutAlias;
import com.calai.backend.workout.entity.WorkoutAliasEvent;
import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.entity.WorkoutSession;
import com.calai.backend.workout.match.AliasMatcher;
import com.calai.backend.workout.nlp.Blacklist;
import com.calai.backend.workout.nlp.DurationParser;
import com.calai.backend.workout.nlp.Heuristics;
import com.calai.backend.workout.nlp.TextNorm;
import com.calai.backend.workout.repo.WorkoutAliasEventRepo;
import com.calai.backend.workout.repo.WorkoutAliasRepo;
import com.calai.backend.workout.repo.WorkoutDictionaryRepo;
import com.calai.backend.workout.repo.WorkoutSessionRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkoutService {
    private final WorkoutDictionaryRepo dictRepo;
    private final WorkoutAliasRepo aliasRepo;
    private final WorkoutSessionRepo sessionRepo;
    private final UserProfileRepository profileRepo;
    private final AuthContext auth;
    private final WorkoutAliasEventRepo aliasEventRepo;
    private final RateLimiterService rateLimiter; // ★ 新增
    private final UserDailyWorkoutSummaryService dailyWorkoutSummaryService;
    private final Clock clock;

    @Value("${workout.estimate.blacklistPolicy:block}")
    private String blacklistPolicy; // generic（現狀）、block（阻擋）、audit（只記錄與回傳 not_found）

    // === ★ 新增：體重 clamp 邊界（跟 UserProfileService 對齊） ===
    private static final double MIN_WEIGHT_KG  = 20.0d;
    private static final double MAX_WEIGHT_KG  = 800.0d;
    private static final double MIN_WEIGHT_LBS = 20.0d;
    private static final double MAX_WEIGHT_LBS = 1500.0d;

    public WorkoutService(
            WorkoutDictionaryRepo dictRepo,
            WorkoutAliasRepo aliasRepo,
            WorkoutSessionRepo sessionRepo,
            UserProfileRepository profileRepo,
            AuthContext auth,
            WorkoutAliasEventRepo aliasEventRepo,
            RateLimiterService rateLimiter, UserDailyWorkoutSummaryService dailyWorkoutSummaryService, Clock clock // ★ 新增
    ) {
        this.dictRepo = dictRepo;
        this.aliasRepo = aliasRepo;
        this.sessionRepo = sessionRepo;
        this.profileRepo = profileRepo;
        this.auth = auth;
        this.aliasEventRepo = aliasEventRepo;
        this.rateLimiter = rateLimiter; // ★ 新增
        this.dailyWorkoutSummaryService = dailyWorkoutSummaryService;
        this.clock = clock;
    }

    // ======== 時間標籤（24h） ========
    private static final DateTimeFormatter TIME_FMT_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter HISTORY_DATE_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static String formatTime24(ZonedDateTime zdt) { return zdt.format(TIME_FMT_24); }

    // === ★ 調整：體重取得邏輯，加入 clamp 與 lbs→kg 轉換 ===
    private double userWeightKgOrThrow(Long uid) {
        UserProfile p = profileRepo.findByUserId(uid)
                .orElseThrow(() -> new IllegalStateException("PROFILE_NOT_FOUND"));

        // 1) 優先使用 kg（視為 canonical；這裡只做 clamp，不改小數位）
        Double kg = p.getWeightKg();
        if (kg != null) {
            kg = Units.clamp(kg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
            return kg;
        }

        // 2) 其次使用 lbs：先 clamp，再由 server 換算成 kg（lbsToKg1 內含 0.1kg 無條件捨去）
        Double lbs = p.getWeightLbs();
        if (lbs != null) {
            lbs = Units.clamp(lbs, MIN_WEIGHT_LBS, MAX_WEIGHT_LBS);
            Double kgFromLbs = Units.lbsToKg(lbs); // lbsToKg1：floor 到 0.1kg
            kgFromLbs = Units.clamp(kgFromLbs, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
            return kgFromLbs;
        }

        // 3) 兩者都沒有 → 視為 profile 不完整
        throw new IllegalStateException("WEIGHT_REQUIRED");
    }

    private String userLocaleOrDefault(Long uid) {
        return profileRepo.findByUserId(uid)
                .map(UserProfile::getLocale)
                .filter(l -> l != null && !l.isBlank())
                .orElse("en");
    }

    private int calcKcal(double met, double userKg, int minutes) {
        double burned = met * userKg * (minutes / 60.0);
        return (int) Math.round(burned);
    }

    private TodayWorkoutResponse buildToday(Long uid, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);

        // Today must mean the user's persisted local calendar day, not the last 24 hours.
        // Using startedAt UTC boundaries with a DATETIME/Instant mapping can accidentally include
        // yesterday's local records, e.g. Asia/Taipei 2026-05-16 17:40 in the 2026-05-17 UTC window.
        var list = sessionRepo.findRecentUserSessionsByLocalDate(uid, today, today);
        int total = list.stream().mapToInt(WorkoutSession::getKcal).sum();

        var dtos = list.stream().map(ws -> {
            ZoneId sessionZone = resolveSessionZone(ws.getTimezone(), zone);
            String tl = formatTime24(ws.getStartedAt().atZone(sessionZone));
            var dict = ws.getDictionary();
            // 防禦：舊資料或匯入資料若 displayNameEn 為 null，退回 canonical_key
            String name = (dict.getDisplayNameEn() != null && !dict.getDisplayNameEn().isBlank())
                    ? dict.getDisplayNameEn()
                    : dict.getCanonicalKey().replace('_', ' ');
            return new WorkoutSessionDto(ws.getId(), name, ws.getMinutes(), ws.getKcal(), tl);
        }).toList();

        return new TodayWorkoutResponse(total, dtos);
    }

    private WorkoutHistoryResponse buildRecentHistory(Long uid, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(6);

        var list = sessionRepo.findRecentUserSessionsByLocalDate(uid, start, today);
        int total = list.stream().mapToInt(WorkoutSession::getKcal).sum();

        var dtos = list.stream().map(ws -> {
            ZoneId sessionZone = resolveSessionZone(ws.getTimezone(), zone);
            LocalDate localDate = ws.getLocalDate() != null
                    ? ws.getLocalDate()
                    : LocalDate.ofInstant(ws.getStartedAt(), sessionZone);
            String tl = formatTime24(ws.getStartedAt().atZone(sessionZone));
            var dict = ws.getDictionary();
            String name = (dict.getDisplayNameEn() != null && !dict.getDisplayNameEn().isBlank())
                    ? dict.getDisplayNameEn()
                    : dict.getCanonicalKey().replace('_', ' ');
            return new WorkoutHistorySessionDto(
                    ws.getId(),
                    name,
                    ws.getMinutes(),
                    ws.getKcal(),
                    localDate,
                    HISTORY_DATE_FMT.format(localDate),
                    tl
            );
        }).toList();

        return new WorkoutHistoryResponse(total, dtos);
    }


    /** WS2+WS5: /estimate（A1 + A5 核心） */
    @Transactional
    public EstimateResponse estimate(String textRaw) {
        Long uid = auth.requireUserId();
        double userKg = userWeightKgOrThrow(uid);
        String localeTag = userLocaleOrDefault(uid);

        // 1) 時長解析
        Integer minutes = DurationParser.parseMinutes(textRaw);
        if (minutes == null || minutes <= 0) {
            return new EstimateResponse("not_found", null, null, null, null);
        }

        // 2) 活動片語
        String phraseLower = extractActivityText(textRaw);
        String norm = TextNorm.normalize(phraseLower);

        // === 3) 黑名單策略 ===
        if (Blacklist.containsBad(textRaw, localeTag)) {
            String reason = Blacklist.matchReason(textRaw, localeTag);
            switch (blacklistPolicy.toLowerCase(Locale.ROOT)) {
                case "block": {
                    // 不回 kcal、不寫事件，直接阻擋
                    // 你也可以改丟 BizException 由 Controller 轉 422
                    return new EstimateResponse("blocked", null, phraseLower, minutes, null);
                }
                case "audit": {
                    // 僅寫事件但不回估算（或回 not_found）
                    // 若要紀錄可選擇 matched=generic，但 usedGeneric=true
                    WorkoutDictionary generic = ensureGeneric();
                    saveEvent(uid, localeTag, norm, generic, 0.0, true);
                    return new EstimateResponse("not_found", null, null, null, null);
                }
                case "generic":
                default: {
                    // 現狀：導向 generic，照算
                    WorkoutDictionary generic = ensureGeneric();
                    int kcal = calcKcal(generic.getMetValue(), userKg, minutes);
                    saveEvent(uid, localeTag, norm, generic, 0.0, true);
                    return new EstimateResponse("ok", generic.getId(), phraseLower, minutes, kcal);
                }
            }
        }

        // 4) APPROVED alias 直中
        var matchOpt = aliasRepo.findApprovedAlias(norm, localeTag);
        if (matchOpt.isPresent()) {
            var dict = matchOpt.get().getDictionary();
            int kcal  = calcKcal(dict.getMetValue(), userKg, minutes);
            saveEvent(uid, localeTag, norm, dict, 1.0, false);
            return new EstimateResponse("ok", dict.getId(), phraseLower, minutes, kcal);
        }

        // 5) 相似度 + 同義詞
        var allDicts = dictRepo.findAll();
        var matcher = new AliasMatcher(allDicts, builtinSynonyms());
        var cand = matcher.best(norm, localeTag);

        double autoApprove = AliasMatcher.autoApproveThreshold(localeTag); // ★ 使用方法
        double medium      = AliasMatcher.mediumThreshold();               // ★ 使用方法

        if (cand != null && cand.score() >= autoApprove) {
            upsertApprovedAlias(localeTag, norm, cand.dict());
            int kcal = calcKcal(cand.dict().getMetValue(), userKg, minutes);
            saveEvent(uid, localeTag, norm, cand.dict(), cand.score(), false);
            return new EstimateResponse("ok", cand.dict().getId(), phraseLower, minutes, kcal);
        }
        if (cand != null && cand.score() >= medium) {
            int kcal = calcKcal(cand.dict().getMetValue(), userKg, minutes);
            saveEvent(uid, localeTag, norm, cand.dict(), cand.score(), false);
            return new EstimateResponse("ok", cand.dict().getId(), phraseLower, minutes, kcal);
        }

        // 6) 低信心 → Generic 強度詞
        double met = pickGenericMet(norm);
        WorkoutDictionary generic = ensureGeneric();
        int kcal = calcKcal(met, userKg, minutes);
        saveEvent(uid, localeTag, norm, generic, 0.0, true);
        return new EstimateResponse("ok", generic.getId(), phraseLower, minutes, kcal);
    }

    // ===== Helpers =====

    // 放在 com.calai.backend.workout.service.WorkoutService 內，直接覆蓋同名方法
    private String extractActivityText(String textRaw) {
        String s = textRaw;
        // 展開 1:30 / 1h30
        s = s.replaceAll("(\\d{1,3})\\s*[:：]\\s*(\\d{1,2})", "$1 h $2 min");
        s = s.replaceAll("(\\d{1,3})\\s*h\\s*(\\d{1,2})", "$1 h $2 min");

        // 正規化
        s = TextNorm.normalize(s);

        // 關鍵：長詞在前，避免只吃掉「分」而殘留「鐘/钟」
        String MIN_UNITS_NORM =
                "(?:"
                + "minutos|minuto|minuta|"
                + "minutes|minute|mins|min|"
                // 其他語系（維持你原本的清單，但把短的放後面）
                + "minuty|minut|minuten|minuut|minuter|"
                + "dakika|"
                + "минуты|минута|минут|мин|"
                + "دقائق|دقيقة|"
                + "דקות|"
                + "phut|นาที|menit|minit|"
                + "分鐘|分钟|分|분"
                + ")";

        String HR_UNITS_NORM  =
                "(?:h|hr|hrs|hour|hours|hora|horas|ore|uur|std|stunden|godz|saat|час|часы|ساعة|ساعات|שעה|"
                        +  "gio|ชั่วโมง|jam|小时|小時|時間|시간)";

        // 去掉「數字 + 分鐘/小時」
        s = s.replaceAll("(\\d{1,4})\\s*" + MIN_UNITS_NORM, " ");
        s = s.replaceAll("(\\d{1,3})\\s*" + HR_UNITS_NORM, " ");

        // 補刀：若先前錯配殘留「鐘/钟」，一併清理
        s = s.replaceAll("(?<![a-zA-Z])[鐘钟](?![a-zA-Z])", " ");

        s = s.replaceAll("\\s+", " ").trim();
        return s.isBlank() ? "workout" : s;
    }


    private Map<String, List<String>> builtinSynonyms() {
        return Map.of(
                "running",  List.of("run","running","jog","慢跑","跑步","ラン","ジョグ","달리기","correr","corrida","кросс"),
                "walking",  List.of("walk","散步","走路","步行","歩く","걷기","paseo","caminata"),
                "cycling",  List.of("cycle","bike","biking","騎車","單車","骑车","自転車","자전거","ciclismo","vélo"),
                "swimming", List.of("swim","游泳","수영","natación","natação")
        );
    }

    private void upsertApprovedAlias(String lang, String phraseLower, WorkoutDictionary dict) {
        var existing = aliasRepo.findAnyByLangAndPhrase(lang, phraseLower);
        if (existing.isPresent()) {
            WorkoutAlias ali = existing.get();
            ali.setStatus("APPROVED");
            ali.setDictionary(dict);
            ali.setLastSeen(Instant.now());
            aliasRepo.save(ali);
            return;
        }
        WorkoutAlias ali = new WorkoutAlias();
        ali.setLangTag(lang);
        ali.setPhraseLower(phraseLower);
        ali.setDictionary(dict);
        ali.setStatus("APPROVED");
        ali.setLastSeen(Instant.now());
        try {
            aliasRepo.save(ali);
        } catch (DataIntegrityViolationException ex) {
            // 併發保險：UNIQUE 擋下後改就地升級
            aliasRepo.findAnyByLangAndPhrase(lang, phraseLower).ifPresent(a -> {
                a.setStatus("APPROVED");
                a.setDictionary(dict);
                a.setLastSeen(Instant.now());
                aliasRepo.save(a);
            });
        }
    }

    private double pickGenericMet(String norm) {
        if (Heuristics.CAT_RUN.stream().anyMatch(norm::contains))   return 7.0;
        if (Heuristics.CAT_CYCLE.stream().anyMatch(norm::contains)) return 6.0;
        if (Heuristics.CAT_SWIM.stream().anyMatch(norm::contains))  return 7.5;
        if (Heuristics.INTENSITY_HARD.stream().anyMatch(norm::contains))  return 7.0;
        if (Heuristics.INTENSITY_LIGHT.stream().anyMatch(norm::contains)) return 3.5;
        return 5.0;
    }

    private WorkoutDictionary ensureGeneric() {
        return dictRepo.findAll().stream()
                .filter(d -> "other_exercise".equals(d.getCanonicalKey()))
                .findFirst().orElseThrow();
    }

    private void saveEvent(Long uid, String lang, String phraseLower,
                           WorkoutDictionary matched, double score, boolean generic) {
        // ★ 速率限制：若 24h 內過多，則不寫事件（但估算仍回傳）
        if (!rateLimiter.allowNewPhrase(uid)) return;

        var e = new WorkoutAliasEvent();
        e.setUserId(uid);
        e.setLangTag(lang);
        e.setPhraseLower(phraseLower);
        e.setMatchedDict(matched);
        e.setScore(score);
        e.setUsedGeneric(generic);
        aliasEventRepo.save(e);
    }

    @Transactional
    public TodayWorkoutResponse deleteSession(Long sessionId, ZoneId requestZone) {
        Long uid = auth.requireUserId();
        var ws = sessionRepo.findByIdAndUserId(sessionId, uid)
                .orElseThrow(() -> new IllegalStateException("NOT_FOUND"));

        ZoneId sessionZone = resolveSessionZone(ws.getTimezone(), requestZone);
        LocalDate affectedDate = ws.getLocalDate() != null
                ? ws.getLocalDate()
                : LocalDate.ofInstant(ws.getStartedAt(), sessionZone);

        sessionRepo.delete(ws);

        // 這裡改用 session 自己固化的 local bucket 來重算
        dailyWorkoutSummaryService.recomputeDay(uid, affectedDate, sessionZone);

        // today endpoint 仍維持「目前 request timezone 的今天」
        return buildToday(uid, requestZone);
    }

    private ZoneId resolveSessionZone(String storedTimezone, ZoneId fallbackZone) {
        if (storedTimezone == null || storedTimezone.isBlank()) {
            return fallbackZone;
        }
        try {
            return ZoneId.of(storedTimezone);
        } catch (Exception ex) {
            return fallbackZone;
        }
    }

    // === ★ 調整：給 /workouts/me/weight 用的 fallback 專用 API ===
    public double currentUserWeightKg() {
        Long uid = auth.requireUserId();
        try {
            return userWeightKgOrThrow(uid);
        } catch (IllegalStateException ex) {
            // PROFILE_NOT_FOUND / WEIGHT_REQUIRED → 回 0.0，讓 App fallback 使用 70kg
            return 0.0d;
        }
    }

    @Transactional(readOnly = true)
    public PresetListResponse presets() {
        Long uid = auth.requireUserId();
        double userKg = userWeightKgOrThrow(uid);
        var all = dictRepo.findAll();
        var list = all.stream().map(d -> {
            int kcal30 = calcKcal(d.getMetValue(), userKg, 30);
            return new PresetWorkoutDto(d.getId(), d.getDisplayNameEn(), kcal30, d.getIconKey());
        }).toList();
        return new PresetListResponse(list);
    }

    private void purgeOldSessions(Long uid, ZoneId zone) {
        LocalDate cutoffDate = LocalDate.now(zone).minusDays(7);
        sessionRepo.deleteByUserIdAndLocalDateBefore(uid, cutoffDate);
    }

    @Transactional
    public LogWorkoutResponse log(LogWorkoutRequest req, ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone);

        double userKg = userWeightKgOrThrow(uid);
        WorkoutDictionary dict = dictRepo.findById(req.activityId()).orElseThrow();

        int minutes = req.minutes();
        int kcal = calcKcal(dict.getMetValue(), userKg, minutes);

        Instant now = clock.instant();
        LocalDate localDate = LocalDate.ofInstant(now, zone);

        WorkoutSession ws = new WorkoutSession();
        ws.setUserId(uid);
        ws.setDictionary(dict);
        ws.setMinutes(minutes);
        ws.setKcal(kcal);
        ws.setStartedAt(now);
        ws.setLocalDate(localDate);
        ws.setTimezone(zone.getId());
        ws.setCreatedAt(now);

        sessionRepo.save(ws);

        // summary 重算吃 persisted localDate，不再靠 startedAt + current request zone 回推
        dailyWorkoutSummaryService.recomputeDay(uid, localDate, zone);

        TodayWorkoutResponse today = buildToday(uid, zone);
        String timeLabel = formatTime24(now.atZone(zone));

        return new LogWorkoutResponse(
                new WorkoutSessionDto(ws.getId(), dict.getDisplayNameEn(), ws.getMinutes(), ws.getKcal(), timeLabel),
                today
        );
    }

    @Transactional // ← 改成可寫交易，或直接拿掉註解
    public TodayWorkoutResponse today(ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone);           // 內含 deleteOlderThan（寫入操作）
        return buildToday(uid, zone);          // 查詢 + 映射
    }

    @Transactional(readOnly = true)
    public WorkoutHistoryResponse recentHistory(ZoneId zone) {
        Long uid = auth.requireUserId();
        return buildRecentHistory(uid, zone);
    }

    @Transactional
    public void purgeOldSessionsPublic(Long userId, ZoneId zone) {
        LocalDate cutoffDate = LocalDate.now(zone).minusDays(7);
        sessionRepo.deleteByUserIdAndLocalDateBefore(userId, cutoffDate);
    }

    @Transactional
    public WorkoutWeeklyProgressResponse weeklyProgress(ZoneId zone) {
        Long uid = auth.requireUserId();
        return dailyWorkoutSummaryService.getWeeklyProgress(uid, zone);
    }
}
