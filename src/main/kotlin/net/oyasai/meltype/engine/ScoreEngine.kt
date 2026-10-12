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

    companion object {
        /**
         * 英語辞書に含まれているが、日本語日常語（ローマ字）として極めて頻出するため、
         * 単体入力時は日本語判定を最優先すべき単語リスト（例: sore=それ, are=あれ, dare=だれ, sake=酒 等）
         */
        val AmbiguousJapaneseWords = setOf(
            "are", "sore", "dare", "tame", "sake", "date", "mine", "fine", "line",
            "kome", "mono", "koto", "shite", "shita", "demo", "doko", "nani",
            "soko", "koko", "haka", "mori", "kami", "kaze", "tsuki", "hoshi",
            "machi", "michi", "kane", "ame", "asa", "hiru", "yoru", "hana",
            "kawa", "yama", "kiri", "sora", "ima", "ano", "kono", "sono",
            "ohayou", "arigatou", "you"
        )
    }

    /**
     * 日本語日常語と衝突しない、純粋な英単語であるかを判定（3文字以上かつ非曖昧語）
     */
    fun isPureEnglishWord(letters: String): Boolean {
        val lower = letters.lowercase()
        return lower.length >= 3 && englishDetector.isWord(lower) && lower !in AmbiguousJapaneseWords
    }

    /**
     * 入力文字列が英語（英単語または英字列）として扱われるべきかを判定
     * - 辞書に一致する純粋な英単語（3文字以上、例: diamond）
     * - ローマ字として無効な並び（子音連続など）を持つ文字列（例: modrinth, discord, minecraft, fabric 等）
     * - 大文字を含む英字列（例: Modrinth, Discord）
     */
    fun isEnglishInput(letters: String): Boolean {
        if (letters.isEmpty()) return false
        val lower = letters.lowercase()
        // 日本語日常語（sore, are, dare, ima 等）は日本語
        if (lower in AmbiguousJapaneseWords) return false
        // 1〜2文字の有効なローマ字（ha, he, to, no 等）は日本語
        if (lower.length <= 2 && romajiDetector.analyze(lower).isValid) return false

        // 1. 純粋な英単語（辞書一致）
        if (isPureEnglishWord(lower)) return true

        // 2. ローマ字として無効な並び（子音連続など、辞書外の英単語・固有名詞）
        val analysis = romajiDetector.analyze(lower)
        if (!analysis.isValid) return true

        // 3. 大文字を含んでいる（例: Modrinth, Fabric, Discord）
        if (letters.any { it in 'A'..'Z' }) return true

        return false
    }

    fun evaluate(letters: String, isFinal: Boolean = false): DetectionResult {
        if (letters.isEmpty()) {
            return DetectionResult(Verdict.UNDECIDED, 0, 0, "入力なし")
        }

        val normalized = RomajiDetector.readCRow(letters.lowercase())
        val isAmbiguousJapanese = normalized in AmbiguousJapaneseWords
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

            // ローマ字として綺麗に音節が完結しており（partialが空）
            if (analysis.partial.isEmpty()) {
                if (isAmbiguousJapanese) {
                    // 日英重複日常語（sore, are, dare, sake 等）は日本語として強力に加点
                    japaneseScore += 5
                } else if (normalized.length <= 2) {
                    // 1〜2文字の有効なローマ字音節（ha, he, to, no, me 等）は日本語（助詞・基本音節）として加点
                    japaneseScore += 3
                } else if (!englishDetector.isWord(letters)) {
                    if (normalized.length >= 5 && !englishDetector.isPrefix(letters)) {
                        japaneseScore += 4
                    } else if (normalized.length >= 3) {
                        // 3〜4文字の一般的な短語（ima, kore, sora, inu, neko 等）に対する加点
                        japaneseScore += 3
                    }
                }
            }
        }

        // 2. 英語辞書解析
        if (isFinal) {
            // 単語確定時（Space/Enter）:
            // 曖昧単語を除き、3文字以上の英単語のみ英語スコアを与える
            if (!isAmbiguousJapanese && letters.length >= 3 && englishDetector.isWord(letters)) {
                englishScore += 5
            }
        } else {
            // 入力中: 曖昧単語や2文字以下でローマ字として有効な場合は英語評価を抑制
            if (!isAmbiguousJapanese && letters.length >= 3) {
                englishScore += englishDetector.evaluate(letters)
            } else if (!analysis.isValid) {
                englishScore += englishDetector.evaluate(letters)
            }
        }

        // ★最重要: 3文字以上で英単語辞書に完全一致する場合、ローマ字偶然一致（例: diamond -> ぢあもんd）を排して英語を確定！
        // ※ただし日本語日常語（sore, are, dare, sake等）は日本語を優先
        if (!isAmbiguousJapanese && letters.length >= 3 && englishDetector.isWord(letters)) {
            // 日本語としての強い特徴（拗音・促音等）がなければ英語
            if (analysis.strongYouon == 0 && analysis.tsu == 0 && analysis.sokuon == 0) {
                return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore + 10, "英単語辞書完全一致")
            }
        }

        val threshold = MeltypeConfig.japaneseThreshold

        // 3. 判定ロジック
        if (!analysis.isValid) {
            return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "ローマ字として無効")
        }

        // 大文字を含む英字列（例: Modrinth, Fabric, Discord）は英語確定
        if (letters.any { it in 'A'..'Z' } && !isAmbiguousJapanese) {
            return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore + 10, "大文字を含む英単語")
        }

        // 日本語スコアが閾値を超え、かつ英語スコアとの差が閾値以上
        if (japaneseScore >= threshold && (japaneseScore - englishScore) >= threshold) {
            return DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "日本語スコア優勢")
        }

        // 英単語辞書に強く合致し、日本語スコアが低い（3文字以上の英単語に限定）
        if (englishScore >= 3 && japaneseScore < threshold && letters.length >= 3) {
            return DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "英単語辞書一致")
        }

        if (isFinal) {
            // 単語確定時（スペース押下時など）:
            // ローマ字として綺麗に完結している場合:
            if (analysis.isValid && analysis.partial.isEmpty()) {
                // 2文字以下（ha, he, to, no, me 等の助詞・単音節）または日本語日常語（sore, are, dare等）は確実に日本語！
                if (letters.length <= 2 || isAmbiguousJapanese) {
                    return DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "確定時: 助詞・日常ローマ字")
                }
                // 3文字以上で英単語辞書完全一致でない場合は日本語！
                if (!englishDetector.isWord(letters)) {
                    return DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "確定時: 有効なローマ字単語")
                }
            }

            return if (japaneseScore >= englishScore) {
                DetectionResult(Verdict.JAPANESE, japaneseScore, englishScore, "確定時: 日本語")
            } else {
                DetectionResult(Verdict.ENGLISH, japaneseScore, englishScore, "確定時: 英語")
            }
        }

        return DetectionResult(Verdict.UNDECIDED, japaneseScore, englishScore, "判定保留（入力継続）")
    }
}
