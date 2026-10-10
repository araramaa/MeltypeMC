package net.oyasai.meltype.engine

/**
 * キーボード入力モード
 */
enum class InputMode(val displayName: String, val badge: String, val iconText: String) {
    /** ハイブリッド自動判別モード（半角/全角キー不要・自動英日打ち分け） */
    HYBRID("日本語変換", "[あ/A]", "あ/A"),

    /** 直接入力固定モード（半角英数直接入力） */
    DIRECT("半角英数", "[A]", " A ");

    /** モードをトグル（切り替え） */
    fun toggle(): InputMode = when (this) {
        HYBRID -> DIRECT
        DIRECT -> HYBRID
    }
}
