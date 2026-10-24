package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 英日判定・ローマ字解析・複合語の分割のテスト */
class DetectionTest : MeltypeTestBase() {

    @Test
    fun testRomajiAnalysis() {
        val analysis = romajiDetector.analyze("kyouha")
        assertTrue(analysis.isValid)
        assertEquals("きょうは", analysis.kana)
    }

    @Test
    fun testEnglishDetection() {
        assertTrue(englishDetector.isWord("diamond"))
        assertTrue(englishDetector.isWord("apple"))
        assertTrue(englishDetector.isWord("craft"))
        assertFalse(englishDetector.isWord("kyouha"))
    }

    @Test
    fun testScoreEngineJapanese() {
        val result = scoreEngine.evaluate("kyouha", isFinal = true)
        assertEquals(Verdict.JAPANESE, result.verdict)
    }

    @Test
    fun testScoreEngineEnglish() {
        // "diamond" は英単語完全一致のため、ローマ字判定で「ぢあもんd」にならず確実に英語になること！
        val result = scoreEngine.evaluate("diamond", isFinal = true)
        assertEquals(Verdict.ENGLISH, result.verdict)
    }

    @Test
    fun testEdgeCasesRobustness() {
        // 空文字
        val emptyResult = scoreEngine.evaluate("", isFinal = true)
        assertNotNull(emptyResult)

        // 記号や数字混じり
        val symbolAnalysis = romajiDetector.analyze("!@#$%^")
        assertFalse(symbolAnalysis.isValid)

        // 1文字判定
        val singleResult = scoreEngine.evaluate("a", isFinal = false)
        assertNotNull(singleResult)

        // 大文字混じり
        val upperAnalysis = romajiDetector.analyze("KyouHa")
        assertTrue(upperAnalysis.isValid)
        assertEquals("きょうは", upperAnalysis.kana)
    }

    /**
     * 「いま (ima)」をはじめとする短縮単語・一般的なひらがな単語が
     * 誤って英語（image等のprefix）判定されず、正常に日本語として変換されることの検証
     */
    @Test
    fun testImaAndShortRomajiEvaluation() {
        // 1. ima の評価
        val imaResult = scoreEngine.evaluate("ima", isFinal = true)
        assertEquals(Verdict.JAPANESE, imaResult.verdict, "ima は英単語ではないので日本語として判定されること")

        // 2. 他の短語（kore, sora, inu, neko, yama）
        for (word in listOf("kore", "sora", "inu", "neko", "yama", "asa", "haru")) {
            val res = scoreEngine.evaluate(word, isFinal = true)
            assertEquals(Verdict.JAPANESE, res.verdict, "$word は確定時に日本語判定されること")
        }

        // 3. 実際の英単語は英語判定を維持
        for (eng in listOf("diamond", "apple", "test", "help", "world", "craft")) {
            val res = scoreEngine.evaluate(eng, isFinal = true)
            assertEquals(Verdict.ENGLISH, res.verdict, "$eng は確定時に英語判定されること")
        }
    }

    /**
     * 助詞や短音節「は (ha)」「へ (he)」「と (to)」「の (no)」「め (me)」など、
     * 英語辞書にも存在する2文字単語が誤って英語判定されず、日本語として判定・変換されることの検証
     */
    @Test
    fun testJapaneseParticlesAndShortSyllablesEvaluation() {
        // ha, he, to, no, me, so, do, go, in, on は英語辞書に存在しうるが、日本語として判定されること
        for (particle in listOf("ha", "he", "to", "no", "me", "so", "do", "go", "in", "on")) {
            val res = scoreEngine.evaluate(particle, isFinal = true)
            assertEquals(Verdict.JAPANESE, res.verdict, "$particle は確定時に日本語判定されること")
        }
    }

    /**
     * toKanaLenient でローマ字にマッチしない子音や英字があっても、
     * 「も」のように文字が途中で破棄・消失せず、すべての文字が保持されることの検証
     */
    @Test
    fun testToKanaLenientDoesNotDropCharacters() {
        val kanaModrinth = romajiDetector.toKanaLenient("modrinth")
        // "も" だけにならず、"もdりんth" のように全文字が保持されること
        assertEquals("もdりんth", kanaModrinth)

        val kanaMinecraft = romajiDetector.toKanaLenient("minecraft")
        assertTrue(kanaMinecraft.contains("みね"))
        // 文字列長が極端に縮んで消失していないこと
        assertTrue(kanaMinecraft.length >= 6)
    }

