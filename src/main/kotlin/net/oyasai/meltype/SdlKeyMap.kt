package net.oyasai.meltype

/**
 * Minecraft 26.3 以降のキー入力の「番号」を、26.2 までの番号（GLFW 方式）に置き換える対応表。
 *
 * - 26.2 まで: キーの番号は GLFW 方式（例: Space = 32、Enter = 257、Esc = 256）。
 * - 26.3 から: キーの番号は USB キーボードの標準（HID）方式（例: Space = 44、Enter = 40、Esc = 41、A = 4）。
 *   修飾キー（Shift・Ctrl・Alt）の印と、マウスのボタンの番号も変わった。
 *
 * Meltype の中身は、GLFW 方式の番号で書かれているので、入口でここを通して、置き換える。
 * Minecraft の部品を使わない（数値だけの）処理にしてあるので、単体でテストできる。
 */
object SdlKeyMap {

    /** 置き換えられないキーのときの値（GLFW の「不明なキー」と同じ） */
    const val UNKNOWN = -1

    /** USB（HID）のキー番号 → GLFW のキー番号。対応表にないキーは [UNKNOWN] */
    fun toGlfwKey(hid: Int): Int = when (hid) {
        in 4..29 -> 65 + (hid - 4)          // A〜Z
        in 30..38 -> 49 + (hid - 30)        // 1〜9
        39 -> 48                            // 0
        40 -> 257                           // Enter
        41 -> 256                           // Esc
        42 -> 259                           // Backspace
        43 -> 258                           // Tab
        44 -> 32                            // Space
        45 -> 45                            // -
        46 -> 61                            // =
        47 -> 91                            // [
        48 -> 93                            // ]
        49 -> 92                            // \
        51 -> 59                            // ;
        52 -> 39                            // '
        53 -> 96                            // `（半角/全角キーも、このキー）
        54 -> 44                            // ,
        55 -> 46                            // .
        56 -> 47                            // /
        in 58..69 -> 290 + (hid - 58)       // F1〜F12
        in 104..115 -> 302 + (hid - 104)    // F13〜F24
        73 -> 260                           // Insert
        74 -> 268                           // Home
        75 -> 266                           // Page Up
        76 -> 261                           // Delete
        77 -> 269                           // End
        78 -> 267                           // Page Down
        79 -> 262                           // →
        80 -> 263                           // ←
        81 -> 264                           // ↓
        82 -> 265                           // ↑
        88 -> 335                           // テンキーの Enter
        in 89..97 -> 321 + (hid - 89)       // テンキーの 1〜9
        98 -> 320                           // テンキーの 0
        224 -> 341                          // 左 Ctrl
        225 -> 340                          // 左 Shift
        226 -> 342                          // 左 Alt
        227 -> 343                          // 左 Windows
        228 -> 345                          // 右 Ctrl
        229 -> 344                          // 右 Shift
        230 -> 346                          // 右 Alt
        231 -> 347                          // 右 Windows
        else -> UNKNOWN
    }

    /**
     * 日本語キーボードの特別なキーを、26.2 までのスキャンコード（Meltype が見分けに使っていた番号）に置き換える。
     * 半角/全角 = 41、変換 = 121。該当しなければ [UNKNOWN]。
     * （無変換キーは、Meltype では使わない）
     */
    fun toLegacyScancode(hid: Int): Int = when (hid) {
        53 -> 41      // 半角/全角（`のキー）
        138 -> 121    // 変換（International4）
        else -> UNKNOWN
    }

    /** 修飾キーの印: 26.3 の方式 → GLFW の方式（Shift=1、Ctrl=2、Alt=4、Windows=8、CapsLock=16、NumLock=32） */
    fun toGlfwModifiers(sdl: Int): Int {
        var result = 0
        if (sdl and 0x3 != 0) result = result or 1          // Shift（左・右）
        if (sdl and 0xC0 != 0) result = result or 2         // Ctrl（左・右）
        if (sdl and 0x300 != 0) result = result or 4        // Alt（左・右）
        if (sdl and 0xC00 != 0) result = result or 8        // Windows（左・右）
        if (sdl and 0x2000 != 0) result = result or 16      // CapsLock
        if (sdl and 0x1000 != 0) result = result or 32      // NumLock
        return result
    }

    /** マウスのボタン: 26.3 の方式（左=1、中=2、右=3、4=4 …）→ GLFW の方式（左=0、右=1、中=2、4=3 …） */
    fun toGlfwMouseButton(sdl: Int): Int = when (sdl) {
        1 -> 0
        2 -> 2
        3 -> 1
        in 4..8 -> sdl - 1
        else -> UNKNOWN
    }
}
