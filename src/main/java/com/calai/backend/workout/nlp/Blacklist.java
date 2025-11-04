package com.calai.backend.workout.nlp;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 多語黑名單（智慧比對 + 運動白名單）
 * - ASCII 且長度 ≤4 的短詞（dm/lan/bet/pn/pb/pw/ib…）採用單字邊界比對，避免誤擋 badminton/treadmill/plank。
 * - 先檢查運動白名單（hiit/weightlifting/...），命中立即放行。
 * - 使用 TextNorm：NFKD -> 去重音 -> toLowerCase -> 壓縮空白。
 */
public final class Blacklist {
    private Blacklist(){}

    /* ---------- 可動態擴充的白名單（thread-safe） ---------- */
    private static final Set<String> ALLOW_SPORTS = ConcurrentHashMap.newKeySet();

    static {
        // 保命種子：避免常見運動在 DB 尚未載入時被擋
        registerSportsWhitelist(List.of(
                "hiit","weightlifting",
                "strength training","resistance training",
                "badminton","treadmill","plank",
                "table tennis","ping pong","golf",
                "push ups","push-ups","pull ups","pull-ups",
                "rowing","elliptical","stairmaster","stair climbing",
                "jump rope","boxing","cycling","swimming","running","walking"
        ));
    }

    /** 對外：批次註冊白名單（會正規化並把底線轉空白） */
    public static void registerSportsWhitelist(Collection<String> tokens) {
        if (tokens == null) return;
        for (String t : tokens) {
            if (t == null || t.isBlank()) continue;
            String n = TextNorm.normalize(t).replace('_', ' ').trim();
            if (!n.isEmpty()) ALLOW_SPORTS.add(n);
        }
    }

    /* ================== 通用規則（語言無關） ================== */
    private static final Pattern P_URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern P_CONTACT = Pattern.compile(
            "(?i)(?:\\b(?:whatsapp|tele\\s*gram|telegram|we\\s*chat|weixin|kakaotalk|discord|snapchat|instagram|ig|facebook|fb|tiktok|x\\.com|twitter)\\b[\\s:：@]?|(?<!in)(?<!on)\\bline\\b[\\s:：@]?)"
    );
    private static final Pattern P_DM    = Pattern.compile("(?i)\\b(dm\\s*me|inbox\\s*me|message\\s*me|contact\\s*me|加我|私訊|私信)\\b");
    private static final Pattern P_PHONE = Pattern.compile("\\+?\\d[\\d\\s\\-]{7,14}\\d");
    private static final Pattern P_PROMO = Pattern.compile("(?i)\\b(promo\\s*code|referral\\s*code|coupon|voucher|discount|優惠碼|折扣碼|推廣碼)\\b");

    private static final List<String> GENERIC = List.of(
            "loan","credit","payday","mortgage",
            "casino","bet","betting","gamble","sportsbook",
            "buy followers","buy likes","followers","likes",
            "cheap","free money","investment plan","binary option","forex","crypto","bitcoin","ethereum",
            "click link","limited offer","giveaway","wire money","western union"
    );

    /** 語言別詞庫 */
    private static final Map<String, List<String>> LEX = buildLex();

