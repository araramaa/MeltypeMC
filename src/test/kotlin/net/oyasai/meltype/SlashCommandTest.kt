package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** スラッシュ（/）によるコマンドモードのテスト */
class SlashCommandTest : MeltypeTestBase() {

    @Test
    fun testSlashCommandGateBypass() {
        // 1. チャット欄が空で '/' を入力した場合 -> バイパス
        assertTrue(slashGate.shouldBypassIme("", '/'))
        assertTrue(slashGate.isCommandMode)

        // 2. 先頭が '/' の状態でコマンドを入力中 -> バイパス継続
        assertTrue(slashGate.shouldBypassIme("/g", 'a'))
        assertTrue(slashGate.isCommandMode)

        // 3. 通常チャット（'/' で始まらない） -> バイパスしない（IME有効）
        slashGate.reset()
        assertFalse(slashGate.shouldBypassIme("", 'k'))
        assertFalse(slashGate.isCommandMode)
    }

    @Test
    fun testSlashCommandArgsJapaneseAllowed() {
        // /say の場合は2番目のトークンからメッセージ（日本語許可）
        assertFalse(slashGate.shouldBypassIme("/say ", 'k'), "/say 以降はIMEがバイパスされないこと")
        assertFalse(slashGate.shouldBypassIme("/say test ", 'k'), "/say の後続入力もIMEがバイパスされないこと")

        // /me の場合も2番目のトークンからメッセージ
        assertFalse(slashGate.shouldBypassIme("/me ", 'k'), "/me 以降はIMEがバイパスされないこと")

        // /tell の場合は3番目のトークンからメッセージ
        assertTrue(slashGate.shouldBypassIme("/tell ", 'p'), "/tell 直後のプレイヤー名はバイパスされること")
        assertFalse(slashGate.shouldBypassIme("/tell steve ", 'k'), "/tell <player> 以降はIMEがバイパスされないこと")
    }
}
