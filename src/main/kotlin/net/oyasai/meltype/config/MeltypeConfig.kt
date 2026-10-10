package net.oyasai.meltype.config

import net.oyasai.meltype.engine.InputMode

/**
 * Meltype MC の設定クラス
 * 読みやすく、直しやすく、安全にデフォルト値を管理します。
 */
object MeltypeConfig {
    /** Meltype 日本語入力の有効/無効 */
    var enabled: Boolean = true

    /**
     * スラッシュ ('/') 入力時に自動でコマンド入力モード（IMEバイパス）にするか
     */
    var autoCommandModeOnSlash: Boolean = true

    /** 日本語と判定するスコアのしきい値 (Meltype Core 準拠: デフォルト 2) */
    var japaneseThreshold: Int = 2

    /** Google CGI API を使った高精度かな漢字変換を使用するか */
    var useGoogleCgi: Boolean = true

    /** 変換候補の最大表示数 (1〜9) */
    var maxCandidates: Int = 5

    /** タイポ自動補正を有効にするか */
    var typoCorrectionEnabled: Boolean = true

    /** チャット画面等を開いた際の初期入力モード（デフォルト: DIRECT = 何もしない時は半角英数字） */
    var initialInputMode: InputMode = InputMode.DIRECT

    /** 前回の入力モードを画面を跨いで記憶・固定するか（true: 一度切り替えたら次に押すまでモードを固定） */
    var rememberLastInputMode: Boolean = true

    /** 画面上にモードインジケーター（[Mel] / [A]）を表示するか */
    var showModeIndicator: Boolean = true

    /**
     * 有効/無効を切り替えるトグル関数
     * @return 切り替え後の状態
     */
    fun toggleEnabled(): Boolean {
        enabled = !enabled
        return enabled
    }
}
