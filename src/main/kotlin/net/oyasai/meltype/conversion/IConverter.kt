package net.oyasai.meltype.conversion

import java.util.concurrent.CompletableFuture

/**
 * かな漢字変換エンジンのインターフェース
 */
interface IConverter {
    /**
     * ひらがな文字列を漢字・かな変換候補リストに変換します（非同期）
     * @param reading 読み仮名（ひらがな）
     * @return 変換候補のリスト（最有力候補が先頭）
     */
    fun convertAsync(reading: String): CompletableFuture<List<String>>
}
