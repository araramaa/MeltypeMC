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
 * 文の構成セグメント（日本語または英単語）
 */
data class SentenceSegment(
    val raw: String,
    val isEnglish: Boolean,
    val reading: String
)

/**
 * 複数セグメントからなるハイブリッド文の分割結果
 * 例: "kyouhadiamondwotoriniikou" -> "きょうは" + "diamond" + "をとりにいこう"
 */
data class MultiCompoundSplit(
    val segments: List<SentenceSegment>
) {
    /** 英単語の前後に自然なスペースを入れたプレビュー（例: "きょうは diamond をとりにいこう", "diamond をさがそう"） */
    val previewText: String get() = segments.mapIndexed { idx, seg ->
        if (seg.isEnglish) {
            val prefix = if (idx > 0) " " else ""
            val suffix = if (idx < segments.size - 1) " " else ""
            "$prefix${seg.reading}$suffix"
        } else {
            seg.reading
        }
    }.joinToString("").replace("  ", " ").trim()

    /** スペースなし連結（例: "きょうはdiamondをとりにいこう"） */
    val noSpaceText: String get() = segments.joinToString("") { it.reading }
}

/**
 * 英語の後に助詞や日本語が連続した場合（例: "diamondwosagasou"）や、
 * 日本語と英単語が助詞（てにをは等）を挟んで混在した一文（例: "kyouhadiamondwotoriniikou"）を
 * 英単語と日本語セグメントに自動分割するクラス
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
            "so", "my", "by", "us", "am", "it", "as", "if", "or", "you", "tou",
            "sou", "kou", "kai", "dai", "mai", "tai", "sai", "kan", "man", "tan",
            "ano", "ima", "asa", "sora", "hana", "kawa", "yama", "kiri", "ame",
            "mori", "kami", "machi", "michi", "kaze", "tsuki", "hoshi", "kane",
            "saga", "sagas", "date", "mine", "fine", "line", "sore", "are", "dare", "tame"
        )

        /**
         * 日本語の助詞・助動詞などのプレフィックス
         * 英単語の直後にこれら（てにをは等）が結合していることを厳密に判定
         * ※単一母音 'o' は douro や sono 等のローマ字末尾と誤爆するため含めない（助詞「を」は "wo"）
         */
        private val ParticlePrefixes = listOf(
            "wo", "ga", "wa", "ha", "no", "ni", "de", "to", "mo", "he",
            "te", "kara", "made", "yori", "dake", "demo", "nara", "da", "desu"
        )
    }

    /**
     * 文字列が「英単語 + 助詞/日本語ローマ字」の複合語であるかを判定し、分割結果を返します
     * @param raw スペースを含まない入力アルファベット列
     * @return 分割できた場合は CompoundSplit、分割できない場合は null
     */
    fun split(raw: String): CompoundSplit? {
        if (raw.length < 5) return null // 最低でも英単語3文字 + 助詞2文字（wo, ni, de 等）以上

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

    /**
     * 日本語と英単語が混在した文（例: "kyouhadiamondwotoriniikou"）を走査し、
     * 助詞（てにをは）を手がかりに文全体を日本語・英語セグメントに自動分割します。
     */
    fun splitSentence(raw: String): MultiCompoundSplit? {
        if (raw.length < 5) return null
        val lower = raw.lowercase()

        // 走査: 長い英単語から順に、かつ前後の境界条件を満たすものを探索
        for (len in minOf(lower.length, 20) downTo 3) {
            for (start in 0..(lower.length - len)) {
                val end = start + len
                val candidate = lower.substring(start, end)
                if (candidate in AmbiguousWords) continue

                // 英単語辞書に含まれるか
                if (!englishDetector.isWord(candidate)) continue

                val before = lower.substring(0, start)
                val after = lower.substring(end)

                // before と after の少なくともどちらかが存在すること（単語単体は通常処理へ）
                if (before.isEmpty() && after.isEmpty()) continue

                // 境界条件の検証:
                // before がある場合:
                // - ローマ字として有効であること
                // - 未完了子音（partial）がないこと
                // - 末尾が助詞（てにをは等）で終わっていること！
                if (before.isNotEmpty()) {
                    val beforeAnalysis = romajiDetector.analyze(before)
                    if (!beforeAnalysis.isValid || beforeAnalysis.partial.isNotEmpty()) continue
                    val endsWithParticle = ParticlePrefixes.any { before.endsWith(it) }
                    if (!endsWithParticle) continue
                }

                // after がある場合:
                // - 先頭が助詞（てにをは等）で始まっていること！
                // （※英単語の直後に日本語が続く場合は必ず助詞が来るため、助詞で始まることを要求して誤爆を完全に防止）
                if (after.isNotEmpty()) {
                    val startsWithParticle = ParticlePrefixes.any { after.startsWith(it) }
                    if (!startsWithParticle) continue
                }

                // セグメントの構築
                val segments = mutableListOf<SentenceSegment>()

                if (before.isNotEmpty()) {
                    val beforeOriginal = raw.substring(0, start)
                    segments.add(
                        SentenceSegment(
                            raw = beforeOriginal,
                            isEnglish = false,
                            reading = romajiDetector.toKanaLenient(before)
                        )
                    )
                }

                val wordOriginal = raw.substring(start, end)
                segments.add(
                    SentenceSegment(
                        raw = wordOriginal,
                        isEnglish = true,
                        reading = wordOriginal
                    )
                )

                if (after.isNotEmpty()) {
                    val afterOriginal = raw.substring(end)
                    // after の中にさらに英単語があるか再帰的に探索
                    val recursive = splitSentence(afterOriginal)
                    if (recursive != null) {
                        segments.addAll(recursive.segments)
                    } else {
                        segments.add(
                            SentenceSegment(
                                raw = afterOriginal,
                                isEnglish = false,
                                reading = romajiDetector.toKanaLenient(after)
                            )
                        )
                    }
                }

                return MultiCompoundSplit(segments)
            }
        }

        return null
    }
}
