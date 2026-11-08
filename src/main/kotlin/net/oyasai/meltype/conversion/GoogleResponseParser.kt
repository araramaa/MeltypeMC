package net.oyasai.meltype.conversion

import net.oyasai.meltype.engine.addIfMissing

/**
 * Google CGI のレスポンスを読んで、変換候補のリストにする部品。状態を持たない。
 *
 * レスポンスの形: [["ひらがな1", ["候補1", "候補2"]], ["ひらがな2", ["候補1", ...]], ...]
 * 単語1つだけでなく、助詞・助動詞を含む文章全体の複数文節を、つなげて文の候補にする。
 */
internal object GoogleResponseParser {

    /** 文全体の候補（文節ごとの第2候補以降を組み合わせたもの）の、最大の数 */
    private const val MAX_SENTENCE_CANDIDATES = 5

    /** 文節1つ分の、読みと候補 */
    private data class Segment(
        val reading: String,
        val candidates: List<String>
    )

    /**
     * レスポンスの文字列 [json] から、候補のリストを作る。
     * 読めない形式のときは、元の読み [original] だけのリストを返す。
     */
    fun parse(json: String, original: String): List<String> {
        val segments = readSegments(json)
        return when {
            segments.isEmpty() -> listOf(original)
            segments.size == 1 -> singleSegmentCandidates(segments[0])
            else -> sentenceCandidates(segments)
        }
    }

    /** [["読み", ["候補1", "候補2", ...]], ...] の全文節を読む（読めない形式なら空） */
    private fun readSegments(json: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        for (element in JsonArrayReader(json).readTopLevel().orEmpty()) {
            val pair = element as? List<*> ?: continue
            val reading = pair.getOrNull(0) as? String ?: continue
            if (reading.isEmpty()) continue
            val candidates = (pair.getOrNull(1) as? List<*>).orEmpty().filterIsInstance<String>()
            segments.add(Segment(reading, candidates.ifEmpty { listOf(reading) }))
        }
        return segments
    }

    /** 1文節のみの場合: そのまま候補リストを返す（読みのひらがなも、なければ足す） */
    private fun singleSegmentCandidates(segment: Segment): List<String> {
        val list = segment.candidates.toMutableList()
        list.addIfMissing(segment.reading)
        return list.distinct()
    }

    /** 複数文節（文章全体・助詞助動詞を含む文）の場合: 文全体の候補を合成する */
    private fun sentenceCandidates(segments: List<Segment>): List<String> {
        val results = mutableListOf<String>()

        // 1. 各文節の最有力候補 (index 0) の連結 -> 例: 「今日はダイヤを探そう」
        results.add(joinCandidatesAt(segments, 0))

        // 2. 各文節の第2候補などのバリエーションを合成
        val maxSegmentCandidates = segments.maxOfOrNull { it.candidates.size } ?: 1
        for (candidateIndex in 1 until maxSegmentCandidates) {
            val sentence = joinCandidatesAt(segments, candidateIndex)
            if (!results.contains(sentence)) {
                results.add(sentence)
            }
            if (results.size >= MAX_SENTENCE_CANDIDATES) break
        }

        // 3. 全文ひらがな
        val fullHiragana = segments.joinToString("") { it.reading }
        if (!results.contains(fullHiragana)) {
            results.add(fullHiragana)
        }

        return results.distinct()
    }

    /** 各文節の [index] 番目の候補をつなげる（その番号の候補がない文節は、最有力候補、それもなければ読み） */
    private fun joinCandidatesAt(segments: List<Segment>, index: Int): String =
        segments.joinToString("") { seg ->
            seg.candidates.getOrNull(index) ?: seg.candidates.firstOrNull() ?: seg.reading
        }
}
