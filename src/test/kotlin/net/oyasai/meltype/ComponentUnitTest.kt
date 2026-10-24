package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 部品単体（変換器・候補の整理・文節・プレビュー）のテスト */
class ComponentUnitTest : MeltypeTestBase() {

    /**
     * Google CGI のレスポンスに「\"」「]」や \u エスケープを含む候補があっても、正しく読み取れることの検証
     */
    @Test
    fun testGoogleCgiResponseParsesEscapedCandidates() {
        val converter = net.oyasai.meltype.conversion.GoogleCgiConverter()

        // 候補に「"」を含む（JSON では \" と書かれる）
        val quoted = converter.parseGoogleCgiResponse("""[["かぎ",["\"鍵\"","鍵","かぎ"]]]""", "かぎ")
        assertEquals(listOf("\"鍵\"", "鍵", "かぎ"), quoted)

        // 候補に「]」を含む
        val bracket = converter.parseGoogleCgiResponse("""[["かっこ",["]","括弧"]]]""", "かっこ")
        assertEquals(listOf("]", "括弧", "かっこ"), bracket)

        // \u エスケープ（「 = 「）
        val unicode = converter.parseGoogleCgiResponse("""[["かぎかっこ",["「","鉤括弧"]]]""", "かぎかっこ")
        assertEquals(listOf("「", "鉤括弧", "かぎかっこ"), unicode)

        // 複数文節（従来どおり、各文節の第1候補をつなげた文が先頭）
        val sentence = converter.parseGoogleCgiResponse(
            """[["きょうは",["今日は","京は"]],["さがそう",["探そう","捜そう"]]]""", "きょうはさがそう"
        )
        assertEquals("今日は探そう", sentence[0])
        assertTrue(sentence.contains("京は捜そう"))
        assertTrue(sentence.contains("きょうはさがそう"))

        // 壊れたレスポンスは、元の読みをそのまま返す
        assertEquals(listOf("かぎ"), converter.parseGoogleCgiResponse("""[["かぎ",["鍵"""", "かぎ"))
    }

    /**
     * 「Google変換を使わない」設定のとき、外部通信せずローカル変換のみが使われることの検証
     */
    @Test
    fun testGoogleConverterIsSkippedWhenDisabled() {
        val old = MeltypeConfig.useGoogleCgi
        try {
            MeltypeConfig.useGoogleCgi = false
            val stub = object : net.oyasai.meltype.conversion.IConverter {
                override fun convertAsync(reading: String) =
                    java.util.concurrent.CompletableFuture.completedFuture(listOf("ローカル:$reading"))
            }
            val converter = net.oyasai.meltype.conversion.GoogleCgiConverter(stub)
            assertEquals(listOf("ローカル:きょう"), converter.convertAsync("きょう").get())
        } finally {
            MeltypeConfig.useGoogleCgi = old
        }
    }

    /**
     * 変換候補が多くても、打ったままのひらがなと生英字が候補の枠内に残ることの検証
     */
    @Test
    fun testFinalizerKeepsKanaAndRawWithinLimit() {
        val many = (1..20).map { "候補$it" }
        val result = CandidateFinalizer.finalize(many, "kyouha", "きょうは", 5)
        assertTrue(result.size <= 9)
        assertTrue(result.contains("きょうは"))
        assertTrue(result.contains("kyouha"))
    }

    @Test
    fun testClauseManagerUnit() {
        val manager = ClauseManager()
        assertFalse(manager.isPartial(10))
        assertEquals(0, manager.clauseLength)

        // shrink
        manager.shrink(10)
        assertEquals(9, manager.clauseLength)
        assertTrue(manager.isPartial(10))

        manager.shrink(10)
        assertEquals(8, manager.clauseLength)

        // split
        val (active, remainder) = manager.split("0123456789")
        assertEquals("01234567", active)
        assertEquals("89", remainder)

        // expand
        val fullyExpanded = manager.expand(10)
        assertFalse(fullyExpanded)
        assertEquals(9, manager.clauseLength)

        val restored = manager.expand(10)
        assertTrue(restored)
        assertEquals(0, manager.clauseLength)
        assertFalse(manager.isPartial(10))

        // reset
        manager.shrink(5)
        assertEquals(4, manager.clauseLength)
        manager.reset()
        assertEquals(0, manager.clauseLength)
    }

    @Test
    fun testPreviewFormatterUnit() {
        val compoundSplitter = CompoundWordSplitter(englishDetector, romajiDetector)
        val formatter = PreviewFormatter(romajiDetector, scoreEngine, compoundSplitter)

        // 空文字列
        assertEquals("", formatter.formatPreview(""))

        // 英語
        assertEquals("diamond", formatter.formatPreview("diamond"))

        // 日本語
        assertEquals("きょうは", formatter.formatPreview("kyouha"))

        // 複合語
        val preview = formatter.formatPreview("diamondwosagasou")
        assertTrue(preview.contains("diamond") && preview.contains("さがそう"))

        // 文節伸縮プレビュー
        val clausePreview = formatter.formatClausePreview("今日", "hadiamond")
        assertTrue(clausePreview.startsWith("[今日]"))
        assertTrue(clausePreview.contains("diamond"))
    }

    @Test
    fun testCandidateFinalizerUnit() {
        val input = listOf("候補1", "候補2", "候補3")
        val finalized = CandidateFinalizer.finalize(input, "rawInput", "kanaInput", maxCandidatesConfig = 5)

        assertTrue(finalized.contains("rawInput"))
        assertTrue(finalized.contains("kanaInput"))
        assertTrue(finalized.size <= 5)
    }
}
