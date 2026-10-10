package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
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
        MeltypeConfig.enabled = true
        MeltypeConfig.initialInputMode = InputMode.ENGLISH
        MeltypeConfig.rememberLastInputMode = false

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
    fun testInputModeToggleWithHankakuZenkakuKey() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // 1. 初期状態は半角英数字モード（ENGLISH）
        assertEquals(InputMode.ENGLISH, session.currentInputMode)

        // 2. 半角英数モードでは英字入力がバイパス（falseを返し、バニラが直接処理）される
        val englishHandled = session.onCharTyped('a', "", insertToChat)
        assertFalse(englishHandled, "ENGLISHモード時はMODで消費せずバニラ直接入力に任せること")
        assertFalse(session.isComposing)

        // 3. 半角/全角キー（JISスキャンコード41）を押す -> 日本語入力モード（JAPANESE）に切り替え！
        val hankakuHandled = session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertTrue(hankakuHandled, "半角/全角キーはMODで消費してバニラに入力させないこと")
        assertEquals(InputMode.JAPANESE, session.currentInputMode)

        // 4. 日本語入力モードでは英字入力がローマ字として蓄積・リアルタイム変換される
        val jpHandled = session.onCharTyped('k', "", insertToChat)
        assertTrue(jpHandled, "JAPANESEモード時はMODが消費してリアルタイム変換すること")
        assertTrue(session.isComposing)

        // 5. もう一度半角/全角キーを押す -> 確定されて半角英数モード（ENGLISH）に戻る！
        session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertEquals(InputMode.ENGLISH, session.currentInputMode)
        assertFalse(session.isComposing)
        assertEquals(1, committed.size) // 入力中だった 'k' が確定されてチャットに挿入された
    }

    @Test
    fun testHenkanAndMuhenkanKeys() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        assertEquals(InputMode.ENGLISH, session.currentInputMode)

        // 変換キー (scancode 121) -> JAPANESE
        val henkanHandled = session.onKeyPressed(0, 121, 0, insertToChat)
        assertTrue(henkanHandled)
        assertEquals(InputMode.JAPANESE, session.currentInputMode)

        // 無変換キー (scancode 123) -> ENGLISH
        val muhenkanHandled = session.onKeyPressed(0, 123, 0, insertToChat)
        assertTrue(muhenkanHandled)
        assertEquals(InputMode.ENGLISH, session.currentInputMode)
    }

    @Test
    fun testContinuousTypingAndAutoCommitInJapaneseMode() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // 半角/全角キーを押して日本語モードに切り替え
        session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertEquals(InputMode.JAPANESE, session.currentInputMode)

        // 1. "kyouha" と入力
        for (c in "kyouha") {
            val handled = session.onCharTyped(c, "", insertToChat)
            assertTrue(handled)
        }
        assertEquals("きょうは", session.previewKana)
        assertTrue(session.isComposing)

        // 2. スペースキーを押して変換
        val spaceHandled = session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
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
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertEquals(3, committed.size)
        assertEquals("diamond", committed[1])
        assertEquals(" ", committed[2])
        assertFalse(session.isComposing)
    }

    @Test
    fun testSentenceWithParticlesAndPunctuation() {
        val analysis = romajiDetector.analyze("kyouhadaiyawosagasou!")
        assertTrue(analysis.isValid)
        assertEquals("きょうはだいやをさがそう!", analysis.kana)

        // 助詞 (ha->は, wo->を) が正しく含まれていること
        assertTrue(analysis.kana.contains("は"))
        assertTrue(analysis.kana.contains("を"))
    }

    @Test
    fun testGoogleCgiMultiSegmentParsing() {
        val converter = net.oyasai.meltype.conversion.GoogleCgiConverter()
        val mockJson = """
            [
              ["きょうは", ["今日は", "きょうは", "京は"]],
              ["だいやを", ["ダイヤを", "だいやを"]],
              ["さがそう", ["探そう", "さがそう", "捜そう"]]
            ]
        """.trimIndent()

        // リフレクションまたは直接メソッドで parseGoogleCgiResponse をテスト
        val method = converter.javaClass.getDeclaredMethod("parseGoogleCgiResponse", String::class.java, String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val results = method.invoke(converter, mockJson, "きょうはだいやをさがそう") as List<String>

        // 第1候補が全文結合された「今日はダイヤを探そう」になっていること！
        assertEquals("今日はダイヤを探そう", results[0])
        assertTrue(results.contains("きょうはだいやをさがそう"))
    }
}
