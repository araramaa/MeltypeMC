package net.oyasai.meltype

import net.oyasai.meltype.conversion.GoogleResponseParser
import net.oyasai.meltype.engine.RomajiDetector
import net.oyasai.meltype.engine.RomajiTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ローマ字の変換表と、Google のレスポンスの読み取り（状態を持たない部品）のテスト */
class RomajiTableTest {

    @Test
    fun tableLookup() {
        assertEquals("か", RomajiTable.kanaFor("ka"))
        assertEquals("うぃ", RomajiTable.kanaFor("wi"))
        assertEquals(null, RomajiTable.kanaFor("we")) // "we" は英単語と衝突するので登録しない
        assertTrue(RomajiTable.contains("tsu"))
        assertFalse(RomajiTable.contains("q"))
    }

    @Test
    fun partialInputIsRecognized() {
        assertTrue(RomajiTable.isPartial("k"))    // か行の途中
        assertTrue(RomajiTable.isPartial("ts"))   // tsu の途中
        assertTrue(RomajiTable.isPartial("n"))    // 次の文字で変わる
        assertTrue(RomajiTable.isPartial("tc"))   // tch の途中
        assertFalse(RomajiTable.isPartial("q"))
        assertFalse(RomajiTable.isPartial("ka"))  // 完成していて、途中ではない
    }

    @Test
    fun longestMatchAndSpecialRulesStillWork() {
        val detector = RomajiDetector()
        assertEquals("がっこう", detector.toKanaLenient("gakkou"))
        assertEquals("しゃしn", detector.toKanaLenient("syasin"))   // 入力途中の n は、そのまま残す
        assertEquals("しゃしん", detector.toKanaFinal("syasin"))    // 確定するときは「ん」にする
        assertEquals("まっち", detector.toKanaLenient("matchi"))
        assertEquals("ほん", detector.toKanaLenient("honn"))
        assertEquals("せんせい", detector.toKanaLenient("sensei"))
    }

    @Test
    fun googleResponseWithOneSegmentKeepsReading() {
        val list = GoogleResponseParser.parse("""[["かぎ",["鍵","key"]]]""", "かぎ")
        assertEquals(listOf("鍵", "key", "かぎ"), list)
    }

    @Test
    fun googleResponseUnreadableReturnsOriginal() {
        assertEquals(listOf("かぎ"), GoogleResponseParser.parse("not json", "かぎ"))
    }
}
