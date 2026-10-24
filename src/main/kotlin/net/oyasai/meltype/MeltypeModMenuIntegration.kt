package net.oyasai.meltype

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.oyasai.meltype.gui.MeltypeConfigScreen

/**
 * Mod Menu（MODの一覧画面）の「設定」ボタンから、Meltype の設定画面を開けるようにする。
 * チャット欄のアイコンを非表示にしていても、ここから設定画面を開ける。
 * Mod Menu が入っていない環境では、このクラスは読み込まれない。
 */
class MeltypeModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> MeltypeConfigScreen(parent, MeltypeClient.session.learningStore) }
}
