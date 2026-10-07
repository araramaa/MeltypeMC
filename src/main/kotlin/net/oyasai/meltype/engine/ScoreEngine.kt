package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig

enum class Verdict {
    /** 日本語（かな・漢字変換対象）として確定 */
    JAPANESE,
    /** 英単語（そのまま英字入力）として確定 */
    ENGLISH,
    /** 判定途中（次のキーストロークを待つ） */
    UNDECIDED,
    /** 不明（デフォルト英語） */
    UNKNOWN
}

data class DetectionResult(
    val verdict: Verdict,
    val japaneseScore: Int,
    val englishScore: Int,
    val reason: String
)

/**
 * Meltype の ScoreEngine を移植した判定クラス
 * 入力されたキー列が「ローマ字（日本語）」か「英単語（英語）」かをリアルタイムにスコアリング判定します。
 */
class ScoreEngine(
    private val romajiDetector: RomajiDetector,
    private val englishDetector: EnglishDetector
) {

    fun evaluate(letters: String, isFinal: Boolean = false): DetectionResult {
        if (letters.isEmpty()) {
            return DetectionResult(Verdict.UNDECIDED, 0, 0, "入力なし")
        }

        val normalized = RomajiDetector.readCRow(letters.lowercase())
        var japaneseScore = 0
        var englishScore = 0

        // 明らかな英字キー（q, x, v, l 単体）は即英語
        if (normalized.length == 1 && normalized in listOf("l", "q", "v", "x")) {
            return DetectionResult(Verdict.ENGLISH, 0, 5, "英字専用キー")
        }

        // 1. ローマ字解析
        val analysis = romajiDetector.analyze(normalized)
        if (!analysis.isValid) {
            // ローマ字として成立しない並びなら英語へ傾ける
            englishScore += 5
        } else {
            // 日本語特有の特徴があれば加点
            if (analysis.strongYouon > 0) japaneseScore += 3 // kya, ryo など
            if (analysis.tsu > 0) japaneseScore += 3         // tsu
            if (analysis.sokuon > 0) japaneseScore += 2      // っ (kk, tt 等)
            if (analysis.longVowels > 0) japaneseScore += 1  // ou, uu
            if (analysis.tokens.isNotEmpty()) japaneseScore += analysis.tokens.size

            // 5文字以上でローマ字として完全に読め、英語辞書にない場合は日本語
            if (normalized.length >= 5 && analysis.partial.isEmpty() && !englishDetector.isPrefix(letters)) {
                japaneseScore += 4
            }
        }

        // 2. 英語辞書解析
        englishScore += englishDetector.evaluate(letters)

        val threshold = MeltypeConfig.japaneseThreshold

        // 3. 判定ロジック
        if (!analysis.isValid) {
            return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "ローマ字として無効")
        }

        // 日本語スコアが閾値を超え、かつ英語スコアとの差が閾値以上
        if (japaneseScore >= threshold && (japaneseScore - englishScore) >= threshold) {
            return DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "日本語スコア優勢")
        }

        // 英単語辞書に強く合致し、日本語スコアが低い
        if (englishScore >= 3 && japaneseScore < threshold && letters.length >= 2) {
            return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "英単語辞書一致")
        }

        if (isFinal) {
            return if (japaneseScore >= englishScore) {
                DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "確定時: 日本語")
            } else {
                DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "確定時: 英語")
            }
        }

        return DetectionResult(Verdict.UNDECIDED, japaneseScore, englishScore, "判定保留（入力継続）")
    }
}
