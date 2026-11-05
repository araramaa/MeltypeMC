package net.oyasai.meltype.engine

import org.lwjgl.glfw.GLFW

/**
 * キー番号と修飾キーの印から、「どのキーか」「どの修飾キーが押されているか」を見分ける部品。
 * 状態を持たない。キー番号は、26.2 までの番号（GLFW 方式）に揃えた後のものを受け取る。
 */
object KeyClassifier {

    /** JIS 半角/全角キースキャンコード (41 / 0x29) */
    const val SCAN_HANKAKU_ZENKAKU = 41

    /** JIS 変換キースキャンコード (121) */
    const val SCAN_HENKAN = 121

    /**
     * 半角/全角キーか（スキャンコード 41 / バッククォートのキー / WORLD キー）。
     * F12 は、設定画面を開くキーなので、含めない。
     */
    fun isHankakuZenkaku(keyCode: Int, scanCode: Int): Boolean =
        scanCode == SCAN_HANKAKU_ZENKAKU ||
            keyCode == GLFW.GLFW_KEY_GRAVE_ACCENT ||
            keyCode == GLFW.GLFW_KEY_WORLD_1 ||
            keyCode == GLFW.GLFW_KEY_WORLD_2

    /** 変換キーか（スキャンコード 121） */
    fun isHenkan(scanCode: Int): Boolean = scanCode == SCAN_HENKAN

    fun hasShift(modifiers: Int): Boolean = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0

    fun hasCtrl(modifiers: Int): Boolean = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0

    /** Shift・Ctrl・Alt のどれも押されていないか */
    fun hasNoModifiers(modifiers: Int): Boolean =
        (modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT)) == 0
}
