package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 変換学習のテスト */
class LearningTest : MeltypeTestBase() {

    /**
     * タブや改行を含む読み・候補は学習しない（保存ファイルの1行1件の形式が崩れないこと）の検証
     */
    @Test
    fun testLearningIgnoresTabAndNewline() {
        val tempFile = java.io.File.createTempFile("meltype_learning_tab_test", ".txt")
        tempFile.deleteOnExit()
        val store = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)

        store.recordSelection("たいや", "鯛焼き")
        store.recordSelection("たい\tや", "タイヤ")
        store.recordSelection("たいや", "タイ\nヤ")
        store.recordSelection("たいや", "タイ\rヤ")
        assertEquals(listOf("鯛焼き"), store.getLearnedCandidates("たいや"))
        assertTrue(store.getLearnedCandidates("たい\tや").isEmpty())
        store.save()

        // 再読み込みしても、正しい1件だけが残っていること
        val reloaded = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)
        assertEquals(listOf("鯛焼き"), reloaded.getLearnedCandidates("たいや"))
        val dataLines = tempFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(1, dataLines.size, "保存ファイルが崩れていないこと: $dataLines")
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
     * 設定で「変換学習機能」を OFF にすると、記録も並べ替えもされず、
     * ON に戻すと元の学習が効くことの検証
     */
    @Test
    fun testLearningDisabledSkipsRecordingAndRanking() {
        val tempFile = java.io.File.createTempFile("meltype_learning_off_test", ".txt")
        tempFile.deleteOnExit()
        val store = net.oyasai.meltype.conversion.ConversionLearningStore(tempFile)
        val defaultCandidates = listOf("タイヤ", "鯛焼き", "たいや")

        // ON の間に「鯛焼き」を学習
        store.recordSelection("たいや", "鯛焼き")
        assertEquals(listOf("鯛焼き"), store.getLearnedCandidates("たいや"))

        MeltypeConfig.learningEnabled = false
        // OFF の間は並べ替えず、学習済み候補も返さない
        assertEquals(defaultCandidates, store.rankCandidates("たいや", defaultCandidates))
        assertTrue(store.getLearnedCandidates("たいや").isEmpty())

        // OFF の間は記録されない
        store.recordSelection("たいや", "タイヤ")
        store.recordSelection("たいや", "タイヤ")

        // ON に戻すと、元の学習だけが効く（OFF 中の選択は記録されていない）
        MeltypeConfig.learningEnabled = true
        assertEquals(listOf("鯛焼き"), store.getLearnedCandidates("たいや"))
        assertEquals(listOf("鯛焼き", "タイヤ", "たいや"), store.rankCandidates("たいや", defaultCandidates))
    }

    /**
     * 学習機能が OFF のセッションでは、候補を確定しても学習されないことの検証
     */
    @Test
    fun testSessionDoesNotLearnWhenLearningDisabled() {
        MeltypeConfig.learningEnabled = false
        val store = newTempLearningStore()
        val session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate,
            converter = net.oyasai.meltype.conversion.LocalDictionaryConverter(),
            learningStore = store
        )
        session.toggleInputMode()

        for (c in "kyouha") session.onCharTyped(c, "") {}
        session.onKeyPressed(GLFW.GLFW_KEY_SPACE, 0, 0) {}
        session.onKeyPressed(GLFW.GLFW_KEY_DOWN, 0, 0) {}
        session.onKeyPressed(GLFW.GLFW_KEY_ENTER, 0, 0) {}

        // ON に戻しても、OFF 中の確定は学習されていない
        MeltypeConfig.learningEnabled = true
        assertTrue(store.getLearnedCandidates("きょうは").isEmpty())
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
}
