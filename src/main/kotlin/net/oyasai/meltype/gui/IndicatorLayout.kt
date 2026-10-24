package net.oyasai.meltype.gui

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.oyasai.meltype.config.MeltypeConfig

/** モード切替アイコン（あ/A）の位置と大きさ */
data class IndicatorBounds(val x: Int, val y: Int, val width: Int, val height: Int) {

    /** 点 (px, py) がアイコンの上にあるか（[margin] だけ周りを広げて判定） */
    fun contains(px: Double, py: Double, margin: Int = 0): Boolean =
        px >= x - margin && px <= x + width + margin &&
            py >= y - margin && py <= y + height + margin
}

/**
 * モード切替アイコン（あ/A）を、画面ごとにどこへ置くかを決めるクラス。
 * 描画（[CompositionRenderer]）とクリック判定（[isClicked]）の両方が、ここで決めた位置を使う。
 */
object IndicatorLayout {

    const val BUTTON_WIDTH = 26
    const val BUTTON_HEIGHT = 12

    /** クリックしやすいように、判定だけ周りを広げる幅 */
    private const val CLICK_MARGIN = 2

    /** 画面ごとのアイコンの位置 */
    fun boundsFor(screen: Screen): IndicatorBounds {
        val screenWidth = screen.width
        val screenHeight = screen.height
        val className = screen.javaClass.name

        return when {
            // チャット画面: 入力欄の先頭（左端）
            screen is ChatScreen || className.contains("Chat") -> chatBounds(screenHeight)

            // 金床画面: アイテム名の入力欄の左
            screen is AnvilScreen || className.contains("Anvil") ->
                IndicatorBounds((screenWidth - 176) / 2 + 58, (screenHeight - 166) / 2 + 38, BUTTON_WIDTH, BUTTON_HEIGHT)

            // 看板編集画面: 「完了」ボタンの左隣（ボタンが見つからない場合はバニラの座標で代用）
            screen is AbstractSignEditScreen || className.contains("Sign") ->
                leftOfBottomButton(screen)
                    ?: IndicatorBounds(screenWidth / 2 - 100 - BUTTON_WIDTH - 4, screenHeight / 4 + 124, BUTTON_WIDTH, BUTTON_HEIGHT)

            // 本編集画面: 「署名」ボタンの左隣（ボタンが見つからない場合はバニラの座標で代用）
            screen is BookEditScreen || className.contains("Book") ->
                leftOfBottomButton(screen)
                    ?: IndicatorBounds(screenWidth / 2 - 100 - BUTTON_WIDTH - 4, 200, BUTTON_WIDTH, BUTTON_HEIGHT)

            // そのほかの入力欄を持つ画面は、チャットと同じ位置
            else -> chatBounds(screenHeight)
        }
    }

    /** マウスでクリックした位置がアイコンの上か（アイコンを表示しない設定なら常に false） */
    fun isClicked(screen: Screen, mouseX: Double, mouseY: Double): Boolean {
        if (!MeltypeConfig.showModeIndicator) return false
        return try {
            boundsFor(screen).contains(mouseX, mouseY, CLICK_MARGIN)
        } catch (_: Throwable) {
            false
        }
    }

    private fun chatBounds(screenHeight: Int) = IndicatorBounds(2, screenHeight - 14, BUTTON_WIDTH, BUTTON_HEIGHT)

    /** 画面最下段のボタン列のうち最も左のボタン（看板の「完了」/ 本の「署名」）の左隣 */
    private fun leftOfBottomButton(screen: Screen): IndicatorBounds? {
        return try {
            val buttons = screen.children().filterIsInstance<AbstractWidget>().filter { it.visible }
            val bottomY = buttons.maxOfOrNull { it.y } ?: return null
            val target = buttons.filter { it.y == bottomY }.minByOrNull { it.x } ?: return null
            IndicatorBounds(
                maxOf(2, target.x - BUTTON_WIDTH - 4),
                target.y + (target.height - BUTTON_HEIGHT) / 2,
                BUTTON_WIDTH,
                BUTTON_HEIGHT
            )
        } catch (_: Throwable) {
            null
        }
    }
}
