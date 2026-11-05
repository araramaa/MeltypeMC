package net.oyasai.meltype

import net.oyasai.meltype.engine.HalfWidthKana
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/** 半角カタカナへの変換（F8 キー）のテスト */
class HalfWidthKanaTest : MeltypeTestBase() {

    @Test
    fun testBasicKatakana() {
        assertEquals("ｱｲｳｴｵ", HalfWidthKana.fromKatakana("アイウエオ"))
        assertEquals("ｺﾝﾆﾁﾊ", HalfWidthKana.fromKatakana("コンニチハ"))
        assertEquals("ﾜｦﾝ", HalfWidthKana.fromKatakana("ワヲン"))
    }

    @Test
    fun testVoicedAndSemiVoiced() {
        // 濁点・半濁点は、2文字に分かれる
        assertEquals("ｶﾞｯｺｳ", HalfWidthKana.fromKatakana("ガッコウ"))
        assertEquals("ﾊﾟﾝ", HalfWidthKana.fromKatakana("パン"))
        assertEquals("ｳﾞｧｲｵﾘﾝ", HalfWidthKana.fromKatakana("ヴァイオリン"))
        assertEquals("ﾎﾞｰﾙﾍﾟﾝ", HalfWidthKana.fromKatakana("ボールペン"))
    }

    @Test
    fun testSmallLettersAndPunctuation() {
        assertEquals("ｷｮｳ", HalfWidthKana.fromKatakana("キョウ"))
        assertEquals("ｰ", HalfWidthKana.fromKatakana("ー"))
        assertEquals("､｡｢｣･", HalfWidthKana.fromKatakana("、。「」・"))
    }

    @Test
    fun testOtherCharactersPassThrough() {
        assertEquals("diamond ｱ 123 漢字", HalfWidthKana.fromKatakana("diamond ア 123 漢字"))
        assertEquals("", HalfWidthKana.fromKatakana(""))
    }

    /** F8 で、入力中の読みが、半角カタカナの候補になる（F7 の全角カタカナと並ぶ） */
    @Test
    fun testF8MakesHalfWidthKatakanaCandidate() {
        val committed = mutableListOf<String>()
        val session = newHybridSession(EchoConverter())
        for (c in "kyouha") session.onCharTyped(c, "", { committed.add(it) })
        session.onKeyPressed(GLFW.GLFW_KEY_F8, 0, 0, { committed.add(it) })
        assertEquals("ｷｮｳﾊ", session.candidates.first(), "候補: ${session.candidates}")

        // F7 は、これまでどおり、全角カタカナ
        session.onKeyPressed(GLFW.GLFW_KEY_F7, 0, 0, { committed.add(it) })
        assertEquals("キョウハ", session.candidates.first(), "候補: ${session.candidates}")
    }
}
