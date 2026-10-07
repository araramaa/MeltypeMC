package net.oyasai.meltype.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.network.chat.Component
import net.oyasai.meltype.engine.MeltypeSession

/**
 * 画面上に入力中テキスト（下線プレビュー）および変換候補ウィンドウを描画するレンダラー
 * 画面外はみ出し防止および例外安全ガードを備えています。
 */
object CompositionRenderer {

    private const val BG_COLOR = 0xCC000000.toInt()      // 半透明黒
    private const val HIGHLIGHT_BG = 0x88336699.toInt()  // 選択中ハイライト青
    private const val TEXT_COLOR = 0xFFFFFFFF.toInt()    // 白文字
    private const val ACCENT_COLOR = 0xFFFFAA00.toInt()  // オレンジ/金色アクセント

    /**
     * 現在開いているScreenのフォアグラウンドにプレビューおよび候補ウィンドウを描画
     */
    fun render(screen: Screen, extractor: GuiGraphicsExtractor, session: MeltypeSession) {
        if (!session.isComposing) return

        try {
            val client = Minecraft.getInstance()
            val font = client.font
            val screenWidth = screen.width
            val screenHeight = screen.height

            // 画面種別ごとの基準座標を算出
            val (baseX, baseY) = when (screen) {
                is ChatScreen -> 4 to (screenHeight - 26)
                is AnvilScreen -> ((screenWidth - 176) / 2 + 58) to ((screenHeight - 166) / 2 + 38)
                is AbstractSignEditScreen -> ((screenWidth / 2) - 100) to (screenHeight - 50)
                is BookEditScreen -> ((screenWidth / 2) - 80) to (screenHeight - 35)
                else -> 4 to (screenHeight - 26)
            }

            // 1. 変換候補リストの描画
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
                    val safeX = baseX.coerceIn(2, maxOf(2, screenWidth - totalWidth - 2))

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

            // 2. 入力中ひらがなプレビュー（下線付き）の描画
            val preview = session.previewKana.ifEmpty { session.rawBuffer.toString() }
            if (preview.isNotEmpty()) {
                val previewText = "変換中: $preview"
                val textWidth = font.width(previewText)
                val previewY = if (session.hasCandidates) baseY - 30 else baseY
                val safeX = baseX.coerceIn(2, maxOf(2, screenWidth - textWidth - 8))

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
