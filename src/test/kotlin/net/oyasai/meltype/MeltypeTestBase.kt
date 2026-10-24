package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.ConversionLearningStore
import net.oyasai.meltype.conversion.IConverter
import net.oyasai.meltype.engine.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * すべてのテストに共通の準備と、よく使う部品。
 * テストは本物の Google 通信と、共有の学習ファイルを使わないようにしてある。
 */
abstract class MeltypeTestBase {

    protected lateinit var romajiDetector: RomajiDetector
    protected lateinit var englishDetector: EnglishDetector
    protected lateinit var scoreEngine: ScoreEngine
    protected lateinit var slashGate: SlashCommandGate

    // テスト中に変更する設定値の元の値（AfterEach で戻す）
    protected var originalUseGoogleCgi = true
    protected var originalLearningEnabled = true

    @BeforeEach
    fun setUp() {
        MeltypeConfig.enabled = true
        MeltypeConfig.initialInputMode = InputMode.DIRECT
        MeltypeConfig.rememberLastInputMode = true

        // 実際のGoogle通信と共有の学習ファイルに依存しないようにする
        originalUseGoogleCgi = MeltypeConfig.useGoogleCgi
        originalLearningEnabled = MeltypeConfig.learningEnabled
        MeltypeConfig.useGoogleCgi = false
        MeltypeConfig.learningEnabled = true

        romajiDetector = RomajiDetector()
        englishDetector = EnglishDetector.loadFromResource("/dictionaries/english-words.txt")
        scoreEngine = ScoreEngine(romajiDetector, englishDetector)
        slashGate = SlashCommandGate()
    }

    @AfterEach
    fun tearDown() {
        MeltypeConfig.useGoogleCgi = originalUseGoogleCgi
        MeltypeConfig.learningEnabled = originalLearningEnabled
    }

    /** テストごとの一時ファイルを使う学習ストア（共有の学習ファイルを使わない） */
    protected fun newTempLearningStore() = ConversionLearningStore(
        java.io.File.createTempFile("meltype_test_learning", ".txt").also { it.deleteOnExit() })

    /** HYBRID（日本語変換）モードで始まるセッションを作る */
    protected fun newHybridSession(converter: IConverter): MeltypeSession {
        val s = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashGate,
            converter = converter,
            learningStore = newTempLearningStore()
        )
        s.toggleInputMode() // HYBRID
        return s
    }

    /** 読みをそのまま「漢[読み]」として返す変換器（変換結果がどの読みから来たかを確かめるため） */
    protected class EchoConverter : IConverter {
        override fun convertAsync(reading: String) =
            java.util.concurrent.CompletableFuture.completedFuture(listOf("漢[$reading]"))
    }

    /**
     * 結果が遅れて届く変換器（手動で complete するまで結果を返さない）
     */
    protected class DelayedConverter : net.oyasai.meltype.conversion.IConverter {
        val pending = mutableListOf<java.util.concurrent.CompletableFuture<List<String>>>()
        override fun convertAsync(reading: String): java.util.concurrent.CompletableFuture<List<String>> {
            val f = java.util.concurrent.CompletableFuture<List<String>>()
            pending.add(f)
            return f
        }
    }

}