package net.oyasai.meltype.config

import net.oyasai.meltype.engine.InputMode
import org.lwjgl.glfw.GLFW
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

    /** 変換候補の最大表示数 (7 または 9。生英字・ひらがなを枠内に残すため 7 未満にはしない) */
    var maxCandidates: Int = 7

    /** チャット画面等を開いた際の初期入力モード（デフォルト: HYBRID = 半角全角キー不要でそのまま日本語/英語自動判別） */
    var initialInputMode: InputMode = InputMode.HYBRID

    /** 前回の入力モードを画面を跨いで記憶・固定するか（true: 一度切り替えたら次に押すまでモードを固定） */
    var rememberLastInputMode: Boolean = true

    /** 画面上にモード切替アイコンボタン（[ A ] / [あ/A]）を表示するか */
    var showModeIndicator: Boolean = true

    /** 変換学習機能（よく使う単語を記憶して最上位に表示）を有効にするか */
    var learningEnabled: Boolean = true

    /** 入力モード切り替えキーの GLFW キーコード（-1: 未設定） */
    var toggleKeyCode: Int = -1

    /** 入力モード切り替えキーと同時に押す修飾キー（GLFW_MOD_SHIFT / CONTROL / ALT の組み合わせ） */
    var toggleKeyModifiers: Int = 0

    init {
        load()
    }

    /** 切り替えキーとして登録できるキーか（通常の文字入力・Enter・Backspace 等を奪わないための制限） */
    fun isAllowedToggleKey(keyCode: Int, modifiers: Int): Boolean {
        val hasCtrlOrAlt = (modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT)) != 0
        return when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_BACKSPACE,
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> false
            // 文字・数字・記号キー（GLFW 32〜96）は Ctrl / Alt との組み合わせのみ許可
            in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_GRAVE_ACCENT -> hasCtrlOrAlt
            // 修飾キー単体（Shift / Ctrl / Alt / Super）は登録不可
            in GLFW.GLFW_KEY_LEFT_SHIFT..GLFW.GLFW_KEY_RIGHT_SUPER -> false
            else -> keyCode >= 0
        }
    }

    /** 押されたキーが登録済みの切り替えキーと一致するか */
    fun isToggleKey(keyCode: Int, modifiers: Int): Boolean {
        return toggleKeyCode >= 0 && keyCode == toggleKeyCode &&
            (modifiers and TOGGLE_MODIFIER_MASK) == toggleKeyModifiers
    }

    const val TOGGLE_MODIFIER_MASK: Int = GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT

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
            props.getProperty("maxCandidates")?.toIntOrNull()?.let { maxCandidates = if (it >= 9) 9 else 7 }
            props.getProperty("initialInputMode")?.let {
                initialInputMode = if (it.equals("HYBRID", ignoreCase = true)) InputMode.HYBRID else InputMode.DIRECT
            }
            props.getProperty("rememberLastInputMode")?.toBooleanStrictOrNull()?.let { rememberLastInputMode = it }
            props.getProperty("showModeIndicator")?.toBooleanStrictOrNull()?.let { showModeIndicator = it }
            props.getProperty("autoCommandModeOnSlash")?.toBooleanStrictOrNull()?.let { autoCommandModeOnSlash = it }
            props.getProperty("learningEnabled")?.toBooleanStrictOrNull()?.let { learningEnabled = it }
            props.getProperty("japaneseThreshold")?.toIntOrNull()?.let { japaneseThreshold = it }
            props.getProperty("useGoogleCgi")?.toBooleanStrictOrNull()?.let { useGoogleCgi = it }
            props.getProperty("toggleKeyCode")?.toIntOrNull()?.let { toggleKeyCode = it }
            props.getProperty("toggleKeyModifiers")?.toIntOrNull()?.let { toggleKeyModifiers = it and TOGGLE_MODIFIER_MASK }
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
            props.setProperty("japaneseThreshold", japaneseThreshold.toString())
            props.setProperty("useGoogleCgi", useGoogleCgi.toString())
            props.setProperty("toggleKeyCode", toggleKeyCode.toString())
            props.setProperty("toggleKeyModifiers", toggleKeyModifiers.toString())

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
