package net.oyasai.meltype.engine

import net.oyasai.meltype.conversion.ConversionLearningStore

/**
 * 確定するときの文字を決める部品と、選んだ変換を学習として記録する部品。
 * 入力バッファなどの状態は持たず、必要なものを引数で受け取る。
 */
class CommitTextResolver(
    private val scoreEngine: ScoreEngine,
    private val compoundWordSplitter: CompoundWordSplitter,
    private val learningStore: ConversionLearningStore
) {

    /**
     * 候補を選ばずに（変換せずに）確定するときの文字を決める。
     * 英単語＋助詞の複合語・英語はそのまま、日本語はひらがなにして、語尾の n は「ん」にする。
     */
    fun resolveWithoutCandidates(raw: String, previewKana: String): String {
        if (previewKana.isEmpty()) return raw

        compoundWordSplitter.splitSentence(raw)?.let { return it.noSpaceText }
        compoundWordSplitter.split(raw)?.let { return "${it.english}${it.japaneseKana}" }
        if (scoreEngine.isEnglishInput(raw)) return raw
        return previewKana.withFinalN()
    }

    /**
     * 候補ウィンドウから選んで確定した変換を、学習として記録する。
     * 英単語＋日本語の複合語のときは、日本語の部分だけも別に記録する。
     */
    fun recordSelection(reading: String, raw: String, committedText: String) {
        if (reading.isEmpty()) return

        learningStore.recordSelection(reading, committedText)
        if (raw.isNotEmpty() && raw != reading) {
            learningStore.recordSelection(raw, committedText)
        }
        val compound = compoundWordSplitter.split(raw) ?: return
        if (!committedText.startsWith(compound.english)) return
        val japanesePart = committedText.removePrefix(compound.english)
        if (japanesePart.isNotEmpty()) {
            learningStore.recordSelection(compound.japaneseKana, japanesePart)
        }
    }
}
