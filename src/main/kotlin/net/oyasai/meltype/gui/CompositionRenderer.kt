package net.oyasai.meltype.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.network.chat.Component
import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.InputMode
import net.oyasai.meltype.engine.MeltypeSession

/**
 * 画面上に入力モードインジケーター（[あ] / [A]）、入力中テキスト（下線プレビュー）、および変換候補ウィンドウを描画するレンダラー
 * 画面外はみ出し防止および例外安全ガードを備えています。
 */
object CompositionRenderer {

    private const val BG_COLOR = 0xCC000000.toInt()      // 半透明黒
    private const val HIGHLIGHT_BG = 0x88336699.toInt()  // 選択中ハイライト青
    private const val TEXT_COLOR = 0xFFFFFFFF.toInt()    // 白文字
    private const val ACCENT_COLOR = 0xFFFFAA00.toInt()  // オレンジ/金色アクセント
    private const val DIM_COLOR = 0xFFAAAAAA.toInt()     // 控えめなグレー文字

    /**
     * 現在開いているScreenのフォアグラウンドにプレビュー、候補ウィンドウ、およびモードインジケーターを描画
     */
    fun render(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession) {
        if (!MeltypeConfig.enabled) return

        try {
            val client = Minecraft.getInstance()
            val font = client.font
            val screenWidth = screen.width
            val screenHeight = screen.height

            // 画面種別ごとの基準座標を算出（MOD等によるカスタムチャット画面にも完全対応）
            val className = screen.javaClass.name
            val (baseX, baseY) = when {
                screen is ChatScreen || className.contains("Chat") -> 4 to (screenHeight - 26)
                screen is AnvilScreen || className.contains("Anvil") -> ((screenWidth - 176) / 2 + 58) to ((screenHeight - 166) / 2 + 38)
                screen is AbstractSignEditScreen || className.contains("Sign") -> ((screenWidth / 2) - 100) to (screenHeight - 50)
                screen is BookEditScreen || className.contains("Book") -> ((screenWidth / 2) - 80) to (screenHeight - 35)
                else -> 4 to (screenHeight - 26)
            }

            // 1. 入力モードインジケーターの描画（[あ] / [A]）
            var indicatorWidth = 0
            if (MeltypeConfig.showModeIndicator) {
                val mode = session.currentInputMode
                val badgeText = mode.badge
                indicatorWidth = font.width(badgeText) + 6
                val badgeHeight = 11
                val badgeY = baseY

                // ハイブリッド自動判別モード時は金色アクセント、直接入力時は控えめなグレー
                val badgeTextColor = if (mode == InputMode.HYBRID) ACCENT_COLOR else DIM_COLOR

                extractor.fill(baseX, badgeY - 2, baseX + indicatorWidth, badgeY + badgeHeight, BG_COLOR)
                extractor.textRenderer().accept(baseX + 3, badgeY, Component.literal(badgeText).withColor(badgeTextColor))
            }

            // 変換中（isComposing）でなければ以降の描画は不要
            if (!session.isComposing) return

            // プレビューと候補の開始X座標（インジケーターの右側に配置）
            val contentX = baseX + indicatorWidth + 2

            // 2. 変換候補リストの描画
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
                    val boxY = baseY - boxHeight - 2

                    // 画面右端へのはみ出し防止
                    val safeX = contentX.coerceIn(2, maxOf(2, screenWidth - totalWidth - 2))

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

            // 3. 入力中ひらがなプレビュー（下線付き）の描画
            val preview = session.previewKana.ifEmpty { session.rawBuffer.toString() }
            if (preview.isNotEmpty()) {
                val previewText = "変換中: $preview"
                val textWidth = font.width(previewText)
                val previewY = if (session.hasCandidates) baseY - 30 else baseY
                val safeX = contentX.coerceIn(2, maxOf(2, screenWidth - textWidth - 8))

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
}
