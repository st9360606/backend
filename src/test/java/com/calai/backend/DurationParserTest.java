package com.calai.backend;

import com.calai.backend.workout.nlp.DurationParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {

    @Test void colon_and_compound() {
        assertEquals(90, DurationParser.parseMinutes("1:30"));
        assertEquals(75, DurationParser.parseMinutes("1h15min"));
        assertEquals(135, DurationParser.parseMinutes("2 h 15 minutes"));
    }

    @Test void chinese_japanese_korean() {
        assertEquals(30, DurationParser.parseMinutes("30 分鐘"));
        assertEquals(60, DurationParser.parseMinutes("1 小時"));
        assertEquals(20, DurationParser.parseMinutes("20分"));
        assertEquals(30, DurationParser.parseMinutes("半小時"));
        assertEquals(90, DurationParser.parseMinutes("1 個半小時"));
        assertEquals(90, DurationParser.parseMinutes("1个半小时"));
    }

    @Test void english_half_hour() {
        assertEquals(30, DurationParser.parseMinutes("half an hour"));
        assertEquals(90, DurationParser.parseMinutes("1 and a half hours"));
        assertEquals(90, DurationParser.parseMinutes("1 hour and a half"));
    }

    @Test void spanish_portuguese_half() {
        assertEquals(90, DurationParser.parseMinutes("1 hora y media"));  // es
        assertEquals(90, DurationParser.parseMinutes("1 horas y media")); // plural tolerant
        assertEquals(90, DurationParser.parseMinutes("1 hora e meia"));   // pt
    }

    @Test void vietnamese_normalized() {
        // TextNorm.normalize 會把 "phút/giờ" → "phut/gio"，"nửa" → "nua"
        assertEquals(30, DurationParser.parseMinutes("30 phút"));
        assertEquals(120, DurationParser.parseMinutes("2 giờ"));
        assertEquals(150, DurationParser.parseMinutes("2 giờ 30 phút"));
        assertEquals(30, DurationParser.parseMinutes("nửa giờ"));
    }

    @Test void other_languages_half() {
        assertEquals(30, DurationParser.parseMinutes("demi-heure"));    // fr → "demi heure"
        assertEquals(30, DurationParser.parseMinutes("halbe Stunde"));  // de
        assertEquals(30, DurationParser.parseMinutes("mezz'ora"));      // it
        assertEquals(30, DurationParser.parseMinutes("half uur"));      // nl
        assertEquals(30, DurationParser.parseMinutes("pół godziny"));   // pl → "pol godziny"
        assertEquals(30, DurationParser.parseMinutes("yarım saat"));    // tr → "yarim saat"
        assertEquals(30, DurationParser.parseMinutes("полчаса"));       // ru
        assertEquals(30, DurationParser.parseMinutes("نصف ساعة"));      // ar
        assertEquals(30, DurationParser.parseMinutes("חצי שעה"));        // he
        assertEquals(30, DurationParser.parseMinutes("ครึ่งชั่วโมง"));   // th
        assertEquals(30, DurationParser.parseMinutes("setengah jam"));  // id/ms
    }

    @Test void none() {
        assertNull(DurationParser.parseMinutes("no time here"));
    }


    @Test void dot_decimal_hours() {
        assertEquals(90, DurationParser.parseMinutes("1.5h"));
        assertEquals(90, DurationParser.parseMinutes("1.5 hours"));
        assertEquals(75, DurationParser.parseMinutes("1.25 h"));
        assertEquals(135, DurationParser.parseMinutes("2.25 hours"));
        assertEquals(30, DurationParser.parseMinutes("0.5 hour"));
    }

    @Test void comma_decimal_hours() {
        assertEquals(90, DurationParser.parseMinutes("1,5 h"));
        assertEquals(165, DurationParser.parseMinutes("2,75 hours"));
    }

    @Test void chinese_decimal_hours() {
        assertEquals(30, DurationParser.parseMinutes("0.5 小時"));
        assertEquals(150, DurationParser.parseMinutes("2.5 小時"));
        assertEquals(90, DurationParser.parseMinutes("1,5 小時"));
    }

    @Test void mixed_with_minutes() {
        assertEquals(95, DurationParser.parseMinutes("1.5h + 5 minutes"));
        assertEquals(125, DurationParser.parseMinutes("2,0 h  +  5 min"));
    }

    @Test void coexists_with_colon_and_compound() {
        assertEquals(120, DurationParser.parseMinutes("0.5h + 0.5h + 30min + 30min"));
        assertEquals(150, DurationParser.parseMinutes("2:00 + 0.5h + 20 min - 0.5h + 30min")); // 正向/複合場景
    }

    @Test void polish_half_hour_with_diacritic() {
        assertEquals(30, DurationParser.parseMinutes("pół godziny"));
    }

    @Test void vietnamese_half_hour_with_diacritic() {
        assertEquals(30, DurationParser.parseMinutes("nửa giờ"));
    }

    @Test void polish_half_hour_normalized_and_full() {
        assertEquals(30, DurationParser.parseMinutes("pol godziny")); // 去重音
    }

    @Test void polish_half_hour_abbrev() {
        assertEquals(30, DurationParser.parseMinutes("pol godz."));   // 縮寫
    }

    @Test void thai_half_hour_raw_should_pass() {
        assertEquals(30, DurationParser.parseMinutes("ครึ่งชั่วโมง"));
        assertEquals(30, DurationParser.parseMinutes("ครึ่ง ชั่วโมง")); // 有空白也可
    }

    @Test void half_and_complex() {
        assertEquals(30, DurationParser.parseMinutes("half an hour"));
        assertEquals(90, DurationParser.parseMinutes("1 and a half hours"));
        assertEquals(90, DurationParser.parseMinutes("1 hour and a half"));
        assertEquals(90, DurationParser.parseMinutes("1 個半小時"));
        assertEquals(90, DurationParser.parseMinutes("1 hora y media"));
        assertEquals(90, DurationParser.parseMinutes("1 hora e meia"));
        assertEquals(30, DurationParser.parseMinutes("pół godziny"));
        assertEquals(30, DurationParser.parseMinutes("ครึ่งชั่วโมง"));
    }

    @Test void colon_decimal_mix_and_crop() {
        assertEquals(90,  DurationParser.parseMinutes("1:30"));
        assertEquals(150, DurationParser.parseMinutes("2:00 + 0.5h + 20 min - 0.5h + 30min"));
        assertEquals(95,  DurationParser.parseMinutes("1.5h + 5 minutes"));
    }

    @Test void vietnamese_hours_raw_should_pass() {
        assertEquals(120, DurationParser.parseMinutes("2 giờ"));
    }
}
