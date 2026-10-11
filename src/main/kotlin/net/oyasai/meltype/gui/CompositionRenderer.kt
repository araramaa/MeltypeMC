package net.oyasai.meltype.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.network.chat.Component
import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.InputMode
import net.oyasai.meltype.engine.MeltypeSession
import java.lang.reflect.Field

/**
 * チャット入力欄および各種テキスト入力画面向けのUIレンダラー。
 *
 * 主な責務:
 * 1. チャット入力欄先頭のモード切替アイコンボタン（[ A ] / [あ/A]）の描画とクリック判定
 * 2. 入力欄 EditBox の自動オフセット（ボタンと入力文字の重なり防止）
 * 3. 変換候補リストウィンドウの描画（他MODウィジェットとの衝突回避付き）
 * 4. 入力中ひらがなプレビュー（下線付き）の描画
 */
object CompositionRenderer {

    // --- UI レイアウト寸法定数 ---
    const val BUTTON_WIDTH = 26
    const val BUTTON_HEIGHT = 12
    private const val CANDIDATE_BOX_HEIGHT = 14
    private const val TOOLTIP_HEIGHT = 14

    // --- カラーパレット定数 ---
    private const val BG_TRANSPARENT_BLACK = 0xCC000000.toInt()
    private const val HIGHLIGHT_BLUE = 0x88336699.toInt()
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private const val COLOR_ACCENT_ORANGE = 0xFFFFAA00.toInt()

    // DIRECTモード（半角英数）ボタンスタイル
    private const val COLOR_DIRECT_BORDER = 0xFF5A6978.toInt()
    private const val COLOR_DIRECT_BG = 0xEE181822.toInt()
    private const val COLOR_DIRECT_TEXT = 0xFFC8D0D8.toInt()

    // HYBRIDモード（日本語変換）ボタンスタイル
    private const val COLOR_HYBRID_BORDER = 0xFF00E5A3.toInt()
    private const val COLOR_HYBRID_BG = 0xEE0B291E.toInt()
    private const val COLOR_HYBRID_TEXT = 0xFF55FFAA.toInt()

    // ホバー時スタイル
    private const val COLOR_HOVER_BORDER = 0xFFFFFFFF.toInt()
    private const val COLOR_HOVER_DIRECT_BG = 0xEE2A2C38.toInt()
    private const val COLOR_HOVER_HYBRID_BG = 0xFF144030.toInt()

    // リフレクションキャッシュ（スレッドセーフ）
    @Volatile
    private var cachedChatInputField: Field? = null

