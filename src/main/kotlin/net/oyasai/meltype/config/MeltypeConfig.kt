package net.oyasai.meltype.config

/**
 * Meltype MC の設定クラス
 */
object MeltypeConfig {
    /** Meltype 日本語入力の有効/無効 */
    var enabled: Boolean = true

    /**
     * スラッシュ ('/') 入力時に自動でコマンド入力モード（IMEバイパス）にするか
     * ユーザー指定の要件: スラッシュを入力したときは勝手にコマンド入力モードになって欲しい
     */
    var autoCommandModeOnSlash: Boolean = true

    /** 日本語と判定するスコアのしきい値 (Meltype Core 準拠) */
    var japaneseThreshold: Int = 2

    /** Google CGI API を使った高精度かな漢字変換を使用するか */
    var useGoogleCgi: Boolean = true

    /** 変換候補の最大表示数 */
    var maxCandidates: Int = 5

    /** タイポ自動補正を有効にするか */
    var typoCorrectionEnabled: Boolean = true
}
