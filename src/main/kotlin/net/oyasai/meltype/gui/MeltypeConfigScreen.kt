package net.oyasai.meltype.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.ConversionLearningStore
import net.oyasai.meltype.engine.InputMode

/**
 * Meltype MC の設定画面
 * チャット欄の [あ/A] アイコンを右クリックすることで開きます。
 */
class MeltypeConfigScreen(
    private val parentScreen: Screen?,
    private val learningStore: ConversionLearningStore? = null
) : Screen(Component.literal("Meltype MC 設定")) {

    companion object {
        private const val BUTTON_WIDTH = 250
        private const val BUTTON_HEIGHT = 20
        private const val ROW_SPACING = 24
    }

    override fun init() {
        super.init()

        val centerX = width / 2
        var currentY = 48

        // 1. 候補表示数 (3 -> 5 -> 7 -> 9 -> 3)
        addRenderableWidget(
            Button.builder(getCandidatesButtonText()) { button ->
                val next = when (MeltypeConfig.maxCandidates) {
                    3 -> 5
                    5 -> 7
                    7 -> 9
                    else -> 3
                }
                MeltypeConfig.maxCandidates = next
                button.message = getCandidatesButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING

        // 2. チャット開始時の初期入力モード (DIRECT ↔ HYBRID)
        addRenderableWidget(
            Button.builder(getInitialModeButtonText()) { button ->
                MeltypeConfig.initialInputMode = if (MeltypeConfig.initialInputMode == InputMode.DIRECT) {
                    InputMode.HYBRID
                } else {
                    InputMode.DIRECT
                }
                button.message = getInitialModeButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING

        // 3. 前回のモード状態を維持・固定するか
        addRenderableWidget(
            Button.builder(getRememberModeButtonText()) { button ->
                MeltypeConfig.rememberLastInputMode = !MeltypeConfig.rememberLastInputMode
                button.message = getRememberModeButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING

        // 4. チャット欄アイコンの表示・非表示
        addRenderableWidget(
            Button.builder(getIndicatorButtonText()) { button ->
                MeltypeConfig.showModeIndicator = !MeltypeConfig.showModeIndicator
                button.message = getIndicatorButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING

        // 5. スラッシュコマンド時のIME自動解除
        addRenderableWidget(
            Button.builder(getAutoCommandButtonText()) { button ->
                MeltypeConfig.autoCommandModeOnSlash = !MeltypeConfig.autoCommandModeOnSlash
                button.message = getAutoCommandButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING

        // 6. 変換学習機能の有効・無効
        addRenderableWidget(
            Button.builder(getLearningButtonText()) { button ->
                MeltypeConfig.learningEnabled = !MeltypeConfig.learningEnabled
                button.message = getLearningButtonText()
            }.bounds(centerX - BUTTON_WIDTH / 2, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build()
        )
        currentY += ROW_SPACING + 4

        // 7. 下段アクションボタン: 学習履歴リセット (左) & 完了/保存 (右)
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
        val state = if (MeltypeConfig.showModeIndicator) "表示" else "非表示"
        return Component.literal("チャット欄アイコン [あ/A]: $state")
    }

    private fun getAutoCommandButtonText(): Component {
        val state = if (MeltypeConfig.autoCommandModeOnSlash) "ON" else "OFF"
        return Component.literal("コマンド時IME解除 (/): $state")
    }

    private fun getLearningButtonText(): Component {
        val state = if (MeltypeConfig.learningEnabled) "有効 (ON)" else "無効 (OFF)"
        return Component.literal("変換学習機能: $state")
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
