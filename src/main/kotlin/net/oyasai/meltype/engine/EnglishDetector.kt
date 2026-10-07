package net.oyasai.meltype.engine

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 英単語の完全一致および接頭辞（Prefix）判定を行うクラス
 * Meltype の EnglishDetector に相当
 */
class EnglishDetector(words: Collection<String>) {

    private val wordSet: Set<String>
    private val prefixSet: Set<String>

    init {
        val wSet = mutableSetOf<String>()
        val pSet = mutableSetOf<String>()

        for (raw in words) {
            val w = raw.trim().lowercase()
            if (w.isEmpty() || w.startsWith("#")) continue
            wSet.add(w)
            for (len in 1..w.length) {
                pSet.add(w.substring(0, len))
            }
        }

        wordSet = wSet
        prefixSet = pSet
    }

    /** 単語が英単語辞書に含まれるか */
    fun isWord(word: String): Boolean = wordSet.contains(word.lowercase())

    /** 単語が英単語の接頭辞になりうるか */
    fun isPrefix(prefix: String): Boolean = prefixSet.contains(prefix.lowercase())

    /** 単語の英語らしさスコアを算出 */
    fun evaluate(letters: String): Int {
        val lower = letters.lowercase()
        return when {
            wordSet.contains(lower) -> 5
            prefixSet.contains(lower) && lower.length >= 3 -> 3
            prefixSet.contains(lower) -> 1
            else -> 0
        }
    }

    companion object {
        fun loadFromResource(resourcePath: String): EnglishDetector {
            val words = mutableListOf<String>()
            val stream = EnglishDetector::class.java.getResourceAsStream(resourcePath)
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            words.add(trimmed)
                        }
                    }
                }
            }
            return EnglishDetector(words)
        }
    }
}
