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
 * チャット入力欄の先頭に直感的なトグルボタン（[A] / [あ/A]）を描画し、
 * 入力中テキスト（下線プレビュー）および変換候補ウィンドウを描画するレンダラー。
 * Chat Patches の検索バー等の他MODウィジェットとの重なりを完全回避します。
 */
object CompositionRenderer {

    // ボタン寸法
    const val BUTTON_WIDTH = 26
    const val BUTTON_HEIGHT = 12

    // カラーパレット
    private const val BG_COLOR = 0xCC000000.toInt()          // 汎用半透明黒
    private const val HIGHLIGHT_BG = 0x88336699.toInt()      // 選択中ハイライト青
    private const val TEXT_COLOR = 0xFFFFFFFF.toInt()        // 白文字
    private const val ACCENT_COLOR = 0xFFFFAA00.toInt()      // オレンジ/金色アクセント

    // DIRECTモード（半角英数）ボタン色
    private const val DIRECT_BORDER = 0xFF5A6978.toInt()     // 落ち着いたスレートグレー枠
    private const val DIRECT_BG = 0xEE181822.toInt()         // ダークグレー背景
    private const val DIRECT_TEXT = 0xFFC8D0D8.toInt()       // シルバー文字

    // HYBRIDモード（日本語変換）ボタン色
    private const val HYBRID_BORDER = 0xFF00E5A3.toInt()     // 鮮やかなエメラルドグリーン枠
    private const val HYBRID_BG = 0xEE0B291E.toInt()         // ダークエメラルド背景
    private const val HYBRID_TEXT = 0xFF55FFAA.toInt()       // エメラルドグリーン文字

    // ホバー時（マウス乗下時）
    private const val HOVER_BORDER = 0xFFFFFFFF.toInt()      // ピュアホワイト枠
    private const val HOVER_DIRECT_BG = 0xEE2A2C38.toInt()
    private const val HOVER_HYBRID_BG = 0xFF144030.toInt()

    private var chatScreenInputField: Field? = null

