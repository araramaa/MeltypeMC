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

    /**
     * ユーザー指定の重要新機能:
     * 英語の後に てにをは の助詞が付いた時、英語と日本語に自動分割
     * 例: diamondwosagasou -> diamondを探そう
     */
    @Test
    fun testCompoundWordGluedParticles() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val localConverter = net.oyasai.meltype.conversion.LocalDictionaryConverter()
        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate,
            converter = localConverter
        )

        // HYBRIDモードに設定
        session.toggleInputMode()
        assertEquals(InputMode.HYBRID, session.currentInputMode)

        // 1. スペースを挟まずに "diamondwosagasou" と一気にタイプ
        val input = "diamondwosagasou"
        for (c in input) {
            session.onCharTyped(c, "", insertToChat)
        }

        // 入力中プレビューが "diamond をさがそう" と自動分割されていること！
        assertEquals("diamond をさがそう", session.previewKana)

        // 2. スペースキーを押して変換
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)

        // 第1候補が "diamondを探そう" であること！
        assertEquals("diamondを探そう", session.candidates[0])

        // 3. Enterキーを押して確定
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(1, committed.size)
        assertEquals("diamondを探そう", committed[0])

        // 4. "applega" -> "appleが" のテスト
        committed.clear()
        for (c in "applega") {
            session.onCharTyped(c, "", insertToChat)
        }
        assertEquals("apple が", session.previewKana)
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals("appleが", committed[0])
    }

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
            slashCommandGate = slashGate
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
     * 変換学習機能のテスト:
     * よく使う変換候補が自動記録され、次回以降最優先（一番上）に繰り上がること
     */
    @Test
    fun testConversionLearningRankingAndPersistence() {
        val tempFile = java.io.File.createTempFile("meltype_learning_test", ".txt")
        tempFile.deleteOnExit()

        val store = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)
        val defaultCandidates = listOf("タイヤ", "鯛焼き", "たいや")

        // 1. 最初はデフォルト順序
        val initialRanked = store.rankCandidates("たいや", defaultCandidates)
        assertEquals(listOf("タイヤ", "鯛焼き", "たいや"), initialRanked)

        // 2. ユーザーが「鯛焼き」を2回確定
        store.recordSelection("たいや", "鯛焼き")
        store.recordSelection("たいや", "鯛焼き")
        store.save()

        // 3. 次回は「鯛焼き」が第1候補（一番上）に繰り上がること！
        val rankedAfterLearning = store.rankCandidates("たいや", defaultCandidates)
        assertEquals("鯛焼き", rankedAfterLearning[0])
        assertEquals(listOf("鯛焼き", "タイヤ", "たいや"), rankedAfterLearning)

        // 4. ファイルからの再読み込み（永続化テスト: マイクラ再起動後も記憶されていること）
        val reloadedStore = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)
        val reloadedRanked = reloadedStore.rankCandidates("たいや", defaultCandidates)
        assertEquals("鯛焼き", reloadedRanked[0])
    }

    /**
     * セッション統合での変換学習テスト:
     * セッション内で候補を選んで確定すると次回から第1候補になること
     */
    @Test
    fun testSessionIntegrationLearning() {
        val tempFile = java.io.File.createTempFile("meltype_session_learning_test", ".txt")
        tempFile.deleteOnExit()

        val learningStore = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)
        val localConverter = net.oyasai.meltype.conversion.LocalDictionaryConverter()
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate,
            converter = localConverter,
            learningStore = learningStore
        )

        session.toggleInputMode()

        // 1回目: "kyouha" を入力して変換
        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)
        // 初期状態: ["今日は", "きょうは", "キョウハ"]
        assertEquals("今日は", session.candidates[0])

        // あえて第2候補の「きょうは」（ひらがな）を選択して確定
        session.onKeyPressed(GLFW.GLFW_KEY_DOWN, 0, 0, insertToChat)
        assertEquals(1, session.selectedCandidateIndex)
        assertEquals("きょうは", session.candidates[session.selectedCandidateIndex])
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals("きょうは", committed[0])

        // 2回目: 再び "kyouha" を入力して変換
        committed.clear()
        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)

        // 前回選んだ「きょうは」が学習され、第1候補（インデックス0）に繰り上がっていること！
        assertEquals("きょうは", session.candidates[0])
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals("きょうは", committed[0])
    }

    /**
     * 設定の保存と読み込みテスト
     */
    @Test
    fun testMeltypeConfigPersistence() {
        val originalCandidates = MeltypeConfig.maxCandidates
        val originalInitialMode = MeltypeConfig.initialInputMode

        try {
            MeltypeConfig.maxCandidates = 7
            MeltypeConfig.initialInputMode = InputMode.HYBRID
            MeltypeConfig.save()

            // 変更が保持されていること
            assertEquals(7, MeltypeConfig.maxCandidates)
            assertEquals(InputMode.HYBRID, MeltypeConfig.initialInputMode)

            // 再ロード
            MeltypeConfig.load()
            assertEquals(7, MeltypeConfig.maxCandidates)
            assertEquals(InputMode.HYBRID, MeltypeConfig.initialInputMode)
        } finally {
            MeltypeConfig.maxCandidates = originalCandidates
            MeltypeConfig.initialInputMode = originalInitialMode
            MeltypeConfig.save()
        }
    }
}
