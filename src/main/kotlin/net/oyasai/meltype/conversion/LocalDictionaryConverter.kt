package net.oyasai.meltype.conversion

import java.util.concurrent.CompletableFuture

/**
 * オフライン時や通信失敗時に使われるローカルフォールバック変換器
 * ひらがな・カタカナ・基本候補を返します
 */
class LocalDictionaryConverter : IConverter {

    companion object {
        // ひらがな -> カタカナ変換
        fun toKatakana(hiragana: String): String {
            val sb = StringBuilder()
            for (c in hiragana) {
                if (c in '\u3041'..'\u3096') {
                    sb.append((c.code + 0x60).toChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        // 基本辞書（挨拶やよく使う語句）
        private val CommonDict = mapOf(
            "こんにちは" to listOf("こんにちは", "今日は"),
            "ありがとう" to listOf("ありがとう", "有難う"),
            "よろしく" to listOf("よろしく", "宜しく"),
            "はい" to listOf("はい", "拝"),
            "いいえ" to listOf("いいえ"),
            "おつかれ" to listOf("お疲れ", "おつかれ"),
            "こんばんわ" to listOf("こんばんは", "今晩は"),
            "おはよう" to listOf("おはよう", "お早う"),
            "すみません" to listOf("すみません", "済みません"),
            "だいや" to listOf("ダイヤ", "ダイヤモンド"),
            "てつ" to listOf("鉄", "てつ"),
            "きん" to listOf("金", "きん"),
            "いし" to listOf("石", "いし"),
            "き" to listOf("木", "気", "き"),
            "拠点" to listOf("拠点", "きょてん"),
            "きょうは" to listOf("今日は", "きょうは"),
            "さがそう" to listOf("探そう", "さがそう"),
            "をさがそう" to listOf("を探そう", "をさがそう"),
            "ほりにいこう" to listOf("掘りに行こう", "ほりに行こう"),
            "をほりにいこう" to listOf("を掘りに行こう", "をほりにいこう"),
            "を" to listOf("を"),
            "が" to listOf("が"),
            "は" to listOf("は"),
            "の" to listOf("の"),
            "に" to listOf("に"),
            "で" to listOf("で"),
            "と" to listOf("と")
        )
    }

    override fun convertAsync(reading: String): CompletableFuture<List<String>> {
        val candidates = mutableListOf<String>()

        // 辞書マッチ
        CommonDict[reading]?.let { candidates.addAll(it) }

        // ひらがな・カタカナ候補
        if (!candidates.contains(reading)) {
            candidates.add(reading)
        }
        val katakana = toKatakana(reading)
        if (!candidates.contains(katakana)) {
            candidates.add(katakana)
        }

        return CompletableFuture.completedFuture(candidates)
    }
}
