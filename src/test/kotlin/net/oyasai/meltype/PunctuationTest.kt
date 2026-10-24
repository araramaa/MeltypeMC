package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 句読点・「～」・空白などの記号が消えないことのテスト */
class PunctuationTest : MeltypeTestBase() {

    /**
     * Shift+F12 および波線記号（~ / ～）の入力テスト
     */
    @Test
    fun testShiftF12AndTildeHandling() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate,
            learningStore = newTempLearningStore()
        )

        // 1. DIRECTモード（非変換中）で Shift+F12 を押すと即座に「～」が挿入されること
        assertEquals(InputMode.DIRECT, session.currentInputMode)
        val handledShiftF12Direct = session.onKeyPressed(GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertTrue(handledShiftF12Direct)
        assertEquals(listOf("～"), committed)

        // 2. HYBRIDモードに切り替え
        session.toggleInputMode()
        assertEquals(InputMode.HYBRID, session.currentInputMode)
        committed.clear()

        // 3. HYBRIDモード（非変換中）で Shift+F12 を押すと「～」が挿入されること
        val handledShiftF12Hybrid = session.onKeyPressed(GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertTrue(handledShiftF12Hybrid)
        assertEquals(listOf("～"), committed)

        // 4. HYBRIDモードで文字入力中（"arigatou"）に Shift+F12 を押すと、プレビュー末尾に「～」が追加されること
        committed.clear()
        for (c in "arigatou") {
            session.onCharTyped(c, "", insertToChat)
        }
        assertEquals("ありがとう", session.previewKana)
        session.onKeyPressed(GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertEquals("ありがとう～", session.previewKana)

        // Enterで「ありがとう～」が確定されること
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(listOf("ありがとう～"), committed)

        // 5. HYBRIDモードで通常タイピングによる波線記号 ('~') の入力
        committed.clear()
        // 非変換中の '~' は直接全角「～」として挿入されること（モード切替にならず記号として出力）
        val handledTildeDirect = session.onCharTyped('~', "", insertToChat)
        assertTrue(handledTildeDirect)
        assertEquals(listOf("～"), committed)
        assertEquals(InputMode.HYBRID, session.currentInputMode) // モードが勝手に切り替わっていないこと

        // 変換中の '~' はバッファに全角「～」として蓄積されること
        committed.clear()
        for (c in "ikuyo") {
            session.onCharTyped(c, "", insertToChat)
        }
        session.onCharTyped('~', "", insertToChat)
        assertEquals("いくよ～", session.previewKana)
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(listOf("いくよ～"), committed)
    }

    /**
     * 語尾の「～」「、」「。」が、変換前・変換後のどちらに打っても消えないことの検証
     */
    @Test
    fun testTrailingPunctuationIsNeverLost() {
        fun newSession() = newHybridSession(EchoConverter())

        for (punct in listOf('~', '.', ',')) {
            val expectedMark = when (punct) { '~' -> "～"; '.' -> "。"; else -> "、" }

            // A. 変換前に記号を打つ: "ikuyo~" → Space → Enter
            val committedA = mutableListOf<String>()
            val a = newSession()
            for (c in "ikuyo") a.onCharTyped(c, "", { committedA.add(it) })
            a.onCharTyped(punct, "", { committedA.add(it) })
            a.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, { committedA.add(it) })
            a.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, { committedA.add(it) })
            assertTrue(committedA.joinToString("").contains(expectedMark), "変換前の $punct が消えた: $committedA")

            // B. 変換後に記号を打つ: "ikuyo" → Space → ~ → Enter
            val committedB = mutableListOf<String>()
            val b = newSession()
            for (c in "ikuyo") b.onCharTyped(c, "", { committedB.add(it) })
            b.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, { committedB.add(it) })
            b.onCharTyped(punct, "", { committedB.add(it) })
            b.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, { committedB.add(it) })
            assertTrue(committedB.joinToString("").contains(expectedMark), "変換後の $punct が消えた: $committedB")
        }
    }

    /**
     * 変換後（候補表示中）に Shift+F12 の「～」や Shift+Space の空白を足しても、確定時に消えないことの検証
     */
    @Test
    fun testShiftF12AndShiftSpaceAfterConversionAreNeverLost() {
        fun newSession() = newHybridSession(EchoConverter())

        // A. "ikuyo" → Space → Shift+F12 → Enter
        val committedA = mutableListOf<String>()
        val a = newSession()
        for (c in "ikuyo") a.onCharTyped(c, "", { committedA.add(it) })
        a.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, { committedA.add(it) })
        assertTrue(a.hasCandidates)
        assertTrue(a.onKeyPressed(GLFW.GLFW_KEY_F12, 0, GLFW.GLFW_MOD_SHIFT, { committedA.add(it) }))
        a.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, { committedA.add(it) })
        assertEquals("漢[いくよ]～", committedA.joinToString(""), "変換後の Shift+F12 の「～」が消えた: $committedA")

        // B. "ikuyo" → Space → Shift+Space → Enter
        val committedB = mutableListOf<String>()
        val b = newSession()
        for (c in "ikuyo") b.onCharTyped(c, "", { committedB.add(it) })
        b.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, { committedB.add(it) })
        assertTrue(b.hasCandidates)
        assertTrue(b.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, GLFW.GLFW_MOD_SHIFT, { committedB.add(it) }))
        b.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, { committedB.add(it) })
        assertEquals("漢[いくよ] ", committedB.joinToString(""), "変換後の Shift+Space の空白が消えた: $committedB")
    }
}
