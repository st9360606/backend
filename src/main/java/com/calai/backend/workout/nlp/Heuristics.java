package com.calai.backend.workout.nlp;

import java.util.Set;

/**
 * Heuristics：用於粗分運動類別與強度（供 generic MET 粗估）
 * - 已移除重複字串（避免 Set.of(...) 拋 IllegalArgumentException）
 * - 補上「去重音」變體（例：vélo → "velo"、natación → "natacion"），與 TextNorm 的去重音規則一致
 */
public final class Heuristics {
    private Heuristics(){}

    /** 強度詞（輕） */
    public static final Set<String> INTENSITY_LIGHT = Set.of(
            // en
            "light","easy","slow",
            // zh
            "輕","緩","輕度",
            // ja / ko
            "ゆる","느린","낮은",
            // es / pt / de / it / fr
            "ligero","leve","leicht","lento","lent",
            // ru / ar
            "легкий","خفيف",
            // vi（去重音）, th, ms/id
            "cham","ช้า","pelan"
    );

    /** 強度詞（中） */
    public static final Set<String> INTENSITY_MED = Set.of(
            // en
            "moderate","normal",
            // zh
            "中","中等","普通",
            // ko
            "보통",
            // es / pt / de / it / fr
            "moderado","mittel","medio","moyen",
            // ru / ar
            "умеренный","متوسط",
            // vi（去重音）, th, ms/id
            "vua","ปานกลาง","sedang"
    );

    /** 強度詞（重/激烈） */
    public static final Set<String> INTENSITY_HARD = Set.of(
            // en
            "hard","intense","fast","vigorous","heavy",
            // zh
            "激烈","高強度","快","速",
            // ja / ko
            "きつい","빠른","강도",
            // es（去重音）/ pt / de / it / fr
            "rapido","intenso","schnell","forte","rapide",
            // ru / ar
            "интенсив","سريع","شديد",
            // vi（去重音）, th, ms/id
            "nhanh","เร็ว","cepat"
    );

    /** 類別詞：自行車 */
    public static final Set<String> CAT_CYCLE = Set.of(
            // en
            "cycle","cycling","bike","biking",
            // zh
            "單車","騎車","骑车",
            // ko / fr（去重音）/ es / de / id
            "자전거","velo","bicicleta","fahrrad","sepeda"
    );

    /** 類別詞：跑步 */
    public static final Set<String> CAT_RUN = Set.of(
            // en
            "run","running","jog","jogging",
            // zh / ja / ko
            "跑步","慢跑","ジョギング","달리기",
            // es / de（補德語常見詞）
            "correr","rennen","lauf","laufen"
    );

    /** 類別詞：游泳 */
    public static final Set<String> CAT_SWIM = Set.of(
            // en
            "swim","swimming",
            // zh / ko
            "游泳","수영",
            // es（去重音）/ de / it
            "natacion","schwimmen","nuoto"
    );
}
