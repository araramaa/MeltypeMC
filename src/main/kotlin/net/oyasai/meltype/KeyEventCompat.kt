package net.oyasai.meltype

import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Minecraft の版による、キー入力イベントの違いを吸収する部品。
 *
 * - 26.2 まで: [KeyEvent] に `scancode()` がある。キーの番号は GLFW 方式。
 * - 26.3 から: `scancode()` がなくなり、`keycode()` がある。キーの番号は USB（HID）方式に変わった
 *   （修飾キーの印、マウスのボタンの番号も変わった）。→ [SdlKeyMap] で、26.2 までの番号に置き換える。
 *
 * Meltype の中身は、26.2 までの番号（GLFW 方式）で書かれているので、入口でこの部品を通す。
 * これで、1つの jar が、26.2 でも 26.3 でも動く。
 */
object KeyEventCompat {

    /** `scancode()`（26.2 まで）。26.3 以降は null */
    private val scancodeMethod = runCatching { KeyEvent::class.java.getMethod("scancode") }.getOrNull()

    /** `keycode()`（26.3 から）。26.2 までは null */
    private val keycodeMethod = runCatching { KeyEvent::class.java.getMethod("keycode") }.getOrNull()

    /** 26.3 以降の方式（USB 方式の番号）か。`scancode()` がなく、`keycode()` がある版 */
    val isNewKeyScheme: Boolean = scancodeMethod == null && keycodeMethod != null

    /** キーの番号（GLFW 方式）。26.3 以降は、置き換えた番号。置き換えられないキーは -1 */
    fun glfwKey(event: KeyEvent): Int =
        if (isNewKeyScheme) SdlKeyMap.toGlfwKey(event.key()) else event.key()

    /** 修飾キーの印（GLFW 方式） */
    fun glfwModifiers(event: KeyEvent): Int =
        if (isNewKeyScheme) SdlKeyMap.toGlfwModifiers(event.modifiers()) else event.modifiers()

    /**
     * 半角/全角・変換を見分けるための、スキャンコード。
     * 26.2 までは `scancode()`、26.3 以降は、USB 方式の番号から置き換えた値。分からなければ -1
     */
    fun scancode(event: KeyEvent): Int =
        if (isNewKeyScheme) SdlKeyMap.toLegacyScancode(event.key()) else invokeInt(scancodeMethod, event)

    /** `keycode()` の値（26.3 以降）。調査用。ない版では -1 */
    fun keycode(event: KeyEvent): Int = invokeInt(keycodeMethod, event)

    /** マウスのボタンの番号（GLFW 方式: 左=0、右=1） */
    fun mouseButton(event: MouseButtonEvent): Int =
        if (isNewKeyScheme) SdlKeyMap.toGlfwMouseButton(event.button()) else event.button()

    private fun invokeInt(method: java.lang.reflect.Method?, event: KeyEvent): Int =
        try {
            (method?.invoke(event) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
}
