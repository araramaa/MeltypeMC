package net.oyasai.meltype.engine

import net.oyasai.meltype.conversion.ConversionLearningStore
import net.oyasai.meltype.conversion.IConverter
import java.util.concurrent.CompletableFuture

/**
 * 1回の変換の「計画」
 * @property reading 学習記録に使う読み
 * @property raw 最初に入力したアルファベット列
 * @property immediate 通信を待たずにすぐ表示する候補
 * @property final 変換結果が届いたあとに差し替える最終候補
 * @property keepSelection 最終候補に差し替えるとき、選択位置を維持するか（false なら先頭に戻す）
 */
data class ConversionPlan(
    val reading: String,
    val raw: String,
    val immediate: List<String>,
    val final: CompletableFuture<List<String>>,
    val keepSelection: Boolean = false
)

/**
 * 入力文字列から、変換の種類（ハイブリッド文 / 複合語 / 単一単語）を判定して候補の作り方を決めるクラス。
 * セッションの状態には触れず、結果を [ConversionPlan] として返すだけにしてあります。
 */
class ConversionPlanner(
    private val romajiDetector: RomajiDetector,
    private val scoreEngine: ScoreEngine,
    private val compoundWordSplitter: CompoundWordSplitter,
    private val converter: IConverter,
    private val learningStore: ConversionLearningStore
) {

    /**
     * @param raw 入力中のアルファベット列
     * @param previewKana 現在のプレビュー文字列（空なら raw を使う）
     */
    fun plan(raw: String, previewKana: String): ConversionPlan {
        val text = previewKana.ifEmpty { raw }

        // 1. スペース区切りハイブリッド文（例: "kyouha diamond wo sagasou"）
        if (raw.contains(' ')) return planHybridSentence(raw, text)

        // 2. 複合語・ハイブリッド文（例: "kyouhadiamondwotoriniikou" -> "今日はdiamondを取りに行こう"）
        compoundWordSplitter.splitSentence(raw)?.let { return planMultiCompound(it, raw) }

        // 3. 英単語＋助詞/日本語の複合語（例: "diamondwosagasou" -> "diamondを探そう"）
        compoundWordSplitter.split(raw)?.let { return planCompound(it, raw) }

        // 4. 単一単語
        return planSingleWord(raw, text)
    }

    /** 単一単語（英語寄りなら英語を第1候補、日本語寄りなら学習・かな・カタカナ・生英字の順） */
    private fun planSingleWord(raw: String, previewText: String): ConversionPlan {
        // 変換するときは、語尾の n を「ん」にする（例: harowin -> はろうぃん）
        val text = previewText.withFinalN()
        val isEnglishDominant = scoreEngine.isEnglishInput(raw)
        val initialKana = romajiDetector.toKanaFinal(raw)
        val initialKatakana = romajiDetector.toKatakana(initialKana)
        val kana = initialKana.ifEmpty { raw }
        val katakana = initialKatakana.takeIf { it.isNotEmpty() && it != initialKana }

        // 即座に応答できる初期候補（最初に入力した文字そのままも常に選べるようにする）
        val immediate = mutableListOf<String>()
        if (isEnglishDominant) {
            immediate.add(raw)
            if (kana != raw) immediate.add(kana)
            if (katakana != null && katakana != raw) immediate.add(katakana)
        } else {
            immediate.addAll(learningStore.getLearnedCandidates(text))
            immediate.addIfMissing(kana)
            if (katakana != null) immediate.addIfMissing(katakana)
            immediate.addIfMissing(raw)
        }

        // Google CGI / 辞書 による変換（最初に入力した英字・かなを必ず候補に残す）
        val convertTarget = if (isEnglishDominant && initialKana.isNotEmpty()) initialKana else text
        val final = converter.convertAsync(convertTarget).thenApply { resultList ->
            val full = mutableListOf<String>()
            if (isEnglishDominant) {
                full.add(raw) // 第1候補: 英語
                full.addAll(resultList) // 第2候補以降: 漢字・カタカナ等
                full.addIfMissing(kana)
                if (katakana != null) full.addIfMissing(katakana)
            } else {
                full.addAll(learningStore.rankCandidates(text, resultList))
                full.addIfMissing(kana)
                if (katakana != null) full.addIfMissing(katakana)
                full.addIfMissing(raw)
            }
            CandidateFinalizer.finalize(full, raw, kana) as List<String>
        }

        return ConversionPlan(text, raw, immediate.distinct().take(9), final, keepSelection = true)
    }

    /**
     * 複数セグメントからなるハイブリッド文（例: "kyouhadiamondwotoriniikou" -> "今日はdiamondを取りに行こう"）
     * 各日本語セグメントを変換し、英語セグメントと結合して候補を生成する
     */
    fun planMultiCompound(split: MultiCompoundSplit, raw: String): ConversionPlan {
        val immediate = listOf(split.noSpaceText, split.previewText, raw).distinct()

        val segmentFutures = split.segments.map { segment ->
            if (segment.isEnglish) {
                CompletableFuture.completedFuture(listOf(segment.raw))
            } else {
                converter.convertAsync(segment.reading).thenApply { res ->
                    res.ifEmpty { listOf(segment.reading) }
                }.exceptionally { listOf(segment.reading) }
            }
        }

        val final = CompletableFuture.allOf(*segmentFutures.toTypedArray()).thenApply {
            val segmentResults = segmentFutures.map { it.get() }
            // 第1候補: スペースなし結合（例: "今日はdiamondを取りに行こう"）
            val noSpace = split.segments.mapIndexed { idx, seg ->
                if (seg.isEnglish) seg.raw else segmentResults[idx].firstOrNull() ?: seg.reading
            }.joinToString("")
            // 第2候補: 英単語の周囲にスペースを入れた結合（例: "今日は diamond を取りに行こう"）
            val spaced = split.segments.mapIndexed { idx, seg ->
                if (seg.isEnglish) " ${seg.raw} " else segmentResults[idx].firstOrNull() ?: seg.reading
            }.joinToString("").replace("  ", " ").trim()

            val full = mutableListOf(noSpace, spaced)
            full.addIfMissing(split.previewText)
            full.addIfMissing(raw)
            CandidateFinalizer.finalize(full, raw, split.previewText) as List<String>
        }

        return ConversionPlan(split.previewText, raw, immediate, final)
    }

    /** 英単語＋助詞/日本語の複合語（例: "diamondwosagasou"）。英単語は保護し、日本語部分のみ変換して結合 */
    fun planCompound(compound: CompoundSplit, raw: String): ConversionPlan {
        val initialKana = "${compound.english}${compound.japaneseKana}"

        // 学習済み候補の事前適用（複合語全体および日本語部分）
        val learnedFull = learningStore.getLearnedCandidates(raw)
        val learnedJp = learningStore.getLearnedCandidates(compound.japaneseKana).map { "${compound.english}$it" }
        val immediate = (learnedFull + learnedJp + listOf(initialKana, raw)).distinct()

        val final = converter.convertAsync(compound.japaneseKana).thenApply { resultList ->
            val full = resultList.map { "${compound.english}$it" }
            val ranked = learningStore.rankCandidates(raw, full)
            CandidateFinalizer.finalize(ranked + listOf(initialKana), raw, initialKana) as List<String>
        }

        return ConversionPlan(raw, raw, immediate, final)
    }

    /** スペース区切りハイブリッド文（例: "kyouha diamond wo sagasou"）。英単語は保護し、日本語トークンのみ変換 */
    private fun planHybridSentence(rawSentence: String, text: String): ConversionPlan {
        val tokens = rawSentence.split(' ')

        // 即座に初期プレビュー候補を生成
        val immediateSentence = tokens.joinToString(" ") { token ->
            if (token.isEmpty()) ""
            else if (scoreEngine.isPureEnglishWord(token)) token
            else {
                val compound = compoundWordSplitter.split(token)
                if (compound != null) "${compound.english}${compound.japaneseKana}"
                else romajiDetector.toKanaLenient(token)
            }
        }

        // 各トークンごとに変換（複合語、英単語、日本語をそれぞれ最適処理）
        val tokenFutures = tokens.map { token ->
            val compound = compoundWordSplitter.split(token)
            if (compound != null) {
                converter.convertAsync(compound.japaneseKana).thenApply { res ->
                    listOf("${compound.english}${res.firstOrNull() ?: compound.japaneseKana}")
                }.exceptionally { listOf("${compound.english}${compound.japaneseKana}") }
            } else if (token.isEmpty() || scoreEngine.isPureEnglishWord(token) ||
                scoreEngine.evaluate(token, isFinal = true).verdict == Verdict.ENGLISH
            ) {
                CompletableFuture.completedFuture(listOf(token))
            } else {
                val kana = romajiDetector.toKanaLenient(token)
                converter.convertAsync(kana).thenApply { res ->
                    res.ifEmpty { listOf(kana) }
                }.exceptionally { listOf(kana) }
            }
        }

        val final = CompletableFuture.allOf(*tokenFutures.toTypedArray()).thenApply {
            val synthesized = tokenFutures.joinToString(" ") { it.get().firstOrNull() ?: "" }
            val full = mutableListOf(synthesized)
            full.addIfMissing(immediateSentence)
            CandidateFinalizer.finalize(full, rawSentence, immediateSentence) as List<String>
        }

        return ConversionPlan(text, rawSentence, listOf(immediateSentence, rawSentence), final)
    }
}

/** 同じ候補が無いときだけ末尾に追加する */
internal fun MutableList<String>.addIfMissing(item: String) {
    if (!contains(item)) add(item)
}
