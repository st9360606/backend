package com.calai.backend.workout.service;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.userprofile.common.Units;
import com.calai.backend.userprofile.entity.UserProfile;
import com.calai.backend.userprofile.repo.UserProfileRepository;
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

    @Value("${workout.estimate.blacklistPolicy:block}")
    private String blacklistPolicy; // generic（現狀）、block（阻擋）、audit（只記錄與回傳 not_found）

    public WorkoutService(
            WorkoutDictionaryRepo dictRepo,
            WorkoutAliasRepo aliasRepo,
            WorkoutSessionRepo sessionRepo,
            UserProfileRepository profileRepo,
            AuthContext auth,
            WorkoutAliasEventRepo aliasEventRepo,
            RateLimiterService rateLimiter // ★ 新增
    ) {
        this.dictRepo = dictRepo;
        this.aliasRepo = aliasRepo;
        this.sessionRepo = sessionRepo;
        this.profileRepo = profileRepo;
        this.auth = auth;
        this.aliasEventRepo = aliasEventRepo;
        this.rateLimiter = rateLimiter; // ★ 新增
    }

    // ======== 時間標籤（24h） ========
    private static final DateTimeFormatter TIME_FMT_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static String formatTime24(ZonedDateTime zdt) { return zdt.format(TIME_FMT_24); }

    private double userWeightKgOrThrow(Long uid) {
        UserProfile p = profileRepo.findByUserId(uid)
                .orElseThrow(() -> new IllegalStateException("PROFILE_NOT_FOUND"));
        if (p.getWeightKg() != null) return p.getWeightKg();
        if (p.getWeightLbs() != null) return Units.lbsToKg(p.getWeightLbs());
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
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();

        var list = sessionRepo.findUserSessionsBetween(uid, start, end);
        int total = list.stream().mapToInt(WorkoutSession::getKcal).sum();

        var dtos = list.stream().map(ws -> {
            String tl = formatTime24(ws.getStartedAt().atZone(zone));
            var dict = ws.getDictionary();
            // 防禦：舊資料或匯入資料若 displayNameEn 為 null，退回 canonical_key
            String name = (dict.getDisplayNameEn() != null && !dict.getDisplayNameEn().isBlank())
                    ? dict.getDisplayNameEn()
                    : dict.getCanonicalKey().replace('_', ' ');
            return new WorkoutSessionDto(ws.getId(), name, ws.getMinutes(), ws.getKcal(), tl);
        }).toList();

        return new TodayWorkoutResponse(total, dtos);
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
                "(?:min|mins|minute|minutes|minuto|minutos|minuta|minuty|minut|minuten|minuut|minuter|"
                        +  "dakika|мин|минута|минуты|минут|دقيقة|دقائق|דקות|phut|นาที|menit|minit|"
                        +  "分鐘|分钟|分|분)";
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
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant cutoff = todayStart.minus(Duration.ofDays(7));
        sessionRepo.deleteOlderThan(uid, cutoff);
    }

    @Transactional
    public LogWorkoutResponse log(LogWorkoutRequest req, ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone);

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
        String timeLabel = formatTime24(ZonedDateTime.ofInstant(ws.getStartedAt(), zone));

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

    @Transactional
    public void purgeOldSessionsPublic(Long userId, ZoneId zone) {
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant cutoff = todayStart.minus(Duration.ofDays(7));
        sessionRepo.deleteOlderThan(userId, cutoff);
    }
}
