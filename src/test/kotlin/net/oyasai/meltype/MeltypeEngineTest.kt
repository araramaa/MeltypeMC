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
     * 「ima」と入力して Space を押した際に、チャット欄へ「ima 」と直接出力されず、
     * 正常に変換候補ウィンドウが表示されることの検証
     */
    @Test
    fun testImaTypingSession() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        // HYBRIDモードに設定
        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "ima" とタイプ
        for (c in "ima") {
            session.onCharTyped(c, "", insertToChat)
        }

        // 入力中のプレビューが「いま」になっていること
        assertEquals("いま", session.previewKana)

        // Space を押す -> 英語として確定されず、変換候補ウィンドウが表示されること！
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates, "Space押下時に変換候補が表示されること")
        assertTrue(committed.isEmpty(), "チャット欄へ「ima 」として勝手に確定出力されないこと")
        assertTrue(session.candidates.contains("いま"), "候補リストに読みが含まれること")
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
     * 「ha」と入力した時のプレビューが「は」であり、Spaceを押した際に「ha 」とならず、
     * 正常に変換候補が表示されることの検証
     */
    @Test
    fun testHaTypingSession() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "ha" とタイプ
        for (c in "ha") {
            session.onCharTyped(c, "", insertToChat)
        }

        // 入力中プレビューが「ha」ではなく「は」になっていること！
        assertEquals("は", session.previewKana)

        // Space を押す -> 「ha 」と確定されず、変換候補ウィンドウが表示されること！
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates, "Space押下時に変換候補が表示されること")
        assertTrue(committed.isEmpty(), "チャット欄へ「ha 」として勝手に確定出力されないこと")
        assertTrue(session.candidates.contains("は"), "候補リストに読みが含まれること")
    }

    /**
     * 「douro (どうろ)」が英単語「dour」＋助詞「o」に誤爆分割されず、
     * 正常に「どうろ」としてプレビューおよび変換候補が表示されることの検証
     */
    @Test
    fun testDouroTypingSession() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "douro" とタイプ
        for (c in "douro") {
            session.onCharTyped(c, "", insertToChat)
        }

        // 入力中プレビューが「dour お」ではなく「どうろ」になっていること！
        assertEquals("どうろ", session.previewKana)

        // Space を押す -> 「dourお」とならず、変換候補ウィンドウが表示されること！
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates, "Space押下時に変換候補が表示されること")
        assertTrue(committed.isEmpty(), "勝手にチャット欄へ確定出力されないこと")
        assertTrue(session.candidates.contains("どうろ"), "候補リストに「どうろ」が含まれること")
    }

    /**
     * 「sore (それ)」「are (あれ)」「dare (だれ)」「sake (酒)」などの英語辞書重複語が、
     * 英語確定されずに正常に日本語（「それ」「あれ」等）として変換できることの検証
     */
    @Test
    fun testSoreAndAmbiguousJapaneseWords() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "sore" とタイプ
        for (c in "sore") {
            session.onCharTyped(c, "", insertToChat)
        }

        // 入力中プレビューが「それ」になっていること
        assertEquals("それ", session.previewKana)

        // Space を押す -> 英語 "sore " として勝手に確定されず、候補ウィンドウが開くこと！
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates, "Space押下時に変換候補ウィンドウが開くこと")
        assertTrue(committed.isEmpty(), "チャット欄へ英語のまま勝手に確定挿入されないこと")
        assertTrue(session.candidates.contains("それ"), "候補リストに「それ」が含まれること")
    }

    /**
     * 英語判定された単語（例: diamond）であっても、候補ウィンドウを開いた場合に
     * 英語だけでなく日本語変換候補（カタカナ等）が共存して表示されることの検証
     */
    @Test
    fun testEnglishWordWithJapaneseCandidates() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "diamond" とタイプ
        for (c in "diamond") {
            session.onCharTyped(c, "", insertToChat)
        }

        // Tabキー（または下矢印）で候補ウィンドウを開く
        session.onKeyPressed(GLFW.GLFW_KEY_TAB, 0, 0, insertToChat)
        assertTrue(session.hasCandidates, "Tabキーで候補ウィンドウが開くこと")
        // 英語だけでなく、カタカナや読みが候補に含まれていること！
        assertTrue(session.candidates.contains("diamond"), "第1候補に英語が含まれること")
        assertTrue(session.candidates.any { it != "diamond" }, "英語以外の日本語候補（カタカナ・ひらがな等）が含まれること")
    }

    /**
     * 変換候補リストの中に、最初に入力したアルファベット（raw）が必ず含まれており、
     * 漢字変換以外に最初の文字をそのまま選べることの検証
     */
    @Test
    fun testRawInputSelectableInCandidates() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "kyouha" とタイプして Space で変換
        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)

        assertTrue(session.hasCandidates)
        // 候補リストに、最初に入力した英字 "kyouha" が必ず含まれていること！
        assertTrue(session.candidates.contains("kyouha"), "最初に入力した文字そのまま選べること")
        // ひらがな "きょうは" も必ず含まれていること！
        assertTrue(session.candidates.contains("きょうは"), "ひらがなそのまま選べること")
    }

    /**
     * Shift+Enter で最初に入力したアルファベットがそのままチャットに即座に確定挿入されることの検証
     */
    @Test
    fun testShiftEnterCommitsRawInput() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        for (c in "kyouha") session.onCharTyped(c, "", insertToChat)
        // Shift+Enter を押す
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)

        assertEquals(1, committed.size)
        // 漢字「今日は」ではなく、最初に入力した「kyouha」がそのまま確定挿入されること！
        assertEquals("kyouha", committed[0])
        assertFalse(session.isComposing)
    }

    /**
     * F10キーで最初に入力した半角英字、F7キーで全角カタカナ、F6キーでひらがなへ変換できることの検証
     */
    @Test
    fun testFunctionKeysDirectSelection() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "tesuto" と入力
        for (c in "tesuto") session.onCharTyped(c, "", insertToChat)

        // F10キー -> 半角英字 "tesuto"
        session.onKeyPressed(GLFW.GLFW_KEY_F10, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)
        assertEquals("tesuto", session.candidates[0])

        // F7キー -> 全角カタカナ "テスト"
        session.onKeyPressed(GLFW.GLFW_KEY_F7, 0, 0, insertToChat)
        assertEquals("テスト", session.candidates[0])

        // F6キー -> ひらがな "てすと"
        session.onKeyPressed(GLFW.GLFW_KEY_F6, 0, 0, insertToChat)
        assertEquals("てすと", session.candidates[0])
    }

    /**
     * "Modrinth" や "modrinth" を入力した際に「も」としか変換されないバグの修正検証
     * プレビューが英語のまま維持され、スペースで直接確定、または候補表示で英単語が選べること
     */
    @Test
    fun testModrinthInputDoesNotTruncateToMo() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // 1. "Modrinth" と入力
        for (c in "Modrinth") session.onCharTyped(c, "", insertToChat)

        // プレビューが「も」ではなく「Modrinth」であること！
        assertEquals("Modrinth", session.previewKana)

        // Enterで確定 -> "Modrinth" がそのまま確定挿入される！
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(1, committed.size)
        assertEquals("Modrinth", committed[0])
        assertFalse(session.isComposing)

        // 2. スペースキーでの英単語自動確定（"Modrinth "）の検証
        committed.clear()
        for (c in "modrinth") session.onCharTyped(c, "", insertToChat)
        assertEquals("modrinth", session.previewKana)
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertEquals("modrinth ", committed[0])
        assertFalse(session.isComposing)

        // 3. 下矢印キー（またはTabキー）で変換候補を開いた場合、英単語が第1候補にあること
        committed.clear()
        for (c in "Modrinth") session.onCharTyped(c, "", insertToChat)
        session.onKeyPressed(GLFW.GLFW_KEY_DOWN, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)
        assertEquals("Modrinth", session.candidates[0])
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
     * スペースなし一括入力 "kyouhadiamondwotoriniikou" のプレビューおよび変換の検証
     * 「今日はdiamondを取りに行こう」が第1候補として合成されること
     */
    @Test
    fun testKyouhaDiamondWoToriniikouFullFlow() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // スペースなしで一括タイプ
        for (c in "kyouhadiamondwotoriniikou") session.onCharTyped(c, "", insertToChat)

        // プレビューが「きょうは diamond をとりにいこう」と表示されること！
        assertEquals("きょうは diamond をとりにいこう", session.previewKana)

        // 変換を実行
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)

        // 候補リストに英単語 "diamond" を含んだ文が含まれていること！
        assertTrue(session.candidates.any { it.contains("diamond") })
        // 最初に入力した文字列そのままでも選択可能なこと！
        assertTrue(session.candidates.contains("kyouhadiamondwotoriniikou"))
    }

    /**
     * "ohayou" が "you"（英語）に誤分割されて「おはyou」になってしまわず、
     * 「おはよう」としてプレビューおよび変換され、かつ原文 "ohayou" も候補に含まれることの検証
     */
    @Test
    fun testOhayouInputDoesNotSplitToYou() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )

        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "ohayou" と入力
        for (c in "ohayou") session.onCharTyped(c, "", insertToChat)

        // プレビューが「おはyou」ではなく「おはよう」であること！
        assertEquals("おはよう", session.previewKana)

        // 変換を実行
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, insertToChat)
        assertTrue(session.hasCandidates)

        // 候補に「おはよう」が含まれていること！
        assertTrue(session.candidates.contains("おはよう"))
        // 原文「ohayou」が必ず候補に含まれていて選択できること！
        assertTrue(session.candidates.contains("ohayou"), "原文 ohayou が候補に含まれること")
    }

    @Test
    fun testSlashCommandArgsJapaneseAllowed() {
        // /say の場合は2番目のトークンからメッセージ（日本語許可）
        assertFalse(slashGate.shouldBypassIme("/say ", 'k'), "/say 以降はIMEがバイパスされないこと")
        assertFalse(slashGate.shouldBypassIme("/say test ", 'k'), "/say の後続入力もIMEがバイパスされないこと")

        // /me の場合も2番目のトークンからメッセージ
        assertFalse(slashGate.shouldBypassIme("/me ", 'k'), "/me 以降はIMEがバイパスされないこと")

        // /tell の場合は3番目のトークンからメッセージ
        assertTrue(slashGate.shouldBypassIme("/tell ", 'p'), "/tell 直後のプレイヤー名はバイパスされること")
        assertFalse(slashGate.shouldBypassIme("/tell steve ", 'k'), "/tell <player> 以降はIMEがバイパスされないこと")
    }

    @Test
    fun testMeltypeConfigPropertiesPersistence() {
        val tempFile = java.io.File.createTempFile("meltype_test_config", ".properties")
        try {
            val props = java.util.Properties()
            props.setProperty("japaneseThreshold", "3")
            props.setProperty("useGoogleCgi", "false")
            props.setProperty("typoCorrectionEnabled", "false")

            tempFile.writer(java.nio.charset.StandardCharsets.UTF_8).use {
                props.store(it, "Test")
            }

            // 直接プロパティ読み込みの検証
            val loaded = java.util.Properties()
            tempFile.reader(java.nio.charset.StandardCharsets.UTF_8).use { loaded.load(it) }

            assertEquals(3, loaded.getProperty("japaneseThreshold")?.toIntOrNull())
            assertEquals(false, loaded.getProperty("useGoogleCgi")?.toBooleanStrictOrNull())
            assertEquals(false, loaded.getProperty("typoCorrectionEnabled")?.toBooleanStrictOrNull())
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Windows等の外部IMEが有効な状態でひらがな・漢字の確定文字が直接送られてきた場合、
     * Meltype側のバッファが自動破棄されて二重入力（重複挿入）が防止されることのテスト
     */
    @Test
    fun testExternalImeDirectJapaneseInputCancelsBufferToPreventDuplicateInput() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )
        session.setInputMode(InputMode.HYBRID, insertToChat)

        // 1. "konn" までローマ字が入力された状態
        for (c in "konn") session.onCharTyped(c, "", insertToChat)
        assertTrue(session.isComposing)
        assertEquals("こん", session.previewKana)

        // 2. ここでWindows IMEが確定した日本語文字「今」が直接 charTyped として飛んできた
        val handled = session.onCharTyped('今', "", insertToChat)

        // Meltype側は二重確定を避けるため insertToChat に確定文字を流し込まず、Minecraftバニラに処理を任せる (handled = false)
        assertFalse(handled, "外部IMEの文字はバニラにパススルーされること")
        // Meltype側の未確定バッファはクリアされていること
        assertFalse(session.isComposing, "Meltypeの未確定バッファは破棄されること")
        assertEquals("", session.previewKana)
        // 二重挿入されていないこと
        assertTrue(committed.isEmpty(), "Meltype側から二重にテキストが挿入されていないこと")
    }

    /**
     * ユーザー指定機能:
     * 「最初に打った文字をシフトで一文字ずつ選んで変換できるようにする」
     * Shift + Left で文節を 1 文字ずつ縮め、Shift + Right で伸ばし、
     * 選択した文節のみを部分確定して、残りの文字列を引き継げることの検証
     */
    @Test
    fun testShiftArrowClauseResizingAndPartialConversion() {
        val committed = mutableListOf<String>()
        val insertToChat: (String) -> Unit = { committed.add(it) }

        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate
        )
        session.setInputMode(InputMode.HYBRID, insertToChat)

        // "kyouhadiamond" と入力
        for (c in "kyouhadiamond") session.onCharTyped(c, "", insertToChat)
        assertTrue(session.isComposing)

        // 1. Shift + Left を押して文節を縮める
        // "kyouhadiamond" (13文字) -> Shift+Left で 12文字へ
        session.onKeyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertEquals(12, session.clauseLength)
        assertTrue(session.hasCandidates)
        assertTrue(session.previewKana.startsWith("["))

        // 2. さらに Shift + Left を複数回押して "kyouha" (6文字) まで縮める
        while (session.clauseLength > 6) {
            session.onKeyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        }
        assertEquals(6, session.clauseLength)

        // "kyouha"（きょうは）の候補が表示され、残りの "diamond" がプレビューに含まれていること
        assertTrue(session.candidates.contains("きょうは"))
        assertTrue(session.previewKana.contains("diamond"))

        // 3. Shift + Right を 1 回押すと 7文字 ("kyouhad") に伸びること
        session.onKeyPressed(GLFW.GLFW_KEY_RIGHT, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertEquals(7, session.clauseLength)

        // 再度 Shift + Left で 6文字 ("kyouha") に戻す
        session.onKeyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_SHIFT, insertToChat)
        assertEquals(6, session.clauseLength)

        // 4. Enter キーで「きょうは」を部分確定！
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)

        // チャット欄に「きょうは」（または変換された漢字）が挿入されていること
        assertEquals(1, committed.size)
        val firstCommit = committed[0]
        assertTrue(firstCommit.isNotEmpty())

        // 5. 残りの "diamond" がバッファに引き継がれ、未確定状態として継続していること！
        assertTrue(session.isComposing)
        assertEquals("diamond", session.rawBuffer.toString())

        // 6. 続けて Enter を押すと残りの "diamond" も確定されること
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0, insertToChat)
        assertEquals(2, committed.size)
        assertEquals("diamond", committed[1])
        assertFalse(session.isComposing)
    }
}