    /**
     * 各画面におけるモード切替ボタンの矩形領域 [X, Y, Width, Height] を算出
     */
    fun getButtonBounds(screen: Screen): IntArray {
        val screenWidth = screen.width
        val screenHeight = screen.height
        val className = screen.javaClass.name

        return when {
            // チャット画面: 入力欄の先頭（左端 x = 2, y = screenHeight - 14）
            screen is ChatScreen || className.contains("Chat") -> {
                intArrayOf(2, screenHeight - 14, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            // 金床画面
            screen is AnvilScreen || className.contains("Anvil") -> {
                val bx = (screenWidth - 176) / 2 + 58
                val by = (screenHeight - 166) / 2 + 38
                intArrayOf(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            // 看板編集画面
            screen is AbstractSignEditScreen || className.contains("Sign") -> {
                intArrayOf((screenWidth / 2) - 100, screenHeight - 50, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            // 本編集画面
            screen is BookEditScreen || className.contains("Book") -> {
                intArrayOf((screenWidth / 2) - 80, screenHeight - 35, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            // フォールバック
            else -> {
                intArrayOf(2, screenHeight - 14, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
        }
    }

    /**
     * チャット入力欄（EditBox）の開始位置をボタンの右側へオフセット
     * 入力された文字やカーソルがボタンと絶対に重ならないように保護します。
     */
    private fun adjustChatInputOffset(screen: Screen) {
        if (screen !is ChatScreen) return
        try {
            var field = cachedChatInputField
            if (field == null) {
                field = ChatScreen::class.java.declaredFields.firstOrNull {
                    EditBox::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }
                cachedChatInputField = field
            }
            val editBox = field?.get(screen) as? EditBox ?: return
            val targetX = 2 + BUTTON_WIDTH + 2
            val targetWidth = screen.width - targetX - 2
            if (editBox.x != targetX || editBox.width != targetWidth) {
                editBox.x = targetX
                editBox.width = targetWidth
            }
        } catch (_: Throwable) {
            // リフレクション失敗時もゲームクラッシュを防止
        }
    }

    /**
     * 画面上の他MODウィジェット（Chat Patches の検索バー等）を検出し、
     * 変換候補ウィンドウが被らない安全なY座標を算出
     */
    private fun getSafeCandidatesBaseY(screen: Screen): Int {
        val screenHeight = screen.height
        val className = screen.javaClass.name
        if (screen !is ChatScreen && !className.contains("Chat")) {
            return screenHeight - 26
        }

        var highestObstacleY = screenHeight - 14
        try {
            for (child in screen.children()) {
                if (child is AbstractWidget && child.visible) {
                    val cy = child.y
                    // 入力欄の真上（下から50px以内）にある障害物ウィジェットを検出
                    if (cy in (screenHeight - 50) until highestObstacleY) {
                        highestObstacleY = minOf(highestObstacleY, cy)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return highestObstacleY
    }

    /**
     * フォアグラウンドUI全体のメイン描画メソッド
     */
    fun render(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession, mouseX: Int = -1, mouseY: Int = -1) {
        if (!MeltypeConfig.enabled) return

        try {
            // 1. チャット入力欄の自動オフセット
            adjustChatInputOffset(screen)

            // 2. モード切替アイコンボタンの描画
            if (MeltypeConfig.showModeIndicator) {
                renderModeButton(screen, extractor, session, mouseX, mouseY)
            }

            // 変換中（isComposing）でなければ候補やプレビューの描画は不要
            if (!session.isComposing) return

            val obstacleY = getSafeCandidatesBaseY(screen)

            // 3. 変換候補リストの描画
            if (session.hasCandidates) {
                renderCandidatesList(screen, extractor, session, obstacleY)
            }

            // 4. 入力中ひらがなプレビュー（下線付き）の描画
            renderUnderlinePreview(screen, extractor, session, obstacleY)
        } catch (_: Throwable) {
            // レンダリング例外時もゲーム本体のクラッシュを絶対に防止するフェイルセーフ
        }
    }

    /**
     * モード切替アイコンボタンおよびホバーツールチップを描画
     */
    private fun renderModeButton(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession, mouseX: Int, mouseY: Int) {
        val client = Minecraft.getInstance()
        val font = client.font
        val bounds = getButtonBounds(screen)
        val btnX = bounds[0]
        val btnY = bounds[1]
        val btnW = bounds[2]
        val btnH = bounds[3]

        val isHovered = mouseX in btnX..(btnX + btnW) && mouseY in btnY..(btnY + btnH)
        val mode = session.currentInputMode
        val iconText = mode.iconText

        val borderColor = when {
            isHovered -> COLOR_HOVER_BORDER
            mode == InputMode.HYBRID -> COLOR_HYBRID_BORDER
            else -> COLOR_DIRECT_BORDER
        }

        val bgColor = when {
            isHovered && mode == InputMode.HYBRID -> COLOR_HOVER_HYBRID_BG
            isHovered -> COLOR_HOVER_DIRECT_BG
            mode == InputMode.HYBRID -> COLOR_HYBRID_BG
            else -> COLOR_DIRECT_BG
        }

        val textColor = when {
            isHovered -> COLOR_WHITE
            mode == InputMode.HYBRID -> COLOR_HYBRID_TEXT
            else -> COLOR_DIRECT_TEXT
        }

        // ボタン枠（1pxボーダー）と背景
        extractor.fill(btnX, btnY, btnX + btnW, btnY + btnH, borderColor)
        extractor.fill(btnX + 1, btnY + 1, btnX + btnW - 1, btnY + btnH - 1, bgColor)

        // 中央揃えテキスト
        val textW = font.width(iconText)
        val textX = btnX + (btnW - textW) / 2
        val textY = btnY + (btnH - 8) / 2
        extractor.textRenderer().accept(textX, textY, Component.literal(iconText).withColor(textColor))

        // ホバーツールチップ
        if (isHovered) {
            val tooltipText = when (mode) {
                InputMode.DIRECT -> "半角英数 (左クリック: 日本語切替 / 右クリック: 設定)"
                InputMode.HYBRID -> "日本語変換 (左クリック: 半角切替 / 右クリック: 設定)"
            }
            val ttW = font.width(tooltipText) + 8
            val ttX = btnX.coerceAtMost(screen.width - ttW - 4)
            val ttY = (btnY - TOOLTIP_HEIGHT - 2).coerceAtLeast(2)

            extractor.fill(ttX, ttY, ttX + ttW, ttY + TOOLTIP_HEIGHT, 0xF0101015.toInt())
            extractor.fill(ttX, ttY, ttX + ttW, ttY + 1, borderColor)
            extractor.textRenderer().accept(ttX + 4, ttY + 3, Component.literal(tooltipText).withColor(0xFFE0E0E0.toInt()))
        }
    }

    /**
     * 変換候補リストウィンドウを描画
     */
    private fun renderCandidatesList(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession, obstacleY: Int) {
        val candidates = session.candidates
        if (candidates.isEmpty()) return

        val client = Minecraft.getInstance()
        val font = client.font
        val screenWidth = screen.width

        val candidateTexts = candidates.mapIndexed { idx, cand ->
            val num = idx + 1
            val prefix = if (idx == session.selectedCandidateIndex) "▶ $num." else "  $num."
            "$prefix $cand "
        }

        var totalWidth = 8
        for (text in candidateTexts) {
            totalWidth += font.width(text) + 4
        }
        val boxY = obstacleY - CANDIDATE_BOX_HEIGHT - 2
        val safeX = 4.coerceIn(2, maxOf(2, screenWidth - totalWidth - 2))

        // 背景ボックス
        extractor.fill(safeX, boxY, safeX + totalWidth, boxY + CANDIDATE_BOX_HEIGHT, BG_TRANSPARENT_BLACK)

        // 各候補の描画
        var curX = safeX + 4
        for ((idx, text) in candidateTexts.withIndex()) {
            val itemWidth = font.width(text)
            val isSelected = idx == session.selectedCandidateIndex

            if (isSelected) {
                extractor.fill(curX - 2, boxY + 1, curX + itemWidth + 2, boxY + CANDIDATE_BOX_HEIGHT - 1, HIGHLIGHT_BLUE)
            }

            val color = if (isSelected) COLOR_ACCENT_ORANGE else COLOR_WHITE
            extractor.textRenderer().accept(curX, boxY + 3, Component.literal(text).withColor(color))
            curX += itemWidth + 4
        }
    }

    /**
     * 入力中のひらがなプレビュー（下線付き）を描画
     */
    private fun renderUnderlinePreview(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession, obstacleY: Int) {
        val preview = session.previewKana.ifEmpty { session.rawBuffer.toString() }
        if (preview.isEmpty()) return

        val client = Minecraft.getInstance()
        val font = client.font
        val screenWidth = screen.width

        val previewText = "変換中: $preview"
        val textWidth = font.width(previewText)
        val previewY = if (session.hasCandidates) obstacleY - 32 else obstacleY - 16
        val safeX = 4.coerceIn(2, maxOf(2, screenWidth - textWidth - 8))

        // 背景
        extractor.fill(safeX, previewY - 2, safeX + textWidth + 8, previewY + 11, BG_TRANSPARENT_BLACK)
        // テキスト
        extractor.textRenderer().accept(safeX + 4, previewY, Component.literal(previewText).withColor(COLOR_ACCENT_ORANGE))
        // 下線
        extractor.fill(safeX + 4, previewY + 10, safeX + 4 + textWidth, previewY + 11, COLOR_ACCENT_ORANGE)
    }

    /**
     * マウスクリック位置がモード切替ボタン上にあるかを安全に判定
     */
    fun isIndicatorClicked(screen: Screen, mouseX: Double, mouseY: Double): Boolean {
        if (!MeltypeConfig.showModeIndicator) return false
        try {
            val bounds = getButtonBounds(screen)
            val btnX = bounds[0]
            val btnY = bounds[1]
            val btnW = bounds[2]
            val btnH = bounds[3]

            // 快適なクリックのため2pxのマージンを付与
            return mouseX >= (btnX - 2) && mouseX <= (btnX + btnW + 2) &&
                   mouseY >= (btnY - 2) && mouseY <= (btnY + btnH + 2)
        } catch (_: Throwable) {
            return false
        }
    }
}
