package net.oyasai.meltype.engine

import org.lwjgl.glfw.GLFW

/**
 * F6〜F10 で、入力中の文字を決まった形（ひらがな・カタカナなど）に直す部品。状態を持たない。
 */
class FunctionKeyFormatter(private val romajiDetector: RomajiDetector) {

    /** F6〜F10 のキーか */
    fun isFormatKey(keyCode: Int): Boolean = keyCode in GLFW.GLFW_KEY_F6..GLFW.GLFW_KEY_F10

    /**
     * 入力した文字（[raw]）を、キーに合わせた形にする。
     * F6=ひらがな / F7=カタカナ / F8=半角カタカナ / F9=全角英数 / F10=入力した文字そのまま
     */
    fun format(keyCode: Int, raw: String): String {
        val kana = romajiDetector.toKanaFinal(raw)
        return when (keyCode) {
            GLFW.GLFW_KEY_F6 -> kana
            GLFW.GLFW_KEY_F7 -> romajiDetector.toKatakana(kana)
            GLFW.GLFW_KEY_F8 -> HalfWidthKana.fromKatakana(romajiDetector.toKatakana(kana))
            GLFW.GLFW_KEY_F9 -> romajiDetector.toZenkakuAlpha(raw)
            else -> raw
        }
    }
}
