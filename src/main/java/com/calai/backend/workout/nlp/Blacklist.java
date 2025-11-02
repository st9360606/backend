package com.calai.backend.workout.nlp;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 多語黑名單（安全建構版）
 * - 用 buildLex() 逐步 put；若同 key 被多次 put，會自動合併（Set 去重），不再因 Map.ofEntries 重複 key 直接炸掉。
 * - 最後轉成不可變 Map<String, List<String>>。
 */
public final class Blacklist {
    private Blacklist(){}

    /* ========= 通用規則（語言無關） ========= */
    private static final Pattern P_URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern P_CONTACT = Pattern.compile(
            "(?i)(whatsapp|tele\\s*gram|telegram|line|we\\s*chat|weixin|kakaotalk|discord|snapchat|instagram|ig|facebook|fb|tiktok|x\\.com|twitter)[\\s:：@]?"
    );
    private static final Pattern P_DM = Pattern.compile("(?i)\\b(dm me|inbox me|message me|contact me|加我|私訊|私信)\\b");
    private static final Pattern P_PHONE = Pattern.compile("\\+?\\d[\\d\\s\\-]{7,14}\\d");
    private static final Pattern P_PROMO = Pattern.compile("(?i)\\b(promo code|referral code|coupon|voucher|discount|優惠碼|折扣碼|推廣碼)\\b");

    // 通用英文詞庫（去除成人類別，保留常見 spam/詐騙）
    private static final List<String> GENERIC = List.of(
            "loan","credit","payday","mortgage",
            "casino","bet","betting","gamble","sportsbook",
            "buy followers","buy likes","followers","likes",
            "cheap","free money","investment plan","binary option","forex","crypto","bitcoin","ethereum",
            "click link","limited offer","giveaway","wire money","western union"
    );

    /** 語言別詞庫（合併重複 key、去重） */
    private static final Map<String, List<String>> LEX = buildLex();

