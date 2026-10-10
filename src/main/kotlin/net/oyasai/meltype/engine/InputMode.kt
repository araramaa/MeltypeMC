package net.oyasai.meltype.engine

/**
 * キーボード入力モード
 */
enum class InputMode(val displayName: String, val badge: String) {
    /** 半角英数字モード（バニラ直接入力） */
    ENGLISH("半角英数", "[A]"),

    /** 日本語入力モード（Meltype かな漢字変換） */
    JAPANESE("日本語", "[あ]");

    /** モードをトグル（切り替え） */
    fun toggle(): InputMode = when (this) {
        ENGLISH -> JAPANESE
        JAPANESE -> ENGLISH
    }
}
