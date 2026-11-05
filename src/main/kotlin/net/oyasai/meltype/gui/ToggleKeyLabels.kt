package net.oyasai.meltype.gui

import org.lwjgl.glfw.GLFW

/**
 * 切り替えキーの登録画面で、キーの名前や、登録できなかった理由を文字にする部品。状態を持たない。
 */
object ToggleKeyLabels {

    /** 修飾キーの印を、「Ctrl+」「Alt+」「Shift+」の形の文字にする */
    fun modifierPrefix(modifiers: Int): String {
        var prefix = ""
        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) prefix += "Ctrl+"
        if ((modifiers and GLFW.GLFW_MOD_ALT) != 0) prefix += "Alt+"
        if ((modifiers and GLFW.GLFW_MOD_SHIFT) != 0) prefix += "Shift+"
        return prefix
    }

    /** キーの名前（F1〜F25、英字、数字はそのまま。ほかは GLFW の名前、なければ「キー番号」） */
    fun keyName(keyCode: Int): String {
        if (keyCode in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) return "F${keyCode - GLFW.GLFW_KEY_F1 + 1}"
        // 英字と数字は、GLFW を使わず（26.3 以降は使えないことがある）、そのまま文字にする
        if (keyCode in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z || keyCode in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9) {
            return keyCode.toChar().toString()
        }
        val named = try { GLFW.glfwGetKeyName(keyCode, 0) } catch (_: Throwable) { null }
        return named?.uppercase() ?: "キー$keyCode"
    }

    /** 登録できなかったキーの理由（ボタンに表示する短い説明） */
    fun rejectReason(keyCode: Int): String = when {
        keyCode in GLFW.GLFW_KEY_SPACE..GLFW.GLFW_KEY_GRAVE_ACCENT -> "文字キーは Ctrl か Alt と一緒に"
        else -> "登録できないキーです。別のキーを"
    }
}