    /**
     * 画面種別に応じたボタンの (X, Y) 座標を算出
     */
    fun getButtonBounds(screen: Screen): IntArray {
        val screenWidth = screen.width
        val screenHeight = screen.height
        val className = screen.javaClass.name

        return when {
            screen is ChatScreen || className.contains("Chat") -> {
                // チャット画面: チャット入力欄（screenHeight - 14）の先頭（左端 x = 2）
                intArrayOf(2, screenHeight - 14, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            screen is AnvilScreen || className.contains("Anvil") -> {
                val bx = (screenWidth - 176) / 2 + 58
                val by = (screenHeight - 166) / 2 + 38
                intArrayOf(bx, by, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            screen is AbstractSignEditScreen || className.contains("Sign") -> {
                intArrayOf((screenWidth / 2) - 100, screenHeight - 50, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            screen is BookEditScreen || className.contains("Book") -> {
                intArrayOf((screenWidth / 2) - 80, screenHeight - 35, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
            else -> {
                intArrayOf(2, screenHeight - 14, BUTTON_WIDTH, BUTTON_HEIGHT)
            }
        }
    }

    /**
     * ChatScreen の EditBox をボタンの右側にオフセット（文字とボタンが被らないように自動調整）
     */
    private fun adjustChatInputOffset(screen: Screen) {
        if (screen !is ChatScreen) return
        try {
            if (chatScreenInputField == null) {
                chatScreenInputField = ChatScreen::class.java.declaredFields.firstOrNull {
                    EditBox::class.java.isAssignableFrom(it.type)
                }?.apply { isAccessible = true }
            }
            val editBox = chatScreenInputField?.get(screen) as? EditBox ?: return
            val targetX = 2 + BUTTON_WIDTH + 2
            val targetWidth = screen.width - targetX - 2
            if (editBox.x != targetX || editBox.width != targetWidth) {
                editBox.x = targetX
                editBox.width = targetWidth
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 画面上の障害物（Chat Patchesの検索バーなど）を検知し、候補ウィンドウの基準Y座標を算出
     */
    private fun getCandidatesBaseY(screen: Screen): Int {
        val screenHeight = screen.height
        val className = screen.javaClass.name
        if (screen !is ChatScreen && !className.contains("Chat")) {
            return screenHeight - 26
        }

        // チャット画面の場合、入力欄の上にある他MODのウィジェット（Chat Patches の検索バーなど）を探索
        var highestObstacleY = screenHeight - 14
        try {
            val children = screen.children()
            for (child in children) {
                if (child is AbstractWidget && child.visible) {
                    val cy = child.y
                    // 入力欄の真上（screenHeight - 50 〜 screenHeight - 14）にあるウィジェットを検知
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
     * 現在開いているScreenのフォアグラウンドにボタン、候補ウィンドウ、下線プレビューを描画
     */
    fun render(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession, mouseX: Int = -1, mouseY: Int = -1) {
        if (!MeltypeConfig.enabled) return

        try {
            val client = Minecraft.getInstance()
            val font = client.font
            val screenWidth = screen.width
            val screenHeight = screen.height

            // 1. チャット入力欄の自動オフセット
            adjustChatInputOffset(screen)

            // 2. モード切り替えアイコンボタンの描画
            val bounds = getButtonBounds(screen)
            val btnX = bounds[0]
            val btnY = bounds[1]
            val btnW = bounds[2]
            val btnH = bounds[3]

            val isHovered = mouseX in btnX..(btnX + btnW) && mouseY in btnY..(btnY + btnH)

            if (MeltypeConfig.showModeIndicator) {
                val mode = session.currentInputMode
                val iconText = mode.iconText

                val borderColor = when {
                    isHovered -> HOVER_BORDER
                    mode == InputMode.HYBRID -> HYBRID_BORDER
                    else -> DIRECT_BORDER
                }

                val bgColor = when {
                    isHovered && mode == InputMode.HYBRID -> HOVER_HYBRID_BG
                    isHovered -> HOVER_DIRECT_BG
                    mode == InputMode.HYBRID -> HYBRID_BG
                    else -> DIRECT_BG
                }

                val textColor = when {
                    isHovered -> TEXT_COLOR
                    mode == InputMode.HYBRID -> HYBRID_TEXT
                    else -> DIRECT_TEXT
                }

                // 1pxボーダー付きボタンの描画
                extractor.fill(btnX, btnY, btnX + btnW, btnY + btnH, borderColor)
                extractor.fill(btnX + 1, btnY + 1, btnX + btnW - 1, btnY + btnH - 1, bgColor)

                // アイコンテキストの中央揃え描画
                val textW = font.width(iconText)
                val textX = btnX + (btnW - textW) / 2
                val textY = btnY + (btnH - 8) / 2
                extractor.textRenderer().accept(textX, textY, Component.literal(iconText).withColor(textColor))

                // マウスホバー時のツールチップ（説明表示）
                if (isHovered) {
                    val tooltipText = when (mode) {
                        InputMode.DIRECT -> "半角英数 (クリックで日本語に切替)"
                        InputMode.HYBRID -> "日本語変換 (クリックで半角に切替)"
                    }
                    val ttW = font.width(tooltipText) + 8
                    val ttH = 14
                    val ttX = btnX.coerceAtMost(screenWidth - ttW - 4)
                    val ttY = (btnY - ttH - 2).coerceAtLeast(2)

                    extractor.fill(ttX, ttY, ttX + ttW, ttY + ttH, 0xF0101015.toInt())
                    extractor.fill(ttX, ttY, ttX + ttW, ttY + 1, borderColor)
                    extractor.textRenderer().accept(ttX + 4, ttY + 3, Component.literal(tooltipText).withColor(0xFFE0E0E0.toInt()))
                }
            }

            // 変換中（isComposing）でなければ以降の描画は不要
            if (!session.isComposing) return

            // 3. 変換候補リストの描画（障害物検知で検索バーとも被らない）
            val obstacleY = getCandidatesBaseY(screen)

            if (session.hasCandidates) {
                val candidates = session.candidates
                if (candidates.isNotEmpty()) {
                    val candidateTexts = candidates.mapIndexed { idx, cand ->
                        val num = idx + 1
                        val prefix = if (idx == session.selectedCandidateIndex) "▶ $num." else "  $num."
                        "$prefix $cand "
                    }

                    var totalWidth = 8
                    for (text in candidateTexts) {
                        totalWidth += font.width(text) + 4
                    }
                    val boxHeight = 14
                    val boxY = obstacleY - boxHeight - 2

                    val safeX = 4.coerceIn(2, maxOf(2, screenWidth - totalWidth - 2))

                    // 背景ボックス
                    extractor.fill(safeX, boxY, safeX + totalWidth, boxY + boxHeight, BG_COLOR)

                    // 各候補の描画
                    var curX = safeX + 4
                    for ((idx, text) in candidateTexts.withIndex()) {
                        val itemWidth = font.width(text)
                        val isSelected = idx == session.selectedCandidateIndex

                        if (isSelected) {
                            extractor.fill(curX - 2, boxY + 1, curX + itemWidth + 2, boxY + boxHeight - 1, HIGHLIGHT_BG)
                        }

                        val color = if (isSelected) ACCENT_COLOR else TEXT_COLOR
                        val comp = Component.literal(text).withColor(color)
                        extractor.textRenderer().accept(curX, boxY + 3, comp)
                        curX += itemWidth + 4
                    }
                }
            }

            // 4. 入力中ひらがなプレビュー（下線付き）の描画
            val preview = session.previewKana.ifEmpty { session.rawBuffer.toString() }
            if (preview.isNotEmpty()) {
                val previewText = "変換中: $preview"
                val textWidth = font.width(previewText)
                val previewY = if (session.hasCandidates) obstacleY - 32 else obstacleY - 16
                val safeX = 4.coerceIn(2, maxOf(2, screenWidth - textWidth - 8))

                // 背景
                extractor.fill(safeX, previewY - 2, safeX + textWidth + 8, previewY + 11, BG_COLOR)
                // テキスト
                extractor.textRenderer().accept(safeX + 4, previewY, Component.literal(previewText).withColor(ACCENT_COLOR))
                // 下線
                extractor.fill(safeX + 4, previewY + 10, safeX + 4 + textWidth, previewY + 11, ACCENT_COLOR)
            }
        } catch (_: Throwable) {
            // 描画エラー時もゲームを落とさないフェイルセーフ保護
        }
    }

    /**
     * マウスクリック位置がモード切替ボタン上にあるかを判定
     */
    fun isIndicatorClicked(screen: Screen, mouseX: Double, mouseY: Double): Boolean {
        if (!MeltypeConfig.showModeIndicator) return false
        try {
            val bounds = getButtonBounds(screen)
            val btnX = bounds[0]
            val btnY = bounds[1]
            val btnW = bounds[2]
            val btnH = bounds[3]

            // 判定マージンを持たせて快適にクリック可能
            return mouseX >= (btnX - 2) && mouseX <= (btnX + btnW + 2) &&
                   mouseY >= (btnY - 2) && mouseY <= (btnY + btnH + 2)
        } catch (_: Throwable) {
            return false
        }
    }
}
