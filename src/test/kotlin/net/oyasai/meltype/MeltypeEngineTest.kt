package net.oyasai.meltype

import net.oyasai.meltype.engine.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MeltypeEngineTest {

    private lateinit var romajiDetector: RomajiDetector
    private lateinit var englishDetector: EnglishDetector
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var slashGate: SlashCommandGate

    @BeforeEach
    fun setUp() {
        romajiDetector = RomajiDetector()
        englishDetector = EnglishDetector(listOf("diamond", "gamemode", "survival", "item", "sword", "iron", "gold", "apple"))
        scoreEngine = ScoreEngine(romajiDetector, englishDetector)
        slashGate = SlashCommandGate()
    }

    @Test
    fun testRomajiAnalysis() {
        val analysis = romajiDetector.analyze("kyouha")
        assertTrue(analysis.isValid)
        assertEquals("きょうは", analysis.kana)
    }

    @Test
    fun testEnglishDetection() {
        assertTrue(englishDetector.isWord("diamond"))
        assertTrue(englishDetector.isPrefix("diam"))
        assertFalse(englishDetector.isWord("kyouha"))
    }

    @Test
    fun testScoreEngineJapanese() {
        val result = scoreEngine.evaluate("kyouha", isFinal = true)
        assertEquals(Verdict.JAPANESE, result.verdict)
    }

    @Test
    fun testScoreEngineEnglish() {
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

    @Test
    fun testSlashCommandGateBypass() {
        // ユーザー指定要件: スラッシュを入力したときは勝手にコマンド入力モードになって欲しい
        // 1. チャット欄が空で '/' を入力した場合 -> バイパス
        assertTrue(slashGate.shouldBypassIme("", '/'))
        assertTrue(slashGate.isCommandMode)

        // 2. 先頭が '/' の状態でコマンドを入力中 -> バイパス継続
        assertTrue(slashGate.shouldBypassIme("/g", 'a'))
        assertTrue(slashGate.isCommandMode)

        // 3. 通常チャット（'/' で始まらない） -> バイパスしない（IME有効）
        slashGate.reset()
        assertFalse(slashGate.shouldBypassIme("", 'k'))
        assertFalse(slashGate.isCommandMode)
    }

    @Test
    fun testContinuousTypingAndAutoCommit() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // 1. "kyouha" と入力
        for (c in "kyouha") {
            val handled = session.onCharTyped(c, "", insertToChat)
            assertTrue(handled)
        }
        assertEquals("きょうは", session.previewKana)
        assertTrue(session.isComposing)

        // 2. スペースキーを押して変換
        val spaceHandled = session.onKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(spaceHandled)
        assertTrue(session.hasCandidates)
        assertEquals(0, committed.size) // まだ確定されていない

        // 3. 確定せずに次の英単語 "diamond" の 'd' を入力 -> 直前の候補が自動確定される！
        val dHandled = session.onCharTyped('d', "", insertToChat)
        assertTrue(dHandled)
        assertEquals(1, committed.size) // "きょうは" が自動確定された！
        assertEquals("きょうは", committed[0])
        assertEquals("d", session.rawBuffer.toString())

        // 4. "iamond" を入力
        for (c in "iamond") {
            session.onCharTyped(c, "", insertToChat)
        }
        assertEquals("diamond", session.rawBuffer.toString())

        // 5. スペースキーを押す -> 英語判定のため "diamond" + " " が確定される！
        session.onKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertEquals(3, committed.size)
        assertEquals("diamond", committed[1])
        assertEquals(" ", committed[2])
        assertFalse(session.isComposing)
    }
}
