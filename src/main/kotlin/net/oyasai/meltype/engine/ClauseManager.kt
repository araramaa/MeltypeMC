package net.oyasai.meltype.engine

/**
 * 文節伸縮（Shift + Left / Right）による部分変換の選択範囲と状態を管理するクラス。
 * 未確定入力バッファ内の文節長インデックス境界チェックと伸縮操作をカプセル化します。
 */
class ClauseManager {

    /** 現在の文節伸縮における選択文字数（アルファベット数）。0 または rawLength の場合は全文字対象 */
    var clauseLength: Int = 0
        internal set

    /** 部分変換中（文節が縮められている状態）かどうか */
    fun isPartial(rawLength: Int): Boolean = clauseLength in 1 until rawLength

    /** 文節を 1 文字縮める (Shift + Left) */
    fun shrink(rawLength: Int) {
        if (rawLength <= 1) return
        if (clauseLength <= 0 || clauseLength > rawLength) {
            clauseLength = rawLength - 1
        } else if (clauseLength > 1) {
            clauseLength--
        }
    }

    /**
     * 文節を 1 文字伸ばす (Shift + Right)
     * @return true の場合、最大文字数まで戻ったため通常変換に復帰すべき
     */
    fun expand(rawLength: Int): Boolean {
        if (rawLength <= 0) return false
        if (clauseLength in 1 until rawLength) {
            clauseLength++
            if (clauseLength == rawLength) {
                clauseLength = 0
                return true // 通常変換復帰
            }
        }
        return false
    }

    /** 状態をリセット（通常変換へ復帰） */
    fun reset() {
        clauseLength = 0
    }

    /**
     * 生文字列を（アクティブ文節部分, 残り未確定部分）に分割
     */
    fun split(raw: String): Pair<String, String> {
        return if (isPartial(raw.length)) {
            val active = raw.substring(0, clauseLength)
            val remainder = raw.substring(clauseLength)
            Pair(active, remainder)
        } else {
            Pair(raw, "")
        }
    }
}
