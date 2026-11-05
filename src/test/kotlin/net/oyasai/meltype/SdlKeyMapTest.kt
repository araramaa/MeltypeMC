package net.oyasai.meltype

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/** Minecraft 26.3 以降のキー番号（USB 方式）を、26.2 までの番号（GLFW 方式）に置き換える対応表のテスト */
class SdlKeyMapTest {

    @Test
    fun testLettersAndDigits() {
        assertEquals(GLFW.GLFW_KEY_A, SdlKeyMap.toGlfwKey(4))
        assertEquals(GLFW.GLFW_KEY_K, SdlKeyMap.toGlfwKey(14))   // ログで確認した「k」
        assertEquals(GLFW.GLFW_KEY_N, SdlKeyMap.toGlfwKey(17))   // 「n」
        assertEquals(GLFW.GLFW_KEY_O, SdlKeyMap.toGlfwKey(18))   // 「o」
        assertEquals(GLFW.GLFW_KEY_Z, SdlKeyMap.toGlfwKey(29))
        assertEquals(GLFW.GLFW_KEY_1, SdlKeyMap.toGlfwKey(30))
        assertEquals(GLFW.GLFW_KEY_9, SdlKeyMap.toGlfwKey(38))
        assertEquals(GLFW.GLFW_KEY_0, SdlKeyMap.toGlfwKey(39))
    }

    @Test
    fun testEditingKeys() {
        assertEquals(GLFW.GLFW_KEY_ENTER, SdlKeyMap.toGlfwKey(40))
        assertEquals(GLFW.GLFW_KEY_ESCAPE, SdlKeyMap.toGlfwKey(41))
        assertEquals(GLFW.GLFW_KEY_BACKSPACE, SdlKeyMap.toGlfwKey(42))
        assertEquals(GLFW.GLFW_KEY_TAB, SdlKeyMap.toGlfwKey(43))
        assertEquals(GLFW.GLFW_KEY_SPACE, SdlKeyMap.toGlfwKey(44))
        assertEquals(GLFW.GLFW_KEY_GRAVE_ACCENT, SdlKeyMap.toGlfwKey(53))
        assertEquals(GLFW.GLFW_KEY_RIGHT, SdlKeyMap.toGlfwKey(79))
        assertEquals(GLFW.GLFW_KEY_LEFT, SdlKeyMap.toGlfwKey(80))
        assertEquals(GLFW.GLFW_KEY_DOWN, SdlKeyMap.toGlfwKey(81))
        assertEquals(GLFW.GLFW_KEY_UP, SdlKeyMap.toGlfwKey(82))
    }

    @Test
    fun testFunctionKeysAndKeypad() {
        assertEquals(GLFW.GLFW_KEY_F1, SdlKeyMap.toGlfwKey(58))
        assertEquals(GLFW.GLFW_KEY_F6, SdlKeyMap.toGlfwKey(63))
        assertEquals(GLFW.GLFW_KEY_F7, SdlKeyMap.toGlfwKey(64))
        assertEquals(GLFW.GLFW_KEY_F9, SdlKeyMap.toGlfwKey(66))
        assertEquals(GLFW.GLFW_KEY_F10, SdlKeyMap.toGlfwKey(67))
        assertEquals(GLFW.GLFW_KEY_F12, SdlKeyMap.toGlfwKey(69))
        assertEquals(GLFW.GLFW_KEY_KP_ENTER, SdlKeyMap.toGlfwKey(88))
        assertEquals(GLFW.GLFW_KEY_KP_1, SdlKeyMap.toGlfwKey(89))
        assertEquals(GLFW.GLFW_KEY_KP_9, SdlKeyMap.toGlfwKey(97))
        assertEquals(GLFW.GLFW_KEY_KP_0, SdlKeyMap.toGlfwKey(98))
    }

    @Test
    fun testModifierKeysAndUnknown() {
        assertEquals(GLFW.GLFW_KEY_LEFT_CONTROL, SdlKeyMap.toGlfwKey(224))
        assertEquals(GLFW.GLFW_KEY_LEFT_SHIFT, SdlKeyMap.toGlfwKey(225))
        assertEquals(GLFW.GLFW_KEY_RIGHT_SHIFT, SdlKeyMap.toGlfwKey(229))
        assertEquals(SdlKeyMap.UNKNOWN, SdlKeyMap.toGlfwKey(0))      // ログに出ていた「key=0」
        assertEquals(SdlKeyMap.UNKNOWN, SdlKeyMap.toGlfwKey(138))    // 変換キーは、キー番号としては対応表に入れない
    }

    @Test
    fun testJapaneseSpecialKeysBecomeLegacyScancodes() {
        assertEquals(41, SdlKeyMap.toLegacyScancode(53))     // 半角/全角
        assertEquals(121, SdlKeyMap.toLegacyScancode(138))   // 変換
        assertEquals(SdlKeyMap.UNKNOWN, SdlKeyMap.toLegacyScancode(139))   // 無変換は使わない
        assertEquals(SdlKeyMap.UNKNOWN, SdlKeyMap.toLegacyScancode(44))
    }

    @Test
    fun testModifiersAndMouseButtons() {
        assertEquals(GLFW.GLFW_MOD_SHIFT, SdlKeyMap.toGlfwModifiers(3))
        assertEquals(GLFW.GLFW_MOD_SHIFT, SdlKeyMap.toGlfwModifiers(1))        // 左 Shift だけ
        assertEquals(GLFW.GLFW_MOD_CONTROL, SdlKeyMap.toGlfwModifiers(192))
        assertEquals(GLFW.GLFW_MOD_ALT, SdlKeyMap.toGlfwModifiers(768))
        assertEquals(GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT, SdlKeyMap.toGlfwModifiers(192 or 1))
        assertEquals(0, SdlKeyMap.toGlfwModifiers(0))

        assertEquals(0, SdlKeyMap.toGlfwMouseButton(1))   // 左
        assertEquals(1, SdlKeyMap.toGlfwMouseButton(3))   // 右
        assertEquals(2, SdlKeyMap.toGlfwMouseButton(2))   // 中
    }
}
