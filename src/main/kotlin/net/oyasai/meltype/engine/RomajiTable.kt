package net.oyasai.meltype.engine

/**
 * ローマ字 → かな の変換表と、入力途中の文字（まだ表の途中までしか打っていない文字）の判定。
 * 表を足したり直したりするときは、このファイルだけを見ればよい。
 */
internal object RomajiTable {

    /** 表の中で一番長いローマ字の長さ（最長一致で探す長さの上限） */
    const val MAX_ROMAJI_LENGTH = 4

    /** ローマ字 → かな */
    val entries: Map<String, String> = mapOf(
        // 母音
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        // か行
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        // さ行
        "sa" to "さ", "si" to "し", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "sha" to "しゃ", "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "sye" to "しぇ", "syo" to "しょ",
        "ja" to "じゃ", "ju" to "じゅ", "je" to "じぇ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jye" to "じぇ", "jyo" to "じょ",
        // た行
        "ta" to "た", "ti" to "ち", "chi" to "ち", "tu" to "つ", "tsu" to "つ", "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "cha" to "ちゃ", "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tye" to "ちぇ", "tyo" to "ちょ",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dye" to "ぢぇ", "dyo" to "ぢょ",
        // な行
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        // は行
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ", "fyu" to "ふゅ",
        // ま行
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        // や行
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        // ら行
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        // わ行
        "wa" to "わ", "wo" to "を", "nn" to "ん",
        "wi" to "うぃ", "wu" to "う", // "we" は英単語 we と衝突するため登録しない（whe で入力）
        "wha" to "うぁ", "whi" to "うぃ", "whu" to "う", "whe" to "うぇ", "who" to "うぉ",
        // や行・拗音の追加（MS-IME 等と同じ打ち方）
        "ye" to "いぇ",
        "zya" to "じゃ", "zyu" to "じゅ", "zye" to "じぇ", "zyo" to "じょ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cye" to "ちぇ", "cyo" to "ちょ",
        "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",
        "tha" to "てゃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
        "dha" to "でゃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",
        "kwa" to "くぁ", "kwi" to "くぃ", "kwu" to "く", "kwe" to "くぇ", "kwo" to "くぉ",
        "gwa" to "ぐぁ", "gwi" to "ぐぃ", "gwu" to "ぐ", "gwe" to "ぐぇ", "gwo" to "ぐぉ",
        // 促音・小文字
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "xtu" to "っ", "ltu" to "っ", "xtsu" to "っ", "ltsu" to "っ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
        // 外来音
        "va" to "ヴぁ", "vi" to "ヴぃ", "vu" to "ヴ", "ve" to "ヴぇ", "vo" to "ヴぉ",
        "thi" to "てぃ", "dhi" to "でぃ",
        "-" to "ー"
    )

    /** プレフィックス（表のローマ字の、途中までの文字）になりうる文字列の集合 */
    private val partialPrefixes: Set<String> = buildSet {
        for (key in entries.keys) {
            for (len in 1 until key.length) {
                add(key.substring(0, len))
            }
        }
    }

    fun kanaFor(romaji: String): String? = entries[romaji]

    fun contains(romaji: String): Boolean = entries.containsKey(romaji)

    /**
     * 入力の最後に残った文字が、まだ打っている途中か（表のローマ字の途中までか）。
     * `n`（次の文字で変わる）と `tc`（`tch` の途中）も、途中として扱う。
     */
    fun isPartial(rest: String): Boolean = rest in partialPrefixes || rest == "n" || rest == "tc"
}
