package net.oyasai.meltype.config

import net.oyasai.meltype.engine.InputMode
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Meltype MC の設定クラス
 * 読みやすく、直しやすく、安全に設定を管理・永続化します。
 * .minecraft/config/meltype/config.properties に保存されます。
 */
object MeltypeConfig {
    /** Meltype 日本語入力の有効/無効 */
    var enabled: Boolean = true

    /** スラッシュ ('/') 入力時に自動でコマンド入力モード（IMEバイパス）にするか */
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

    /** 画面上にモード切替アイコンボタン（[ A ] / [あ/A]）を表示するか */
    var showModeIndicator: Boolean = true

    /** 変換学習機能（よく使う単語を記憶して最上位に表示）を有効にするか */
    var learningEnabled: Boolean = true

    init {
        load()
    }

    /**
     * 設定ファイルの取得
     */
    fun getConfigFile(): File {
        return try {
            val fabricClass = Class.forName("net.fabricmc.loader.api.FabricLoader")
            val getInstance = fabricClass.getMethod("getInstance")
            val loader = getInstance.invoke(null)
            val getConfigDir = fabricClass.getMethod("getConfigDir")
            val configPath = getConfigDir.invoke(loader) as java.nio.file.Path
            configPath.resolve("meltype").resolve("config.properties").toFile()
        } catch (_: Throwable) {
            File("config/meltype/config.properties")
        }
    }

    /**
     * 設定ファイルからの読み込み
     */
    fun load() {
        val file = getConfigFile()
        if (!file.exists()) return

        try {
            val props = Properties()
            file.reader(StandardCharsets.UTF_8).use { props.load(it) }

            props.getProperty("enabled")?.toBooleanStrictOrNull()?.let { enabled = it }
            props.getProperty("maxCandidates")?.toIntOrNull()?.let { maxCandidates = it.coerceIn(1, 9) }
            props.getProperty("initialInputMode")?.let {
                initialInputMode = if (it.equals("HYBRID", ignoreCase = true)) InputMode.HYBRID else InputMode.DIRECT
            }
            props.getProperty("rememberLastInputMode")?.toBooleanStrictOrNull()?.let { rememberLastInputMode = it }
            props.getProperty("showModeIndicator")?.toBooleanStrictOrNull()?.let { showModeIndicator = it }
            props.getProperty("autoCommandModeOnSlash")?.toBooleanStrictOrNull()?.let { autoCommandModeOnSlash = it }
            props.getProperty("learningEnabled")?.toBooleanStrictOrNull()?.let { learningEnabled = it }
        } catch (_: Throwable) {
        }
    }

    /**
     * 設定ファイルへの同期保存
     */
    fun save() {
        val file = getConfigFile()
        try {
            file.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty("enabled", enabled.toString())
            props.setProperty("maxCandidates", maxCandidates.toString())
            props.setProperty("initialInputMode", initialInputMode.name)
            props.setProperty("rememberLastInputMode", rememberLastInputMode.toString())
            props.setProperty("showModeIndicator", showModeIndicator.toString())
            props.setProperty("autoCommandModeOnSlash", autoCommandModeOnSlash.toString())
            props.setProperty("learningEnabled", learningEnabled.toString())

            file.writer(StandardCharsets.UTF_8).use {
                props.store(it, "Meltype MC Configuration")
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 有効/無効を切り替えるトグル関数
     * @return 切り替え後の状態
     */
    fun toggleEnabled(): Boolean {
        enabled = !enabled
        save()
        return enabled
    }
}