    /**
     * scoreEngine.isEnglishInput による英単語・マイクラ用語・日本語日常語の判別検証
     */
    @Test
    fun testIsEnglishInputAccuracy() {
        // 英語・固有名詞・マイクラ用語
        assertTrue(scoreEngine.isEnglishInput("modrinth"))
        assertTrue(scoreEngine.isEnglishInput("Modrinth"))
        assertTrue(scoreEngine.isEnglishInput("minecraft"))
        assertTrue(scoreEngine.isEnglishInput("fabric"))
        assertTrue(scoreEngine.isEnglishInput("diamond"))
        assertTrue(scoreEngine.isEnglishInput("discord"))

        // 日本語日常語・助詞（英語扱いになってはいけない単語）
        assertFalse(scoreEngine.isEnglishInput("ima"))
        assertFalse(scoreEngine.isEnglishInput("ha"))
        assertFalse(scoreEngine.isEnglishInput("he"))
        assertFalse(scoreEngine.isEnglishInput("sore"))
        assertFalse(scoreEngine.isEnglishInput("are"))
        assertFalse(scoreEngine.isEnglishInput("dare"))
        assertFalse(scoreEngine.isEnglishInput("dourou"))
        assertFalse(scoreEngine.isEnglishInput("kyouha"))
    }

    /**
     * "kyouhadiamondwotoriniikou" が助詞「は」「を」を手がかりに
     * 「きょうは」+「diamond」+「をとりにいこう」に自動分割されることの検証
     */
    @Test
    fun testSplitSentenceMultiCompound() {
        val splitter = CompoundWordSplitter(englishDetector, romajiDetector)
        val split = splitter.splitSentence("kyouhadiamondwotoriniikou")

        assertNotNull(split)
        assertEquals(3, split!!.segments.size)
        assertEquals("きょうは", split.segments[0].reading)
        assertFalse(split.segments[0].isEnglish)
        assertEquals("diamond", split.segments[1].reading)
        assertTrue(split.segments[1].isEnglish)
        assertEquals("をとりにいこう", split.segments[2].reading)
        assertFalse(split.segments[2].isEnglish)

        assertEquals("きょうは diamond をとりにいこう", split.previewText)
    }

    /**
     * 「ハロウィン」など、うぃ・てぃ・つぁ行のローマ字入力ができることの検証
     */
    @Test
    fun testExtendedRomajiForLoanwords() {
        // 語尾の n は、確定・変換のときに「ん」になる（入力途中のプレビューは n のまま）
        assertEquals("はろうぃん", romajiDetector.toKanaFinal("harowin"))
        assertEquals("はろうぃn", romajiDetector.toKanaLenient("harowin"))
        assertEquals("ハロウィン", romajiDetector.toKatakana(romajiDetector.toKanaFinal("harowin")))
        assertEquals("はろうぃん", romajiDetector.toKanaFinal("harowinn"))
        assertEquals("はろうぃん", romajiDetector.toKanaFinal("harouxin"))
        assertEquals("ほん", romajiDetector.toKanaFinal("hon"))
        assertEquals("つぁ", romajiDetector.toKanaLenient("tsa"))
        assertEquals("じゃ", romajiDetector.toKanaLenient("zya"))
        assertEquals("ちゃ", romajiDetector.toKanaLenient("cya"))
        assertEquals("でぃ", romajiDetector.toKanaLenient("dhi"))
        // 日本語として入力できる（英語に誤判定されない）
        assertFalse(scoreEngine.isEnglishInput("harowin"))
        // 英単語 "we" は日本語（うぇ）にならない
        assertTrue(scoreEngine.isEnglishInput("we") || romajiDetector.toKanaLenient("we") != "うぇ")
    }

    /**
     * "hidarite"（左手）の末尾 "rite" が英単語と誤判定され「ひだrite」にならないことの検証
     */
    @Test
    fun testPureRomajiWordIsNotSplitByShortEnglishWord() {
        val splitter = CompoundWordSplitter(englishDetector, romajiDetector)
        assertNull(splitter.splitSentence("hidarite"))
        assertNull(splitter.split("hidarite"))
        // "settei"（設定）の先頭 "set" も同様に英単語と誤判定されない
        assertNull(splitter.splitSentence("settei"))
        assertNull(splitter.split("settei"))
        // "onegaishitai"（お願いしたい）の先頭 "one" も同様
        assertNull(splitter.splitSentence("onegaishitai"))
        assertNull(splitter.split("onegaishitai"))
    }
}
