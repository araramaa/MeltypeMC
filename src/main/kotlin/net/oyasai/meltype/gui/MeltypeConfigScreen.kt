package net.oyasai.meltype.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.ConversionLearningStore
import net.oyasai.meltype.engine.InputMode
import org.lwjgl.glfw.GLFW

/**
 * Meltype MC の設定画面
 * チャット欄の [あ/A] アイコンを右クリックすることで開きます。
 */
class MeltypeConfigScreen(
    private val parentScreen: Screen?,
    private val learningStore: ConversionLearningStore? = null
) : Screen(Component.literal("Meltype MC 設定")) {

    private var toggleKeyButton: Button? = null
    private var listeningForKey = false
    private var rejectedReason: String? = null

    /** 切り替えキーの案内を表示する Y 座標（ボタン一覧のすぐ下。init で決まる） */
    private var keyHintY = 0

    companion object {
        private const val BUTTON_WIDTH = 280
        private const val BUTTON_HEIGHT = 20
        private const val ROW_SPACING = 22
    }

    override fun init() {
        super.init()

        val centerX = width / 2
        val rowX = centerX - BUTTON_WIDTH / 2
        var currentY = 48

        // 設定ボタンを1行ずつ縦に並べる（押すたびに設定を変えて、ボタンの文字を更新する）
        fun addRow(label: () -> Component, onClick: () -> Unit): Button {
            val button = addSettingButton(rowX, currentY, label, onClick)
            currentY += ROW_SPACING
            return button
        }

        // 1. 候補表示数 (7 <-> 9)
        addRow({ getCandidatesButtonText() }) {
            MeltypeConfig.maxCandidates = if (MeltypeConfig.maxCandidates == 7) 9 else 7
        }

        // 2. チャット開始時の初期入力モード (DIRECT <-> HYBRID)
        addRow({ getInitialModeButtonText() }) {
            MeltypeConfig.initialInputMode = MeltypeConfig.initialInputMode.toggle()
        }

        // 3. 前回のモード状態を維持・固定するか
        addRow({ getRememberModeButtonText() }) {
            MeltypeConfig.rememberLastInputMode = !MeltypeConfig.rememberLastInputMode
        }

        // 4. チャット欄アイコンの表示・非表示
        addRow({ getIndicatorButtonText() }) {
            MeltypeConfig.showModeIndicator = !MeltypeConfig.showModeIndicator
        }

        // 5. スラッシュコマンド時のIME自動解除
        addRow({ getAutoCommandButtonText() }) {
            MeltypeConfig.autoCommandModeOnSlash = !MeltypeConfig.autoCommandModeOnSlash
        }

        // 6. 変換学習機能の有効・無効
        addRow({ getLearningButtonText() }) {
            MeltypeConfig.learningEnabled = !MeltypeConfig.learningEnabled
        }

        // 7. Google 変換の使用（オフ時は打った文字を外部へ送らず、ローカル辞書のみで変換）
        addRow({ getGoogleButtonText() }) {
            MeltypeConfig.useGoogleCgi = !MeltypeConfig.useGoogleCgi
        }

        // 8. 日本語判定のしきい値 (1 -> 2 -> 3 -> 4 -> 1)
        addRow({ getThresholdButtonText() }) {
            val current = MeltypeConfig.japaneseThreshold
            MeltypeConfig.japaneseThreshold = if (current in 1..3) current + 1 else 1
        }

        // 9. 入力モード切り替えキーの登録（押すと登録待ちになり、次に押したキーを登録）
        toggleKeyButton = addRow({ getToggleKeyButtonText() }) {
            listeningForKey = true
            rejectedReason = null
        }
        currentY += 4

        // 10. 下段アクションボタン: 学習履歴リセット (左) & 完了/保存 (右)
        val halfWidth = (BUTTON_WIDTH - 6) / 2

        addRenderableWidget(
            Button.builder(Component.literal("学習履歴をクリア")) { button ->
                learningStore?.clear()
                button.message = Component.literal("履歴をクリアしました✓")
                button.active = false
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, halfWidth, BUTTON_HEIGHT).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("完了 / 保存して戻る")) {
                onClose()
            }.bounds(centerX + 3, currentY, halfWidth, BUTTON_HEIGHT).build()
        )

        // 切り替えキーの案内は、メニュー（ボタン一覧）のすぐ下に表示する
        keyHintY = currentY + BUTTON_HEIGHT + 6
    }

    /** 設定ボタンを1つ追加する。押すと [onClick] で設定を変えたあと、[label] でボタンの文字を作り直す */
    private fun addSettingButton(x: Int, y: Int, label: () -> Component, onClick: () -> Unit): Button {
        val button = Button.builder(label()) { pressed ->
            onClick()
            pressed.message = label()
        }.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        return addRenderableWidget(button)
    }

    private fun getCandidatesButtonText(): Component {
        return Component.literal("変換候補の表示数: ${MeltypeConfig.maxCandidates} 個")
    }

    private fun getInitialModeButtonText(): Component {
        val modeStr = if (MeltypeConfig.initialInputMode == InputMode.DIRECT) "半角英数 (DIRECT)" else "日本語 (HYBRID)"
        return Component.literal("初期モード: $modeStr")
    }

    private fun getRememberModeButtonText(): Component {
        val state = if (MeltypeConfig.rememberLastInputMode) "ON (固定・記憶)" else "OFF (毎回リセット)"
        return Component.literal("前回のモードを維持: $state")
    }

    private fun getIndicatorButtonText(): Component {
        // 非表示にしても、Mod Menu の「設定」ボタンから、この画面を開ける
        val state = if (MeltypeConfig.showModeIndicator) "表示" else "非表示 (Mod Menuで設定)"
        return Component.literal("アイコン [あ/A]: $state")
    }

    private fun getAutoCommandButtonText(): Component {
        val state = if (MeltypeConfig.autoCommandModeOnSlash) "ON" else "OFF"
        return Component.literal("コマンド時IME解除 (/): $state")
    }

    private fun getLearningButtonText(): Component {
        val state = if (MeltypeConfig.learningEnabled) "有効 (ON)" else "無効 (OFF)"
        return Component.literal("変換学習機能: $state")
    }

    private fun getGoogleButtonText(): Component {
        val state = if (MeltypeConfig.useGoogleCgi) "使う (打った文字をGoogleへ送信)" else "使わない (外部へ送信しない)"
        return Component.literal("Google変換: $state")
    }

    private fun getThresholdButtonText(): Component {
        return Component.literal("日本語判定のしきい値: ${MeltypeConfig.japaneseThreshold} (大きいほど英語になりやすい)")
    }

    private fun getToggleKeyButtonText(): Component {
        if (listeningForKey) {
            val reason = rejectedReason
            return Component.literal(
                if (reason == null) "切り替えキー: キーを押してください (Esc: 解除)" else "× $reason"
            )
        }
        val keyName = if (MeltypeConfig.toggleKeyCode < 0) {
            "未設定"
        } else {
            modifierPrefix(MeltypeConfig.toggleKeyModifiers) + keyName(MeltypeConfig.toggleKeyCode)
        }
        return Component.literal("切り替えキー: $keyName")
    }

    /** 登録できなかったキーの理由（ボタンに表示する短い説明） */
    private fun rejectReason(key: Int): String = when {
        key < 0 -> "登録できないキーです。別のキーを"
        key in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_GRAVE_ACCENT ->
            "文字キーは Ctrl か Alt と一緒に"
        else -> "登録できないキーです。別のキーを"
    }

    private fun modifierPrefix(modifiers: Int): String {
        var prefix = ""
        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) prefix += "Ctrl+"
        if ((modifiers and GLFW.GLFW_MOD_ALT) != 0) prefix += "Alt+"
        if ((modifiers and GLFW.GLFW_MOD_SHIFT) != 0) prefix += "Shift+"
        return prefix
    }

    private fun keyName(keyCode: Int): String {
        if (keyCode in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) return "F${keyCode - GLFW.GLFW_KEY_F1 + 1}"
        val named = try { GLFW.glfwGetKeyName(keyCode, 0) } catch (_: Throwable) { null }
        return named?.uppercase() ?: "キー$keyCode"
    }

    /** 登録待ちの間に押されたキーを切り替えキーとして保存（Esc で解除） */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!listeningForKey) return super.keyPressed(event)

        val key = event.key()
        val mods = event.modifiers() and MeltypeConfig.TOGGLE_MODIFIER_MASK
        when {
            key == GLFW.GLFW_KEY_ESCAPE -> {
                MeltypeConfig.toggleKeyCode = -1
                MeltypeConfig.toggleKeyModifiers = 0
                listeningForKey = false
            }
            // 修飾キー単体は、次に押される本体キーを待つ
            key in GLFW.GLFW_KEY_LEFT_SHIFT..GLFW.GLFW_KEY_RIGHT_SUPER -> return true
            MeltypeConfig.isAllowedToggleKey(key, mods) -> {
                MeltypeConfig.toggleKeyCode = key
                MeltypeConfig.toggleKeyModifiers = mods
                listeningForKey = false
            }
            else -> {
                // 登録できないキーは登録せず、理由を表示して待機を続ける
                rejectedReason = rejectReason(key)
                toggleKeyButton?.message = getToggleKeyButtonText()
                return true
            }
        }
        rejectedReason = null
        toggleKeyButton?.message = getToggleKeyButtonText()
        return true
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        // タイトル表示
        val titleWidth = font.width(title)
        extractor.textRenderer().accept((width - titleWidth) / 2, 16, title.copy().withColor(0xFFFFFFFF.toInt()))

        // サブタイトル表示
        val subtitle = Component.literal("チャット・日本語入力の動作設定").withColor(0xFFAAAAAA.toInt())
        val subtitleWidth = font.width(subtitle)
        extractor.textRenderer().accept((width - subtitleWidth) / 2, 30, subtitle)

        // 切り替えキーの案内（メニューのすぐ下）
        val keyHint = Component.literal("モード切り替え: Ctrl+Space / 半角全角 / 変換 / 無変換キー").withColor(0xFFAAAAAA.toInt())
        val keyHintWidth = font.width(keyHint)
        extractor.textRenderer().accept((width - keyHintWidth) / 2, keyHintY, keyHint)

        // 画面下部ヒント
        val hint = Component.literal("※ チャット欄の [あ/A] アイコンを右クリックでいつでも開けます").withColor(0xFF888888.toInt())
        val hintWidth = font.width(hint)
        extractor.textRenderer().accept((width - hintWidth) / 2, height - 20, hint)
    }

    override fun onClose() {
        MeltypeConfig.save()
        if (parentScreen != null) {
            minecraft.setScreenAndShow(parentScreen)
        } else {
            super.onClose()
        }
    }
}
