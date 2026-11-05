package net.oyasai.meltype.engine

import java.text.Normalizer

/**
 * 全角のカタカナを、半角カタカナ（例: コンニチハ → ｺﾝﾆﾁﾊ）に変換する部品。F8 キーで使う。
 *
 * - 濁点・半濁点は、1文字（ガ）を、2文字（ｶ + ﾞ）に分けて出す。
 * - 小さい文字（ｯ ｬ ｭ ｮ など）、長音（ｰ）、句読点（､ ｡ ｢ ｣ ･）も、半角にする。
 * - カタカナ以外の文字（英字・数字・漢字など）は、そのまま通す。
 * - ひらがなは、先にカタカナにしてから渡すこと（[RomajiDetector.toKatakana]）。
 */
object HalfWidthKana {

    /** 濁点（半角） */
    private const val DAKUTEN = 'ﾞ'

    /** 半濁点（半角） */
    private const val HANDAKUTEN = 'ﾟ'

    /** 結合用の濁点・半濁点（Unicode の分解で出てくるもの） */
    private const val COMBINING_DAKUTEN = '゙'
    private const val COMBINING_HANDAKUTEN = '゚'

    private val table: Map<Char, Char> = mapOf(
        'ア' to 'ｱ', 'イ' to 'ｲ', 'ウ' to 'ｳ', 'エ' to 'ｴ', 'オ' to 'ｵ',
        'カ' to 'ｶ', 'キ' to 'ｷ', 'ク' to 'ｸ', 'ケ' to 'ｹ', 'コ' to 'ｺ',
        'サ' to 'ｻ', 'シ' to 'ｼ', 'ス' to 'ｽ', 'セ' to 'ｾ', 'ソ' to 'ｿ',
        'タ' to 'ﾀ', 'チ' to 'ﾁ', 'ツ' to 'ﾂ', 'テ' to 'ﾃ', 'ト' to 'ﾄ',
        'ナ' to 'ﾅ', 'ニ' to 'ﾆ', 'ヌ' to 'ﾇ', 'ネ' to 'ﾈ', 'ノ' to 'ﾉ',
        'ハ' to 'ﾊ', 'ヒ' to 'ﾋ', 'フ' to 'ﾌ', 'ヘ' to 'ﾍ', 'ホ' to 'ﾎ',
        'マ' to 'ﾏ', 'ミ' to 'ﾐ', 'ム' to 'ﾑ', 'メ' to 'ﾒ', 'モ' to 'ﾓ',
        'ヤ' to 'ﾔ', 'ユ' to 'ﾕ', 'ヨ' to 'ﾖ',
        'ラ' to 'ﾗ', 'リ' to 'ﾘ', 'ル' to 'ﾙ', 'レ' to 'ﾚ', 'ロ' to 'ﾛ',
        'ワ' to 'ﾜ', 'ヲ' to 'ｦ', 'ン' to 'ﾝ',
        'ァ' to 'ｧ', 'ィ' to 'ｨ', 'ゥ' to 'ｩ', 'ェ' to 'ｪ', 'ォ' to 'ｫ',
        'ッ' to 'ｯ', 'ャ' to 'ｬ', 'ュ' to 'ｭ', 'ョ' to 'ｮ',
        'ー' to 'ｰ', '、' to '､', '。' to '｡', '「' to '｢', '」' to '｣', '・' to '･'
    )

    /** 全角カタカナを、半角カタカナにする。変換できない文字は、そのまま */
    fun fromKatakana(text: String): String {
        // ガ → カ + 結合用の濁点 のように、濁点・半濁点を別の文字に分けてから変換する
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in decomposed) {
            when (c) {
                COMBINING_DAKUTEN -> sb.append(DAKUTEN)
                COMBINING_HANDAKUTEN -> sb.append(HANDAKUTEN)
                else -> sb.append(table[c] ?: c)
            }
        }
        return sb.toString()
    }
}