    private static Map<String, List<String>> buildLex() {
        Map<String, LinkedHashMap<String, Boolean>> tmpSeen = new LinkedHashMap<>();
        Map<String, List<String>> out = new LinkedHashMap<>();

        class M { void add(String k, List<String> v) {
            tmpSeen.computeIfAbsent(k, __ -> new LinkedHashMap<>()).putAll(v.stream()
                    .collect(LinkedHashMap::new, (m,a)->m.put(a,true), Map::putAll));
        }}
        M m = new M();

        // ---- 西/葡 ----
        m.add("es", List.of("prestamo","credito","casino","apuesta","comprar seguidores","me gusta","oferta limitada","codigo promo","cupón","descuento","dm"));
        m.add("pt", List.of("emprestimo","credito","cassino","aposta","comprar seguidores","curtidas","oferta limitada","codigo promo","cupom","desconto","dm"));
        m.add("pt-br", List.of("emprestimo","credito","cassino","aposta","comprar seguidores","curtidas","oferta limitada","codigo promocional","cupom","desconto","dm"));

        // ---- 法/德/義/荷/波/羅/捷 ----
        m.add("fr", List.of("pret","credit","casino","pari","acheter des abonnes","mentions j'aime","offre limitee","code promo","coupon","reduction","mp"));
        m.add("de", List.of("kredit","darlehen","kasino","wette","followers kaufen","likes kaufen","begrenztes angebot","gutscheincode","gutschein","rabatt","pn"));
        m.add("it", List.of("prestito","credito","casino","scommessa","comprare follower","mi piace","offerta limitata","codice promo","buono sconto","sconto","dm"));
        m.add("nl", List.of("lening","krediet","casino","gok","volgers kopen","likes kopen","beperkt aanbod","promocode","coupon","korting","pb"));
        m.add("pl", List.of("pozyczka","kredyt","kasyno","zaklad","kup obserwujacych","polubienia","oferta ograniczona","kod promocyjny","kupon","znizka","pw"));
        m.add("ro", List.of("imprumut","credit","cazinou","pariu","cumpara urmaritori","aprecieri","oferta limitata","cod promo","cupon","reducere","md"));
        m.add("cs", List.of("pujcka","kredit","kasino","sazka","koupit sledujici","lajky","omezena nabidka","promo kod","kupon","sleva","sz"));

        // ---- 俄/土/阿/希伯來 ----
        m.add("ru", List.of("кредит","займ","казино","ставка","покупка подписчиков","лайки","ограниченное предложение","промокод","купон","скидка","лс"));
        m.add("tr", List.of("kredi","borc","kumarhane","bahis","takipci satin al","begeni","sinirli teklif","promosyon kodu","kupon","indirim","dm"));
        m.add("ar", List.of("قرض","ائتمان","كازينو","رهان","شراء متابعين","اعجابات","عرض محدود","رمز ترويجي","قسيمة","خصم","راسلني"));
        m.add("he", List.of("הלוואה","אשראי","קזינו","הימור","קנה עוקבים","לייקים","הצעה מוגבלת","קוד קופון","קופון","הנחה","דמ"));

        // ---- 東南亞與北亞 ----
        m.add("vi", List.of("vay","tín dụng","sòng bạc","cá cược","mua người theo dõi","lượt thích","ưu đãi có hạn","mã khuyến mãi","phiếu giảm giá","giảm giá","ib"));
        m.add("th", List.of("กู้ยืม","เครดิต","คาสิโน","พนัน","ซื้อผู้ติดตาม","ไลก์","ข้อเสนอจำกัด","รหัสโปร","คูปอง","ส่วนลด","dm"));
        m.add("id", List.of("pinjaman","kredit","kasino","taruhan","beli pengikut","suka","penawaran terbatas","kode promo","kupon","diskon","dm"));
        m.add("ms", List.of("pinjaman","kredit","kasino","pertaruhan","beli pengikut","suka","tawaran terhad","kod promo","kupon","diskaun","dm"));
        m.add("fil", List.of("utang","kredito","casino","pusta","bili ng followers","likes","limited offer","promo code","kupon","discount","pm"));
        m.add("jv", List.of("silihan","kredit","kasino","taruhan","tuku penganut","seneng","penawaran winates","kode promo","kupon","diskon"));

        // ---- 日/韓/中 ----
        m.add("ja", List.of("融資","クレジット","カジノ","ベット","フォロワー購入","いいね","期間限定","プロモコード","クーポン","割引","dm"));
        m.add("ko", List.of("대출","신용","카지노","베팅","팔로워 구매","좋아요","기간한정","프로모션 코드","쿠폰","할인","dm"));
        m.add("zh-tw", List.of("貸款","信用","賭場","下注","買粉","買讚","限時優惠","優惠碼","折扣碼","折扣","私訊"));
        m.add("zh-hk", List.of("貸款","信用","賭場","下注","買粉","買讚","限時優惠","優惠碼","折扣碼","折扣","私訊"));
        m.add("zh-cn", List.of("贷款","信用","赌场","下注","买粉","买赞","限时优惠","优惠码","折扣码","折扣","私信"));

        // ---- 北歐與芬蘭 ----
        m.add("sv", List.of("lan","kredit","kasino","vad","kopa foljare","gilla","begransat erbjudande","kampanjkod","kupong","rabatt","dm"));
        m.add("da", List.of("lan","kredit","kasino","vaeddemaal","koeb followers","likes","begrænset tilbud","kampagnekode","kupon","rabat","dm"));
        m.add("nb", List.of("laan","kreditt","kasino","spill","kjop folgere","likes","begrenset tilbud","kampanjekode","kupong","rabatt","dm"));
        m.add("fi", List.of("laina","luotto","kasino","veto","osta seuraajia","tykkayksia","rajoitettu tarjous","alennuskoodi","kuponki","alennus","dm"));

        // ---- 南亞 ----
        m.add("hi", List.of("ऋण","क्रेडिट","कैसीनो","सट्टा","फॉलोअर्स खरीदें","लाइक्स","सीमित ऑफर","प्रोमो कोड","कूपन","छूट","dm"));
        m.add("bn", List.of("ঋণ","ক্রেডিট","ক্যাসিনো","বাজি","ফলোয়ার কিনুন","লাইক","সীমিত অফার","প্রোমো কোড","কুপন","ডিসকাউন্ট","dm"));
        m.add("pa", List.of("ਕਰਜ਼ਾ","ਕ੍ਰੈਡਿਟ","ਕੈਸੀਨੋ","ਦਾਅ","ਫਾਲੋਅਰ ਖਰੀਦੋ","ਲਾਈਕਸ","ਸੀਮਿਤ ਪੇਸ਼ਕਸ਼","ਪ੍ਰੋਮੋ ਕੋਡ","ਕੂਪਨ","ਛੂਟ","dm"));

        for (var e : tmpSeen.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue().keySet()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 主要入口 */
    public static boolean containsBad(String raw, String localeTag) {
        if (raw == null || raw.isBlank()) return false;

        String norm = TextNorm.normalize(raw);
        for (String allow : ALLOW_SPORTS) {
            if (norm.contains(allow)) return false;
        }

        if (P_URL.matcher(raw).find())     return true;
        if (P_CONTACT.matcher(raw).find()) return true;
        if (P_DM.matcher(raw).find())      return true;
        if (P_PROMO.matcher(raw).find())   return true;
        if (P_PHONE.matcher(raw).find())   return true;

        String tag = normalizeTag(localeTag);
        var list1 = LEX.get(tag);
        if (list1 != null && containsAnySmart(norm, list1)) return true;

        String main = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
        var list2 = LEX.get(main);
        if (list2 != null && containsAnySmart(norm, list2)) return true;

        return containsAnySmart(norm, GENERIC);
    }

    /** 除錯/觀測：回傳命中原因（沒命中回 null） */
    public static String matchReason(String raw, String localeTag) {
        if (raw == null || raw.isBlank()) return null;

        String norm = TextNorm.normalize(raw);
        for (String allow : ALLOW_SPORTS) {
            if (norm.contains(allow)) return null;
        }

        if (P_URL.matcher(raw).find())     return "url";
        if (P_CONTACT.matcher(raw).find()) return "contact";
        if (P_DM.matcher(raw).find())      return "dm";
        if (P_PROMO.matcher(raw).find())   return "promo";
        if (P_PHONE.matcher(raw).find())   return "phone";

        String tag = normalizeTag(localeTag);
        var list1 = LEX.get(tag);
        if (list1 != null) {
            String k = firstHitSmart(norm, list1);
            if (k != null) return tag + ":" + k;
        }
        String main = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
        var list2 = LEX.get(main);
        if (list2 != null) {
            String k = firstHitSmart(norm, list2);
            if (k != null) return main + ":" + k;
        }
        String g = firstHitSmart(norm, GENERIC);
        return g != null ? "*:" + g : null;
    }

    /* =============== helpers =============== */

    // ASCII 且長度 ≤4 的短詞，用邊界比對；其餘用 contains
    private static boolean containsAnySmart(String norm, List<String> tokens) {
        for (String t : tokens) {
            if (isAsciiLetters(t) && t.length() <= 4) {
                Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(t) + "(?![a-z])");
                if (p.matcher(norm).find()) return true;
            } else {
                if (norm.contains(t)) return true;
            }
        }
        return false;
    }

    private static String firstHitSmart(String norm, List<String> tokens) {
        for (String t : tokens) {
            if (isAsciiLetters(t) && t.length() <= 4) {
                Pattern p = Pattern.compile("(?<![a-z])" + Pattern.quote(t) + "(?![a-z])");
                if (p.matcher(norm).find()) return t;
            } else if (norm.contains(t)) {
                return t;
            }
        }
        return null;
    }

    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) return "*";
        return tag.toLowerCase(Locale.ROOT);
    }

    /** 文本正規化：NFKD → 去重音 → toLowerCase → 壓縮空白 */
    static final class TextNorm {
        static String normalize(String s) {
            if (s == null) return "";
            String n = Normalizer.normalize(s, Normalizer.Form.NFKD);
            n = n.replaceAll("\\p{M}+", "");           // 去重音
            n = n.toLowerCase(Locale.ROOT);
            n = n.replaceAll("\\s+", " ").trim();
            return n;
        }
    }

    /** 觀測用 */
    public static int sizeOfLang(String langKey) {
        var list = LEX.get(langKey);
        return list == null ? 0 : list.size();
    }
}