    private static Map<String, List<String>> buildLex() {
        // 先用 LinkedHashMap + LinkedHashSet 以保序 & 去重
        Map<String, LinkedHashSet<String>> tmp = new LinkedHashMap<>();

        // ---- 西/葡 ----
        merge(tmp, "es", List.of("prestamo","credito","casino","apuesta","comprar seguidores","me gusta","oferta limitada","codigo promo","cupón","descuento","dm"));
        merge(tmp, "pt", List.of("emprestimo","credito","cassino","aposta","comprar seguidores","curtidas","oferta limitada","codigo promo","cupom","desconto","dm"));
        merge(tmp, "pt-br", List.of("emprestimo","credito","cassino","aposta","comprar seguidores","curtidas","oferta limitada","codigo promocional","cupom","desconto","dm"));

        // ---- 法/德/義/荷/波/羅/捷 ----
        merge(tmp, "fr", List.of("pret","credit","casino","pari","acheter des abonnes","mentions j'aime","offre limitee","code promo","coupon","reduction","mp"));
        merge(tmp, "de", List.of("kredit","darlehen","kasino","wette","followers kaufen","likes kaufen","begrenztes angebot","gutscheincode","gutschein","rabatt","pn"));
        merge(tmp, "it", List.of("prestito","credito","casino","scommessa","comprare follower","mi piace","offerta limitata","codice promo","buono sconto","sconto","dm"));
        merge(tmp, "nl", List.of("lening","krediet","casino","gok","volgers kopen","likes kopen","beperkt aanbod","promocode","coupon","korting","pb"));
        merge(tmp, "pl", List.of("pozyczka","kredyt","kasyno","zaklad","kup obserwujacych","polubienia","oferta ograniczona","kod promocyjny","kupon","znizka","pw"));
        merge(tmp, "ro", List.of("imprumut","credit","cazinou","pariu","cumpara urmaritori","aprecieri","oferta limitata","cod promo","cupon","reducere","md"));
        merge(tmp, "cs", List.of("pujcka","kredit","kasino","sazka","koupit sledujici","lajky","omezena nabidka","promo kod","kupon","sleva","sz"));

        // ---- 俄/土/阿/希伯來 ----
        merge(tmp, "ru", List.of("кредит","займ","казино","ставка","покупка подписчиков","лайки","ограниченное предложение","промокод","купон","скидка","лс"));
        merge(tmp, "tr", List.of("kredi","borc","kumarhane","bahis","takipci satin al","begeni","sinirli teklif","promosyon kodu","kupon","indirim","dm"));
        merge(tmp, "ar", List.of("قرض","ائتمان","كازينو","رهان","شراء متابعين","اعجابات","عرض محدود","رمز ترويجي","قسيمة","خصم","راسلني"));
        merge(tmp, "he", List.of("הלוואה","אשראי","קזינו","הימור","קנה עוקבים","לייקים","הצעה מוגבלת","קוד קופון","קופון","הנחה","דמ"));

        // ---- 東南亞與北亞 ----
        merge(tmp, "vi", List.of("vay","tín dụng","sòng bạc","cá cược","mua người theo dõi","lượt thích","ưu đãi có hạn","mã khuyến mãi","phiếu giảm giá","giảm giá","ib"));
        merge(tmp, "th", List.of("กู้ยืม","เครดิต","คาสิโน","พนัน","ซื้อผู้ติดตาม","ไลก์","ข้อเสนอจำกัด","รหัสโปร","คูปอง","ส่วนลด","dm"));
        merge(tmp, "id", List.of("pinjaman","kredit","kasino","taruhan","beli pengikut","suka","penawaran terbatas","kode promo","kupon","diskon","dm"));
        merge(tmp, "ms", List.of("pinjaman","kredit","kasino","pertaruhan","beli pengikut","suka","tawaran terhad","kod promo","kupon","diskaun","dm"));
        merge(tmp, "fil", List.of("utang","kredito","casino","pusta","bili ng followers","likes","limited offer","promo code","kupon","discount","pm"));
        merge(tmp, "jv", List.of("silihan","kredit","kasino","taruhan","tuku penganut","seneng","penawaran winates","kode promo","kupon","diskon"));

        // ---- 日/韓/中 ----
        merge(tmp, "ja", List.of("融資","クレジット","カジノ","ベット","フォロワー購入","いいね","期間限定","プロモコード","クーポン","割引","dm"));
        merge(tmp, "ko", List.of("대출","신용","카지노","베팅","팔로워 구매","좋아요","기간한정","프로모션 코드","쿠폰","할인","dm"));
        merge(tmp, "zh-tw", List.of("貸款","信用","賭場","下注","買粉","買讚","限時優惠","優惠碼","折扣碼","折扣","私訊"));
        merge(tmp, "zh-hk", List.of("貸款","信用","賭場","下注","買粉","買讚","限時優惠","優惠碼","折扣碼","折扣","私訊"));
        merge(tmp, "zh-cn", List.of("贷款","信用","赌场","下注","买粉","买赞","限时优惠","优惠码","折扣码","折扣","私信"));

        // ---- 北歐與芬蘭 ----
        merge(tmp, "sv", List.of("lan","kredit","kasino","vad","kopa foljare","gilla","begransat erbjudande","kampanjkod","kupong","rabatt","dm"));
        merge(tmp, "da", List.of("lan","kredit","kasino","vaeddemaal","koeb followers","likes","begrænset tilbud","kampagnekode","kupon","rabat","dm"));
        merge(tmp, "nb", List.of("laan","kreditt","kasino","spill","kjop folgere","likes","begrenset tilbud","kampanjekode","kupong","rabatt","dm"));
        merge(tmp, "fi", List.of("laina","luotto","kasino","veto","osta seuraajia","tykkayksia","rajoitettu tarjous","alennuskoodi","kuponki","alennus","dm"));

        // ---- 南亞 ----
        merge(tmp, "hi", List.of("ऋण","क्रेडिट","कैसीनो","सट्टा","फॉलोअर्स खरीदें","लाइक्स","सीमित ऑफर","प्रोमो कोड","कूपन","छूट","dm"));
        merge(tmp, "bn", List.of("ঋণ","ক্রেডিট","ক্যাসিনো","বাজি","ফলোয়ার কিনুন","লাইক","সীমিত অফার","প্রোমো কোড","কুপন","ডিসকাউন্ট","dm"));
        merge(tmp, "pa", List.of("ਕਰਜ਼ਾ","ਕ੍ਰੈਡਿਟ","ਕੈਸੀਨੋ","ਦਾਅ","ਫਾਲੋਅਰ ਖਰੀਦੋ","ਲਾਈਕਸ","ਸੀਮਿਤ ਪੇਸ਼ਕਸ਼","ਪ੍ਰੋਮੋ ਕੋਡ","ਕੂਪਨ","ਛੂਟ","dm"));

        // ---- （原本「西歐」段落重複了 it/nl，這裡就不要再重覆 merge） ----
        // 如需補詞，直接對 "it"/"nl" 再呼叫 merge(tmp,"it", List.of(...)) 即可，不會炸。

        // dev 模式可選擇印出各語系詞彙量以利 debug（略）

        // 轉成不可變 Map<String, List<String>>
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : tmp.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static void merge(Map<String, LinkedHashSet<String>> m, String key, List<String> vals) {
        LinkedHashSet<String> set = m.computeIfAbsent(key, k -> new LinkedHashSet<>());
        set.addAll(vals);
    }

    /** 主要入口：帶入原字串與 localeTag（如 "es"、"pt-BR"、"zh-TW"）。 */
    public static boolean containsBad(String raw, String localeTag) {
        if (raw == null || raw.isBlank()) return false;
        if (P_URL.matcher(raw).find())     return true;
        if (P_CONTACT.matcher(raw).find()) return true;
        if (P_DM.matcher(raw).find())      return true;
        if (P_PROMO.matcher(raw).find())   return true;
        if (P_PHONE.matcher(raw).find())   return true;

        String norm = TextNorm.normalize(raw);
        String tag = normalizeTag(localeTag);

        var list1 = LEX.get(tag);
        if (list1 != null && containsAny(norm, list1)) return true;

        String main = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
        var list2 = LEX.get(main);
        if (list2 != null && containsAny(norm, list2)) return true;

        return containsAny(norm, GENERIC);
    }

    /** 除錯/觀測：回傳命中原因（沒命中回 null） */
    public static String matchReason(String raw, String localeTag) {
        if (raw == null || raw.isBlank()) return null;
        if (P_URL.matcher(raw).find())     return "url";
        if (P_CONTACT.matcher(raw).find()) return "contact";
        if (P_DM.matcher(raw).find())      return "dm";
        if (P_PROMO.matcher(raw).find())   return "promo";
        if (P_PHONE.matcher(raw).find())   return "phone";

        String norm = TextNorm.normalize(raw);
        String tag = normalizeTag(localeTag);

        var list1 = LEX.get(tag);
        if (list1 != null) {
            String k = firstHit(norm, list1);
            if (k != null) return tag + ":" + k;
        }
        String main = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
        var list2 = LEX.get(main);
        if (list2 != null) {
            String k = firstHit(norm, list2);
            if (k != null) return main + ":" + k;
        }
        String g = firstHit(norm, GENERIC);
        return g != null ? "*:" + g : null;
    }

    /* =============== helpers =============== */
    private static boolean containsAny(String norm, List<String> tokens) {
        for (String t : tokens) if (norm.contains(t)) return true;
        return false;
    }
    private static String firstHit(String norm, List<String> tokens) {
        for (String t : tokens) if (norm.contains(t)) return t;
        return null;
    }
    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) return "*";
        return tag.toLowerCase(Locale.ROOT);
    }

    /** 提供給其它模組查觀測值（選用） */
    public static int sizeOfLang(String langKey) {
        var list = LEX.get(langKey);
        return list == null ? 0 : list.size();
    }

    /** 供單元測試取用 */
    static Map<String, List<String>> lexView() { return LEX; }

    /** 文本正規化（若你已有 TextNorm，沿用即可） */
    static final class TextNorm {
        static String normalize(String s) {
            if (s == null) return "";
            String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
            return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        }
    }
}
