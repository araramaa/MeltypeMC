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
 *
 * ローマ字とかなの対応表は [RomajiTable] にある。
 */
class RomajiDetector {

    companion object {
        private val Vowels = setOf('a', 'i', 'u', 'e', 'o')
        private fun isConsonant(c: Char): Boolean = c in 'a'..'z' && c !in Vowels

        /** ローマ字として扱う文字（英字とハイフン）か */
        private fun isRomajiChar(c: Char): Boolean = c in 'a'..'z' || c == '-'

        private val StrongYouonKana = setOf("ゃ", "ゅ", "ょ", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ", "ゎ")

        fun readCRow(input: String): String {
            // ca -> ka, cu -> ku, co -> ko の置換
            return input.replace("ca", "ka").replace("cu", "ku").replace("co", "ko")
        }
    }

    // ---------------------------------------------------------------------------------
    // 文字列を先頭から読むときの、共通の判定（analyze と toKanaLenient の両方が使う）
    // ---------------------------------------------------------------------------------

    /**
     * 位置 [i] の `n` が、撥音「ん」になるか。なるときは、使う文字数を返す（ならないときは 0）。
     * "nn" なら 2 文字、`n` の次が子音（y 以外）なら 1 文字。
     */
    private fun hatsuonLength(s: String, i: Int): Int {
        if (s[i] != 'n' || i + 1 >= s.length) return 0
        val next = s[i + 1]
        return when {
            next == 'n' -> 2
            isConsonant(next) && next != 'y' -> 1
            else -> 0
        }
    }

    /** 位置 [i] の文字が、促音「っ」になるか（同じ子音の連続 kk・tt・ss など、または tch） */
    private fun isSokuon(s: String, i: Int): Boolean {
        val c = s[i]
        if (i + 1 >= s.length || !isConsonant(c) || c == 'n') return false
        val next = s[i + 1]
        return next == c || (c == 't' && next == 'c' && i + 2 < s.length && s[i + 2] == 'h')
    }

    /** 位置 [i] から、表にあるローマ字のうち、一番長く一致するもの（4文字〜1文字）。なければ null */
    private fun longestMatch(s: String, i: Int): String? {
        for (len in minOf(RomajiTable.MAX_ROMAJI_LENGTH, s.length - i) downTo 1) {
            val piece = s.substring(i, i + len)
            if (RomajiTable.contains(piece)) return piece
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // 解析
    // ---------------------------------------------------------------------------------

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

        fun result(isValid: Boolean, partial: String, reason: String?) = RomajiAnalysis(
            isValid = isValid,
            tokens = tokens,
            partial = partial,
            invalidReason = reason,
            strongYouon = strongYouon,
            tsu = tsu,
            sokuon = sokuon,
            longVowels = longVowels
        )

        while (i < s.length) {
            val c = s[i]
            if (!isRomajiChar(c)) {
                // 空白や句読点・記号はそのままトークンとして保持し、文全体の解析を継続
                tokens.add(RomajiToken(c.toString(), c.toString()))
                i += 1
                continue
            }

            val hatsuon = hatsuonLength(s, i)
            if (hatsuon > 0) {
                tokens.add(RomajiToken(s.substring(i, i + hatsuon), "ん"))
                i += hatsuon
                continue
            }

            if (isSokuon(s, i)) {
                tokens.add(RomajiToken(c.toString(), "っ"))
                sokuon++
                i += 1
                continue
            }

            val piece = longestMatch(s, i)
            if (piece != null) {
                val kana = RomajiTable.entries.getValue(piece)
                if (StrongYouonKana.any { kana.contains(it) }) strongYouon++
                if (piece == "tsu") tsu++
                if (kana == "う" && tokens.isNotEmpty() && tokens.last().romaji.endsWith("o")) longVowels++

                tokens.add(RomajiToken(piece, kana))
                i += piece.length
                continue
            }

            // 表に直接マッチしなかった場合、未確定子音（入力途中）か確認
            val rest = s.substring(i)
            if (RomajiTable.isPartial(rest)) return result(true, rest, null)

            return result(false, "", "ローマ字として無効な並び: '$rest'")
        }

        val hasKanaTokens = tokens.any { RomajiTable.contains(it.romaji) || it.kana == "ん" || it.kana == "っ" }
        if (!hasKanaTokens) return result(false, "", "ローマ字トークンなし")

        return result(true, "", null)
    }

    /**
     * 入力途中のローマ字をできる限りひらがなに変換する（入力プレビュー用）
     * ローマ字として解釈できない子音や記号があっても文字を途中で破棄せず、
     * 通常の日本語IMEと同様に英字を残したまま後続の文字の変換を継続します。
     */
    fun toKanaLenient(letters: String): String {
        if (letters.isEmpty()) return ""
        val s = letters.lowercase()
        val sb = StringBuilder()
        var i = 0

        while (i < s.length) {
            val c = s[i]

            // 英字以外・ハイフン以外はそのまま
            if (!isRomajiChar(c)) {
                sb.append(c)
                i += 1
                continue
            }

            val hatsuon = hatsuonLength(s, i)
            if (hatsuon > 0) {
                sb.append("ん")
                i += hatsuon
                continue
            }

            if (isSokuon(s, i)) {
                sb.append("っ")
                i += 1
                continue
            }

            val piece = longestMatch(s, i)
            if (piece != null) {
                sb.append(RomajiTable.kanaFor(piece))
                i += piece.length
                continue
            }

            // 末尾がプレフィックス（入力途中子音）ならそのまま追加して完了
            val rest = s.substring(i)
            if (RomajiTable.isPartial(rest)) {
                sb.append(rest)
                break
            }

            // テーブルにマッチせず、かつプレフィックスでもない孤立子音・不正文字はそのまま出力し、次へ進む
            sb.append(c)
            i += 1
        }

        return sb.toString()
    }

    /**
     * 変換・確定の直前に使う、語尾の `n` を「ん」にしたかな変換
     * （入力途中のプレビューは [toKanaLenient] のまま。`n` は次の `na` などになるかもしれないため）
     */
    fun toKanaFinal(letters: String): String = toKanaLenient(letters).withFinalN()

    /**
     * ひらがな文字列をカタカナに変換する
     */
    fun toKatakana(hiragana: String): String {
        return hiragana.map { c ->
            if (c in 'ぁ'..'ゖ') c + 0x60 else c
        }.joinToString("")
    }

    /**
     * 半角英数字・記号列を全角英数字に変換する
     */
    fun toZenkakuAlpha(str: String): String {
        return str.map { c ->
            when (c) {
                in 'a'..'z' -> (c.code - 'a'.code + 0xFF41).toChar()
                in 'A'..'Z' -> (c.code - 'A'.code + 0xFF21).toChar()
                in '0'..'9' -> (c.code - '0'.code + 0xFF10).toChar()
                ' ' -> '　'
                '-' -> '－'
                else -> c
            }
        }.joinToString("")
    }
}

/**
 * 語尾に残った半角の `n`（前が英字でないもの）を「ん」にする
 * 例: "はろうぃn" -> "はろうぃん"、"n" -> "ん"。英語の語尾（"pen" の n など、前が英字）は変えない
 */
internal fun String.withFinalN(): String {
    if (!endsWith('n')) return this
    val prev = if (length >= 2) this[length - 2] else null
    if (prev != null && (prev in 'a'..'z' || prev in 'A'..'Z')) return this
    return dropLast(1) + "ん"
}
