package com.calai.backend.workout.nlp;

import java.util.Set;

public final class Heuristics {
    private Heuristics(){}

    // 強度詞（多語）
    public static final Set<String> INTENSITY_LIGHT = Set.of(
            "light","easy","slow","輕","緩","輕度","ゆる","느린","낮은","ligero","leve","leicht","lento","lent","легкий","خفيف","chậm","ช้า","pelan"
    );
    public static final Set<String> INTENSITY_MED = Set.of(
            "moderate","normal","中","中等","보통","普通","normal","moderado","mittel","medio","moyen","умеренный","متوسط","vừa","ปานกลาง","sedang"
    );
    public static final Set<String> INTENSITY_HARD = Set.of(
            "hard","intense","fast","vigorous","heavy","激烈","高強度","快","速","きつい","빠른","강도","rápido","intenso","schnell","forte","rapide",
            "интенсив","سريع","شديد","nhanh","เร็ว","cepat"
    );

    // 類別詞（判別大類→選擇較合理 generic MET）
    public static final Set<String> CAT_CYCLE = Set.of("cycle","cycling","bike","biking","單車","騎車","骑车","자전거","vélo","bicicleta","fahrrad");
    public static final Set<String> CAT_RUN   = Set.of("run","running","jog","jogging","跑步","慢跑","ジョギング","달리기","correr","rennen");
    public static final Set<String> CAT_SWIM  = Set.of("swim","swimming","游泳","수영","natación","schwimmen","nuoto");
}
