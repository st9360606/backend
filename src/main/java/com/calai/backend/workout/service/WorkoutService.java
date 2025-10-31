package com.calai.backend.workout.service;

import com.calai.backend.auth.security.AuthContext;
import com.calai.backend.userprofile.entity.UserProfile;
import com.calai.backend.userprofile.repo.UserProfileRepository;
import com.calai.backend.userprofile.common.Units;
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
import java.util.Locale;
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

    /* ========= 時間格式：標準 24h，HH:mm（無 am/pm） ========= */
    private static final DateTimeFormatter TIME_FMT_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static String formatTime24(ZonedDateTime zdt) {
        return zdt.format(TIME_FMT_24); // 例：00:39、12:00、13:00、23:59
    }
    /* ===================================================== */

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

    // 解析使用者輸入時長
    private static record ParsedInput(String activityText, Integer minutes) {}

    private ParsedInput parseUserText(String text, String localeTag) {
        Pattern pMin = Pattern.compile("(\\d{1,3})\\s*(min|mins|minute|minutes|phút|分鐘|分|분)",
                Pattern.CASE_INSENSITIVE);
        Matcher mMin = pMin.matcher(text);
        Integer minutes = null;
        if (mMin.find()) minutes = Integer.valueOf(mMin.group(1));

        if (minutes == null) {
            Pattern pHr = Pattern.compile("(\\d{1,2})\\s*(h|hr|hour|hours|giờ|小時|小时|시간)",
                    Pattern.CASE_INSENSITIVE);
            Matcher mHr = pHr.matcher(text);
            if (mHr.find()) minutes = Integer.valueOf(mHr.group(1)) * 60;
        }

        if (minutes == null) return new ParsedInput(null, null);

        String raw = text;
        raw = mMin.replaceFirst("");
        raw = raw.replaceAll("\\s+", " ").trim();
        if (raw.isBlank()) raw = "workout";

        return new ParsedInput(raw, minutes);
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
            ZonedDateTime zdt = ws.getStartedAt().atZone(zone);
            String tl = formatTime24(zdt); // ★ 24h 格式
            String name = ws.getDictionary().getDisplayNameEn(); // TODO: locale aware
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
            return new EstimateResponse("not_found", null, null, null, null);
        }

        String phraseLower = parsed.activityText().toLowerCase(Locale.ROOT);
        var matchOpt = aliasRepo.findApprovedAlias(phraseLower, localeTag);
        if (matchOpt.isEmpty()) {
            WorkoutAlias pending = new WorkoutAlias();
            pending.setLangTag(localeTag);
            pending.setPhraseLower(phraseLower);
            pending.setStatus("PENDING");
            pending.setCreatedByUser(uid);
            aliasRepo.save(pending);
            return new EstimateResponse("not_found", null, null, null, null);
        }

        var alias = matchOpt.get();
        WorkoutDictionary dict = alias.getDictionary();
        int kcal = calcKcal(dict.getMetValue(), userKg, parsed.minutes());

        return new EstimateResponse(
                "ok",
                dict.getId(),
                parsed.activityText(),
                parsed.minutes(),
                kcal
        );
    }

    /** WS6: 刪除一筆 session */
    @Transactional
    public TodayWorkoutResponse deleteSession(Long sessionId, ZoneId zone) {
        Long uid = auth.requireUserId();
        var ws = sessionRepo.findByIdAndUserId(sessionId, uid)
                .orElseThrow(() -> new IllegalStateException("NOT_FOUND"));
        sessionRepo.delete(ws);
        return buildToday(uid, zone);
    }

    /** 提供前端 fallback 計算用戶體重 */
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
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant cutoff = todayStart.minus(Duration.ofDays(7));
        sessionRepo.deleteOlderThan(uid, cutoff);
    }

    /** WS3+WS4: /log */
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
        String timeLabel = formatTime24(ZonedDateTime.ofInstant(ws.getStartedAt(), zone)); // ★ 24h 格式

        return new LogWorkoutResponse(
                new WorkoutSessionDto(
                        ws.getId(),
                        dict.getDisplayNameEn(),
                        ws.getMinutes(),
                        ws.getKcal(),
                        timeLabel
                ),
                today
        );
    }

    /** WS4: /today */
    @Transactional
    public TodayWorkoutResponse today(ZoneId zone) {
        Long uid = auth.requireUserId();
        purgeOldSessions(uid, zone);
        return buildToday(uid, zone);
    }

    /** 可供排程或管理介面手動清理 */
    @Transactional
    public void purgeOldSessionsPublic(Long userId, ZoneId zone) {
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant cutoff = todayStart.minus(Duration.ofDays(7));
        sessionRepo.deleteOlderThan(userId, cutoff);
    }
}
