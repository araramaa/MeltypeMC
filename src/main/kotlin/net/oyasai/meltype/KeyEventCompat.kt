package net.oyasai.meltype

import net.minecraft.client.input.KeyEvent

/**
 * Minecraft の版による、キー入力イベント（[KeyEvent]）の違いを吸収する部品。
 *
 * - 26.2 まで: `scancode()`（キーボード上の物理的な位置の番号）がある。
 * - 26.3 から: `scancode()` がなくなり、かわりに `keycode()` がある（スキャンコードではない）。
 *
 * 1つの jar が、どちらの版でも動くように、メソッドの有無を実行時に調べて呼ぶ。
 */
object KeyEventCompat {

    /** `scancode()`（26.2 まで）。26.3 以降は null */
    private val scancodeMethod = runCatching { KeyEvent::class.java.getMethod("scancode") }.getOrNull()

    /** `keycode()`（26.3 から）。26.2 までは null */
    private val keycodeMethod = runCatching { KeyEvent::class.java.getMethod("keycode") }.getOrNull()

    /** スキャンコードを返す。取れない版（26.3 以降）では -1 */
    fun scancode(event: KeyEvent): Int = invokeInt(scancodeMethod, event)

    /** `keycode()` を返す。ない版（26.2 まで）では -1 */
    fun keycode(event: KeyEvent): Int = invokeInt(keycodeMethod, event)

    private fun invokeInt(method: java.lang.reflect.Method?, event: KeyEvent): Int =
        try {
            (method?.invoke(event) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
}
