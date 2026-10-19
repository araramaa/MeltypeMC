package net.oyasai.meltype.engine

/**
 * 未確定入力バッファの画面表示用プレビューテキスト生成を担当するフォーマッター。
 * 単語・複合語・英語判定・文節伸縮表示を整形します。
 */
class PreviewFormatter(
    private val romajiDetector: RomajiDetector,
    private val scoreEngine: ScoreEngine,
    private val compoundWordSplitter: CompoundWordSplitter
) {
    /**
     * 通常入力時のプレビューテキスト生成
     * 英単語一致・英語優勢時は英字のままプレビュー（ぢあもんdに化けるのを防止）
     */
    fun formatPreview(text: String): String {
        if (text.isEmpty()) return ""

        // スペース区切りの場合
        if (text.contains(' ')) {
            return text.split(' ').joinToString(" ") { token ->
                if (token.isEmpty()) ""
                else if (scoreEngine.isPureEnglishWord(token)) token
                else {
                    val compound = compoundWordSplitter.split(token)
                    if (compound != null) "${compound.english} ${compound.japaneseKana}"
                    else romajiDetector.toKanaLenient(token)
                }
            }
        }

        // 複合語・ハイブリッド文判定（例: "kyouhadiamondwotoriniikou" -> "きょうはdiamondをとりにいこう"）
        val multiCompound = compoundWordSplitter.splitSentence(text)
        if (multiCompound != null) {
            return multiCompound.previewText
        }

        // 英単語＋日本語の複合語判定（例: "diamondwosagasou" -> "diamond をさがそう"）
        val compound = compoundWordSplitter.split(text)
        if (compound != null) {
            return "${compound.english} ${compound.japaneseKana}"
        }

        // 英語（英単語辞書一致、ローマ字として無効な英字列、大文字を含む英字列等）は英語のままプレビュー！
        if (scoreEngine.isEnglishInput(text)) {
            return text
        }

        return try {
            romajiDetector.toKanaLenient(text)
        } catch (_: Throwable) {
            text
        }
    }

    /**
     * 文節伸縮時のプレビューテキスト生成（例: "[今日]はdiamond"）
     * 選択中の文節と、残りの文字列（英語・複合語・かな対応）を分かりやすくブラケットで囲んで整形します。
     */
    fun formatClausePreview(selectedClause: String, remainderRaw: String): String {
        val remainderFormatted = formatPreview(remainderRaw)
        return "[$selectedClause]$remainderFormatted"
    }
}
