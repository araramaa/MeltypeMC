package net.oyasai.meltype

import net.oyasai.meltype.engine.FunctionKeyFormatter
import net.oyasai.meltype.engine.InputCharacters
import net.oyasai.meltype.engine.KeyClassifier
import net.oyasai.meltype.engine.RomajiDetector
import net.oyasai.meltype.gui.ToggleKeyLabels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/** キーと文字を見分ける部品、F6〜F10 の形式変換、切り替えキーの名前の文字（状態を持たない部品）のテスト */
class InputPartsTest {

    @Test
    fun hankakuZenkakuIsRecognizedButF12IsNot() {
        assertTrue(KeyClassifier.isHankakuZenkaku(-1, KeyClassifier.SCAN_HANKAKU_ZENKAKU))
        assertTrue(KeyClassifier.isHankakuZenkaku(GLFW.GLFW_KEY_GRAVE_ACCENT, -1))
        assertTrue(KeyClassifier.isHankakuZenkaku(GLFW.GLFW_KEY_WORLD_1, -1))
        assertFalse(KeyClassifier.isHankakuZenkaku(GLFW.GLFW_KEY_F12, -1)) // F12 は設定画面を開くキー
    }

    @Test
    fun henkanIsRecognizedByScancode() {
        assertTrue(KeyClassifier.isHenkan(KeyClassifier.SCAN_HENKAN))
        assertFalse(KeyClassifier.isHenkan(-1))
    }

    @Test
    fun modifierChecks() {
        assertTrue(KeyClassifier.hasShift(GLFW.GLFW_MOD_SHIFT))
        assertFalse(KeyClassifier.hasShift(GLFW.GLFW_MOD_CONTROL))
        assertTrue(KeyClassifier.hasCtrl(GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SHIFT))
        assertTrue(KeyClassifier.hasNoModifiers(0))
        assertFalse(KeyClassifier.hasNoModifiers(GLFW.GLFW_MOD_ALT))
    }

    @Test
    fun characterKinds() {
        assertTrue(InputCharacters.isBackquote('`'))
        assertTrue(InputCharacters.isBackquote('｀'))
        assertFalse(InputCharacters.isBackquote('~')) // ~ は記号として有効
        assertTrue(InputCharacters.isRomajiInputChar('a'))
        assertTrue(InputCharacters.isRomajiInputChar('-'))
        assertFalse(InputCharacters.isRomajiInputChar('1'))
        assertTrue(InputCharacters.isTilde('〜'))
        assertTrue(InputCharacters.isJapanese('あ'))
        assertTrue(InputCharacters.isJapanese('漢'))
        assertFalse(InputCharacters.isJapanese('a'))
    }

    @Test
    fun fullWidthPunctuation() {
        assertEquals("、", InputCharacters.toFullWidthPunctuation(','))
        assertEquals("。", InputCharacters.toFullWidthPunctuation('.'))
        assertEquals("～", InputCharacters.toFullWidthPunctuation('~'))
        assertEquals(null, InputCharacters.toFullWidthPunctuation('a'))
    }

    @Test
    fun functionKeysFormatTheTypedText() {
        val formatter = FunctionKeyFormatter(RomajiDetector())
        assertTrue(formatter.isFormatKey(GLFW.GLFW_KEY_F6))
        assertTrue(formatter.isFormatKey(GLFW.GLFW_KEY_F10))
        assertFalse(formatter.isFormatKey(GLFW.GLFW_KEY_F5))
        assertFalse(formatter.isFormatKey(GLFW.GLFW_KEY_F11))

        assertEquals("がっこう", formatter.format(GLFW.GLFW_KEY_F6, "gakkou"))
        assertEquals("ガッコウ", formatter.format(GLFW.GLFW_KEY_F7, "gakkou"))
        assertEquals("ｶﾞｯｺｳ", formatter.format(GLFW.GLFW_KEY_F8, "gakkou"))
        assertEquals("ｇａｋｋｏｕ", formatter.format(GLFW.GLFW_KEY_F9, "gakkou"))
        assertEquals("gakkou", formatter.format(GLFW.GLFW_KEY_F10, "gakkou"))
    }

    @Test
    fun toggleKeyLabels() {
        assertEquals("Ctrl+Alt+", ToggleKeyLabels.modifierPrefix(GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT))
        assertEquals("F8", ToggleKeyLabels.keyName(GLFW.GLFW_KEY_F8))
        assertEquals("K", ToggleKeyLabels.keyName(GLFW.GLFW_KEY_K))
        assertEquals("文字キーは Ctrl か Alt と一緒に", ToggleKeyLabels.rejectReason(GLFW.GLFW_KEY_A))
        assertEquals("登録できないキーです。別のキーを", ToggleKeyLabels.rejectReason(GLFW.GLFW_KEY_F12))
        assertEquals("登録できないキーです。別のキーを", ToggleKeyLabels.rejectReason(-1))
    }
}
