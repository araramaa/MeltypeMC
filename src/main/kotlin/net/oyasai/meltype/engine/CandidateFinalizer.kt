package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig

/**
 * 変換候補リストの整形・要素保証・候補数制限を担当するコンポーネント。
 * 最初に入力した生英字（raw）やひらがな（kana）が候補から脱落しないことを保証します。
 */
object CandidateFinalizer {

    /**
     * 候補リストに最初に入力した文字そのまま（rawCandidate）およびひらがな（kanaCandidate）が
     * 必ず含まれることを保証し、数字キー（1〜9）で直接選択可能な枠（最大7〜9件）に整える
     */
    fun finalize(
        fullList: List<String>,
        rawCandidate: String,
        kanaCandidate: String? = null,
        maxCandidatesConfig: Int = MeltypeConfig.maxCandidates
    ): MutableList<String> {
        val distinct = fullList.distinct().toMutableList()
        if (kanaCandidate != null && !distinct.contains(kanaCandidate)) {
            distinct.add(kanaCandidate)
        }
        if (!distinct.contains(rawCandidate)) {
            distinct.add(rawCandidate)
        }

        val maxCount = maxOf(maxCandidatesConfig, 7).coerceAtMost(9)
        // rawCandidate が maxCount 枠外にはみ出ないよう、末尾に必ず残す！
        if (!distinct.take(maxCount).contains(rawCandidate)) {
            distinct.remove(rawCandidate)
            distinct.add(minOf(maxCount - 1, distinct.size), rawCandidate)
        }

        return distinct.take(maxCount).toMutableList()
    }
}
