package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 変換結果が遅れて届いたときに、二重入力や文字消失が起きないことのテスト */
class AsyncConversionTest : MeltypeTestBase() {

    /**
     * 確定済みの変換に対する遅れた結果が、次の入力の候補として混入しないことの検証
     * （混入すると、次の1文字で古い候補が再挿入され二重入力・文字消失になる）
     */
    @Test
    fun testStaleAsyncResultDoesNotLeakIntoNextInput() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }
        val converter = DelayedConverter()
        val session = newHybridSession(converter)

        // "kyouha" → Space（結果はまだ届かない）→ Enter で即時候補を確定
        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(listOf("きょうは"), committed)

        // 次の入力 "a" を打つ
        session.onCharTyped('a', "", insertToChat)
        assertTrue(session.isComposing)

        // ここで古い変換結果が届く
        converter.pending.forEach { it.complete(listOf("今日は", "京は")) }

        assertFalse(session.hasCandidates, "古い変換結果が次の入力の候補として表示されないこと")

        // 続けて "r" を打っても、古い候補「今日は」が再挿入されないこと
        session.onCharTyped('r', "", insertToChat)
        assertEquals(listOf("きょうは"), committed, "古い候補が再挿入されないこと")
        assertEquals("ar", session.rawBuffer.toString(), "入力中の文字が失われないこと")
    }

    /**
     * 変換を取り消した（Backspace）後に結果が届いても、候補が復活しないことの検証
     */
    @Test
    fun testStaleAsyncResultDoesNotReviveCandidatesAfterBackspace() {
        val converter = DelayedConverter()
        val session = newHybridSession(converter)

        for (c in "kyouha") session.onCharTyped(c, "") {}
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0) {}
        assertTrue(session.hasCandidates)

        // Backspace で変換を取り消す
        session.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0) {}
        assertFalse(session.hasCandidates)

        // 取り消し後に結果が届く
        converter.pending.forEach { it.complete(listOf("今日は", "京は")) }
        assertFalse(session.hasCandidates, "取り消した変換の結果で候補が復活しないこと")
    }

    /**
     * 正常系: 変換結果が遅れて届いたとき（入力に変化なし）は、候補が最終候補に差し替わることの検証
     */
    @Test
    fun testAsyncResultAppliesWhenInputUnchanged() {
        val converter = DelayedConverter()
        val session = newHybridSession(converter)

        for (c in "kyouha") session.onCharTyped(c, "") {}
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0) {}
        converter.pending.forEach { it.complete(listOf("今日は")) }

        assertTrue(session.candidates.contains("今日は"), "最新の変換結果は候補に反映されること")
    }

    /**
     * 文節伸縮（Shift+左）の遅れた結果も、取り消し後には反映されないことの検証
     */
    @Test
    fun testStaleClauseResultDoesNotReviveCandidatesAfterBackspace() {
        val converter = DelayedConverter()
        val session = newHybridSession(converter)

        for (c in "kyouhadiamond") session.onCharTyped(c, "") {}
        session.onKeyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_SHIFT) {}
        assertTrue(session.hasCandidates)
        session.onKeyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0) {}
        assertFalse(session.hasCandidates)

        converter.pending.forEach { it.complete(listOf("今日")) }
        assertFalse(session.hasCandidates, "取り消した文節変換の結果で候補が復活しないこと")
    }
}
