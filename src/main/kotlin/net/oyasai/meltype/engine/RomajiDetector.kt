package net.oyasai.meltype.engine

data class RomajiToken(
    val romaji: String,
    val kana: String
)

data class RomajiAnalysis(
    val isValid: Boolean,
    val tokens: List<RomajiToken>,
    val partial: String,
    val invalidReason: String? = null,
    val strongYouon: Int = 0,
    val tsu: Int = 0,
    val sokuon: Int = 0,
    val longVowels: Int = 0
) {
    val kana: String get() = tokens.joinToString("") { it.kana }
}

/**
 * Meltype.Core の RomajiDetector を忠実に Kotlin に移植したクラス
 * ローマ字トークンの解析、かな変換、未完了子音（partial）の追跡を行います。
 */
class RomajiDetector {

    companion object {
        private val Vowels = setOf('a', 'i', 'u', 'e', 'o')
        private fun isVowel(c: Char): Boolean = c in Vowels
        private fun isConsonant(c: Char): Boolean = c in 'a'..'z' && c !in Vowels

        private val StrongYouonKana = setOf("ゃ", "ゅ", "ょ", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ", "ゎ")

        // ローマ字 -> かな変換テーブル
        private val RomajiTable = mapOf(
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

        // プレフィックスとなりうる部分文字列の集合
        private val PartialPrefixes: Set<String> = buildSet {
            for (key in RomajiTable.keys) {
                for (len in 1 until key.length) {
                    add(key.substring(0, len))
                }
            }
        }

        fun readCRow(input: String): String {
            // ca -> ka, cu -> ku, co -> ko の置換
            return input.replace("ca", "ka").replace("cu", "ku").replace("co", "ko")
        }
    }

    /**
     * ローマ字文字列を解析し、トークン列と状態を返します
     */
    fun analyze(letters: String): RomajiAnalysis {
        val tokens = mutableListOf<RomajiToken>()
        var strongYouon = 0
        var tsu = 0
        var sokuon = 0
        var longVowels = 0
        var i = 0
        val s = letters.lowercase()

        fun invalid(reason: String) = RomajiAnalysis(
            isValid = false,
            tokens = tokens,
            partial = "",
            invalidReason = reason,
            strongYouon = strongYouon,
            tsu = tsu,
            sokuon = sokuon,
            longVowels = longVowels
        )

        while (i < s.length) {
            val c = s[i]
            if (c !in 'a'..'z' && c != '-') {
                return invalid("英字以外の文字: '$c'")
            }

            // 撥音 'n' の判定: "nn" または 次が子音（y以外）の場合
            if (c == 'n' && i + 1 < s.length && (s[i + 1] == 'n' || (isConsonant(s[i + 1]) && s[i + 1] != 'y'))) {
                val consumed = if (s[i + 1] == 'n') 2 else 1
                tokens.add(RomajiToken(s.substring(i, i + consumed), "ん"))
                i += consumed
                continue
            }

            // 促音の判定: 子音の連続 (kk, tt, ss など) または tch
            if (i + 1 < s.length && isConsonant(c) && c != 'n' &&
                (s[i + 1] == c || (c == 't' && s[i + 1] == 'c' && i + 2 < s.length && s[i + 2] == 'h'))
            ) {
                tokens.add(RomajiToken(c.toString(), "っ"))
                sokuon++
                i += 1
                continue
            }

            // 最長一致検索 (4文字〜1文字)
            var matched = false
            val maxLen = minOf(4, s.length - i)
            for (len in maxLen downTo 1) {
                val piece = s.substring(i, i + len)
                val kana = RomajiTable[piece]
                if (kana != null) {
                    if (StrongYouonKana.any { kana.contains(it) }) strongYouon++
                    if (piece == "tsu") tsu++
                    if (kana == "う" && tokens.isNotEmpty() && tokens.last().romaji.endsWith("o")) longVowels++

                    tokens.add(RomajiToken(piece, kana))
                    i += len
                    matched = true
                    break
                }
            }

            if (matched) continue

            // テーブルに直接マッチしなかった場合、未確定子音（入力途中）か確認
            val rest = s.substring(i)
            if (PartialPrefixes.contains(rest) || rest == "n" || rest == "tc") {
                return RomajiAnalysis(
                    isValid = true,
                    tokens = tokens,
                    partial = rest,
                    strongYouon = strongYouon,
                    tsu = tsu,
                    sokuon = sokuon,
                    longVowels = longVowels
                )
            }

            return invalid("ローマ字として無効な並び: '$rest'")
        }

        return RomajiAnalysis(
            isValid = true,
            tokens = tokens,
            partial = "",
            strongYouon = strongYouon,
            tsu = tsu,
            sokuon = sokuon,
            longVowels = longVowels
        )
    }

    /**
     * 入力途中のローマ字をできる限りひらがなに変換する（入力プレビュー用）
     */
    fun toKanaLenient(letters: String): String {
        val analysis = analyze(letters)
        val sb = StringBuilder()
        for (token in analysis.tokens) {
            sb.append(token.kana)
        }
        sb.append(analysis.partial)
        return sb.toString()
    }
}
