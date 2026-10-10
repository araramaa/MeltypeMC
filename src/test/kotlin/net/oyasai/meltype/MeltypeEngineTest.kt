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
        MeltypeConfig.initialInputMode = InputMode.DIRECT
        MeltypeConfig.rememberLastInputMode = true

        romajiDetector = RomajiDetector()
        englishDetector = EnglishDetector.loadFromResource("/dictionaries/english-words.txt")
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

    @Test
    fun testSlashCommandGateBypass() {
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

    /**
     * ユーザー指定の重要要件:
     * ［半角/全角］キー不要のハイブリッド入力
     * キーボードを切り替える必要が一切なく、
     * kyouha diamond wo sagasou とそのまま打つだけで、
     * kyouha → 今日は（日本語判定）
     * diamond → diamond（英単語判定・英語のまま）
     * wo → を（日本語判定）
     * sagasou → 探そう（日本語判定） と自動で打ち分けられることの検証！
     */
    @Test
    fun testHybridTypingWithoutModeSwitch() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // 初期状態は DIRECT モード
        assertEquals(InputMode.DIRECT, session.currentInputMode)

        // 半角/全角キーを押して HYBRID（日本語変換）モードに切り替え
        session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertEquals(InputMode.HYBRID, session.currentInputMode)

        // 1. "kyouha" + Space -> 日本語判定
        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)
        // 候補の先頭は「きょうは」
        assertEquals("きょうは", session.candidates[0])

        // 2. 次の単語 "diamond" の 'd' をタイプ -> 直前の「きょうは」が自動確定！
        session.onCharTyped('d', "", insertToChat)
        assertEquals(1, committed.size)
        assertEquals("きょうは", committed[0])

        // 3. "iamond" + Space -> 英単語判定！「ぢあもんd」にならず即座に "diamond " が確定される！
        for (c in "iamond") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertEquals(2, committed.size)
        assertEquals("diamond ", committed[1])
        assertFalse(session.isComposing, "英単語確定後はバッファがクリアされていること")

        // 4. "wo" + Space -> 日本語判定「を」
        for (c in "wo") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)

        // 5. 次の単語 "sagasou" の 's' をタイプ -> 直前の「を」が自動確定！
        session.onCharTyped('s', "", insertToChat)
        assertEquals(3, committed.size)
        assertEquals("を", committed[2])

        // 6. "agasou" + Enter -> 「さがそう」が確定！
        for (c in "agasou") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(4, committed.size)
        assertEquals("さがそう", committed[3])

        // 全体の確定結果が「きょうは」「diamond 」「を」「さがそう」であること！
        val fullMessage = committed.joinToString("")
        assertEquals("きょうはdiamond をさがそう", fullMessage)
    }

    @Test
    fun testHybridSentenceWithSpaces() {
        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // HYBRIDモードに切り替え
        session.toggleInputMode()

        // Shift+Space 等でスペースを挟んで一括入力した場合
        val input = "kyouha diamond wo sagasou"
        for (c in input) {
            if (c == ' ') {
                session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, GLFW.GLFW_MOD_SHIFT) {}
            } else {
                session.onCharTyped(c, "") {}
            }
        }

        // プレビューが英語と日本語で正しく保持されていること（diamondがぢあもんdにならないこと）
        assertEquals("きょうは diamond を さがそう", session.previewKana)
    }

    @Test
    fun testHankakuZenkakuKeyDirectModeToggle() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // チャット開始時は DIRECT モード（何もしなければ通常の半角英数入力）
        assertEquals(InputMode.DIRECT, session.currentInputMode)

        // DIRECTモードでは文字入力が一切インターセプトされず、通常の半角がそのまま通る
        val handled = session.onCharTyped('a', "", insertToChat)
        assertFalse(handled)

        // 半角/全角キー（または F12 / Ctrl+Space / マウスクリック）で HYBRID（日本語変換モード）に切り替え
        session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertEquals(InputMode.HYBRID, session.currentInputMode)

        // HYBRID モードでは文字入力がインターセプトされてハイブリッド変換バッファに入る
        val handledHybrid = session.onCharTyped('a', "", insertToChat)
        assertTrue(handledHybrid)

        // もう一度押せば DIRECT（半角直接入力）に戻る
        session.onKeyPressed(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, 0, insertToChat)
        assertEquals(InputMode.DIRECT, session.currentInputMode)
    }

    @Test
    fun testModePersistsAcrossScreensUntilToggled() {
        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // 初回は DIRECT（半角英数）
        assertEquals(InputMode.DIRECT, session.currentInputMode)

        // 切り替えボタンを押して HYBRID（日本語変換）にする
        session.toggleInputMode()
        assertEquals(InputMode.HYBRID, session.currentInputMode)

        // チャットを送信・閉じる・別の画面を開く（resetForNewScreen）
        session.resetForNewScreen()

        // 次にボタンを押すまで HYBRID モードが維持・固定されていること！
        assertEquals(InputMode.HYBRID, session.currentInputMode)

        // もう一度切り替えボタンを押して DIRECT にする
        session.toggleInputMode()
        assertEquals(InputMode.DIRECT, session.currentInputMode)

        // 再び画面を開閉しても DIRECT モードが固定・維持されること！
        session.resetForNewScreen()
        assertEquals(InputMode.DIRECT, session.currentInputMode)
    }
}
