package net.oyasai.meltype.gui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
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
     * 指定した座標 (baseX, baseY) を基準にプレビューと候補を描画
     */
    fun renderAt(context: DrawContext, session: MeltypeSession, baseX: Int, baseY: Int) {
        if (!session.isComposing) return

        try {
            val client = MinecraftClient.getInstance() ?: return
            val textRenderer = client.textRenderer ?: return
            val screenWidth = client.window?.scaledWidth ?: 400

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
                        totalWidth += textRenderer.getWidth(text) + 4
                    }
                    val boxHeight = 14
                    val boxY = baseY - boxHeight - 2

                    // 画面右端へのはみ出し防止
                    val safeX = baseX.coerceIn(2, maxOf(2, screenWidth - totalWidth - 2))

                    // 背景ボックス
                    context.fill(safeX, boxY, safeX + totalWidth, boxY + boxHeight, BG_COLOR)

                    // 各候補の描画
                    var curX = safeX + 4
                    for ((idx, text) in candidateTexts.withIndex()) {
                        val itemWidth = textRenderer.getWidth(text)
                        val isSelected = idx == session.selectedCandidateIndex

                        if (isSelected) {
                            context.fill(curX - 2, boxY + 1, curX + itemWidth + 2, boxY + boxHeight - 1, HIGHLIGHT_BG)
                        }

                        val color = if (isSelected) ACCENT_COLOR else TEXT_COLOR
                        context.drawText(textRenderer, text, curX, boxY + 3, color, true)
                        curX += itemWidth + 4
                    }
                }
            }

            // 2. 入力中ひらがなプレビュー（下線付き）の描画
            val preview = session.previewKana.ifEmpty { session.rawBuffer.toString() }
            if (preview.isNotEmpty()) {
                val previewText = "変換中: $preview"
                val textWidth = textRenderer.getWidth(previewText)
                val previewY = if (session.hasCandidates) baseY - 30 else baseY
                val safeX = baseX.coerceIn(2, maxOf(2, screenWidth - textWidth - 8))

                // 背景
                context.fill(safeX, previewY - 2, safeX + textWidth + 8, previewY + 11, BG_COLOR)
                // テキスト
                context.drawText(textRenderer, previewText, safeX + 4, previewY, ACCENT_COLOR, true)
                // 下線
                context.fill(safeX + 4, previewY + 10, safeX + 4 + textWidth, previewY + 11, ACCENT_COLOR)
            }
        } catch (_: Throwable) {
            // 描画エラー時もゲームを落とさない
        }
    }

    /** チャット画面用の描画（画面下部） */
    fun renderChat(context: DrawContext, session: MeltypeSession, screenWidth: Int, screenHeight: Int) {
        renderAt(context, session, 4, screenHeight - 26)
    }

    /** 金床画面用の描画（金床GUI中央のテキスト入力欄上部） */
    fun renderAnvil(context: DrawContext, session: MeltypeSession, screenWidth: Int, screenHeight: Int, backgroundWidth: Int = 176, backgroundHeight: Int = 166) {
        val x = (screenWidth - backgroundWidth) / 2 + 58
        val y = (screenHeight - backgroundHeight) / 2 + 38
        renderAt(context, session, x, y)
    }

    /** 看板編集画面用の描画（画面中央下部） */
    fun renderSign(context: DrawContext, session: MeltypeSession, screenWidth: Int, screenHeight: Int) {
        val x = (screenWidth / 2) - 100
        val y = screenHeight - 50
        renderAt(context, session, x, y)
    }

    /** 本編集画面用の描画（本の下部） */
    fun renderBook(context: DrawContext, session: MeltypeSession, screenWidth: Int, screenHeight: Int) {
        val x = (screenWidth / 2) - 80
        val y = screenHeight - 35
        renderAt(context, session, x, y)
    }
}
