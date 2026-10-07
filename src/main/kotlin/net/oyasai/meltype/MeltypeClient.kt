package net.oyasai.meltype

import net.fabricmc.api.ClientModInitializer
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.engine.EnglishDetector
import net.oyasai.meltype.engine.MeltypeSession
import net.oyasai.meltype.engine.RomajiDetector
import net.oyasai.meltype.engine.ScoreEngine
import net.oyasai.meltype.engine.SlashCommandGate
import org.slf4j.LoggerFactory

/**
 * Meltype MC クライアントエントリポイント
 */
object MeltypeClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("MeltypeMC")

    lateinit var romajiDetector: RomajiDetector
        private set
    lateinit var englishDetector: EnglishDetector
        private set
    lateinit var scoreEngine: ScoreEngine
        private set
    lateinit var slashCommandGate: SlashCommandGate
        private set
    lateinit var session: MeltypeSession
        private set

    override fun onInitializeClient() {
        logger.info("[MeltypeMC] Initializing Meltype Japanese Input for Minecraft...")

        // 1. 各エンジンの初期化
        romajiDetector = RomajiDetector()
        englishDetector = EnglishDetector.loadFromResource("/dictionaries/english-words.txt")
        scoreEngine = ScoreEngine(romajiDetector, englishDetector)
        slashCommandGate = SlashCommandGate()

        // 2. セッションの構築
        session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashCommandGate,
            converter = GoogleCgiConverter()
        )

        logger.info("[MeltypeMC] Successfully initialized! Auto English/Japanese detection and '/' command mode are ready.")
    }
}
