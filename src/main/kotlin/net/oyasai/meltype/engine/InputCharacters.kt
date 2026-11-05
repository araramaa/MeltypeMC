package net.oyasai.meltype.engine

/**
 * 入力された1文字が、どの種類の文字かを見分ける部品。状態を持たない。
 */
object InputCharacters {

    /** バッククォート（半角/全角キーが文字として届いたもの）か。`~` は記号として有効なので含めない */
    fun isBackquote(c: Char): Boolean = c == '`' || c == '｀'

    /** ローマ字・英単語として、入力バッファに足せる文字（英字とハイフン）か */
    fun isRomajiInputChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '-'

    /** 波線の記号（`~` `～` `〜`）か */
    fun isTilde(c: Char): Boolean = c == '~' || c == '～' || c == '〜'

    /** 入力中に打った半角の句読点・記号を、日本語の記号にする（対象外の文字は null） */
    fun toFullWidthPunctuation(c: Char): String? = when (c) {
        ',' -> "、"
        '.' -> "。"
        '?' -> "？"
        '!' -> "！"
        '~', '～', '〜' -> "～"
        else -> null
    }

    /** 外部 IME（Windows の日本語入力など）が確定した文字か（ひらがな・カタカナ・漢字） */
    fun isJapanese(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x309F || // ひらがな
            code in 0x30A0..0x30FF ||    // カタカナ
            code in 0x4E00..0x9FFF ||    // CJK 統合漢字
            code in 0x3400..0x4DBF       // CJK 拡張 A
    }
}
