package net.oyasai.meltype

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/** 数字キー（1〜9）で候補を選んだとき、その数字が、文字として入力されないことのテスト */
class CandidateNumberKeyTest : MeltypeTestBase() {

    private fun typeAndConvert(committed: MutableList<String>): net.oyasai.meltype.engine.MeltypeSession {
        val session = newHybridSession(EchoConverter())
        val insert: (String) -> Unit = { committed.add(it) }
        "kyou".forEach { session.onCharTyped(it, "", insert) }
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insert) // 変換して、候補を出す
        return session
    }

    @Test
    fun digitCharAfterSelectingCandidateIsDiscardedOnce() {
        val committed = mutableListOf<String>()
        val insert: (String) -> Unit = { committed.add(it) }
        val session = typeAndConvert(committed)

        assertTrue(session.onKeyPressed(GLFW.GLFW_KEY_2, 0, 0, insert)) // 2番目の候補を選んで確定
        assertEquals(1, committed.size)

        // 続けて文字としても「2」が届く。MOD が消費して、チャット欄に入れさせない
        assertTrue(session.onCharTyped('2', "", insert))
        assertEquals(1, committed.size)

        // その1回だけ。次の「2」は、普通の入力（バニラに任せる）
        assertFalse(session.onCharTyped('2', "", insert))
    }

    @Test
    fun skipIsClearedWhenAnotherKeyIsPressedFirst() {
        val committed = mutableListOf<String>()
        val insert: (String) -> Unit = { committed.add(it) }
        val session = typeAndConvert(committed)

        session.onKeyPressed(GLFW.GLFW_KEY_1, 0, 0, insert)
        // 数字の文字が届かないまま、別のキーが押されたら、記憶は消える
        session.onKeyPressed(GLFW.GLFW_KEY_A, 0, 0, insert)
        assertFalse(session.onCharTyped('1', "", insert))
    }

    @Test
    fun onlyTheSelectedDigitIsDiscarded() {
        val committed = mutableListOf<String>()
        val insert: (String) -> Unit = { committed.add(it) }
        val session = typeAndConvert(committed)

        session.onKeyPressed(GLFW.GLFW_KEY_3, 0, 0, insert)
        // 違う数字が届いたら、捨てない
        assertFalse(session.onCharTyped('5', "", insert))
    }
}
