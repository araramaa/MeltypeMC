package net.oyasai.meltype.engine

/**
 * 英単語＋日本語の自動分割結果
 * 例: "diamondwosagasou" -> english="diamond", japaneseRaw="wosagasou", japaneseKana="をさがそう"
 */
data class CompoundSplit(
    val english: String,
    val japaneseRaw: String,
    val japaneseKana: String
)

/**
 * 英語の後に助詞や日本語が連続した場合（例: "diamondwosagasou"）を
 * 英単語（"diamond"）と日本語（"wosagasou" -> "をさがそう"）に自動分割するクラス
 */
class CompoundWordSplitter(
    private val englishDetector: EnglishDetector,
    private val romajiDetector: RomajiDetector
) {

    companion object {
        /**
         * 短い英単語のうち、日本語ローマ字と重複して誤判定を起こしやすい単語を除外
         */
        private val AmbiguousWords = setOf(
            "an", "in", "on", "at", "to", "no", "is", "he", "me", "do", "go",
            "so", "my", "by", "us", "am", "it", "as", "if", "or",
            "ano", "ima", "asa", "sora", "hana", "kawa", "yama", "kiri", "ame",
            "mori", "kami", "machi", "michi", "kaze", "tsuki", "hoshi", "kane",
            "saga", "sagas" // "sagasou" 誤爆防止
        )

        /**
         * 日本語の助詞・助動詞などのプレフィックス
         * 英単語の直後にこれら（てにをは等）が結合していることを厳密に判定
         */
        private val ParticlePrefixes = listOf(
            "wo", "o", "ga", "wa", "ha", "no", "ni", "de", "to", "mo", "he",
            "te", "kara", "made", "yori", "dake", "demo", "nara", "da", "desu"
        )
    }

    /**
     * 文字列が「英単語 + 助詞/日本語ローマ字」の複合語であるかを判定し、分割結果を返します
     * @param raw スペースを含まない入力アルファベット列
     * @return 分割できた場合は CompoundSplit、分割できない場合は null
     */
    fun split(raw: String): CompoundSplit? {
        if (raw.length < 4) return null // 最低でも英単語3文字 + 日本語1文字以上

        val lower = raw.lowercase()

        // 最長一致で英単語プレフィックスを探索 (末尾-1文字から3文字目まで走査)
        for (len in lower.length - 1 downTo 3) {
            val prefix = lower.substring(0, len)
            if (prefix in AmbiguousWords) continue

            if (englishDetector.isWord(prefix)) {
                val remainder = lower.substring(len)

                // 助詞判定: remainder の先頭が助詞/助動詞で始まっているかチェック
                val startsWithParticle = ParticlePrefixes.any { remainder.startsWith(it) }
                if (!startsWithParticle) continue

                val analysis = romajiDetector.analyze(remainder)

                // 残りの文字列が有効なローマ字であること
                if (analysis.isValid) {
                    val kana = romajiDetector.toKanaLenient(remainder)
                    if (kana.isNotEmpty()) {
                        return CompoundSplit(
                            english = prefix,
                            japaneseRaw = remainder,
                            japaneseKana = kana
                        )
                    }
                }
            }
        }

        return null
    }
}
