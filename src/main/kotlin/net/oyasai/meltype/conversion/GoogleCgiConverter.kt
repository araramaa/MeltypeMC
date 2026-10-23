package net.oyasai.meltype.conversion

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.addIfMissing
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Google 日本語入力 CGI API を利用した高精度かな漢字変換エンジン
 * エンドポイント: https://www.google.com/transliterate?langpair=ja-Hira|ja&text=...
 */
class GoogleCgiConverter(
    private val fallback: IConverter = LocalDictionaryConverter()
) : IConverter {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1000))
        .build()

    private val maxCacheSize = 1000
    private val cache: MutableMap<String, List<String>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, List<String>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                return size > maxCacheSize
            }
        }
    )

    override fun convertAsync(reading: String): CompletableFuture<List<String>> {
        val trimmed = reading.trim()
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        // 設定で Google 変換をオフにしている場合は、通信せずローカル辞書のみで変換
        if (!MeltypeConfig.useGoogleCgi) {
            return fallback.convertAsync(trimmed)
        }

        // キャッシュチェック
        cache[trimmed]?.let {
            return CompletableFuture.completedFuture(it)
        }

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        val url = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=$encoded"

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(1200))
                .header("User-Agent", "MeltypeMC/1.0")
                .GET()
                .build()

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose { response ->
                    if (response.statusCode() == 200) {
                        val parsed = parseGoogleCgiResponse(response.body(), trimmed)
                        if (parsed.isNotEmpty()) {
                            cache[trimmed] = parsed
                            CompletableFuture.completedFuture(parsed)
                        } else {
                            fallback.convertAsync(trimmed)
                        }
                    } else {
                        fallback.convertAsync(trimmed)
                    }
                }
                .exceptionallyCompose {
                    // 通信エラー時はローカル辞書へフォールバック
                    fallback.convertAsync(trimmed)
                }
        } catch (_: Throwable) {
            fallback.convertAsync(trimmed)
        }
    }

    private data class Segment(
        val reading: String,
        val candidates: List<String>
    )

    /**
     * Google CGI レスポンス形式: [["ひらがな1", ["候補1", "候補2"]], ["ひらがな2", ["候補1", ...]], ...]
     * 単語単体だけでなく、助詞・助動詞を含む文章全体の複数文節を一括結合して高精度な文候補を生成
     */
    internal fun parseGoogleCgiResponse(json: String, original: String): List<String> {
        val segments = mutableListOf<Segment>()
        // [["読み", ["候補1", "候補2", ...]], ...] の全文節を走査（読めない形式なら空のまま）
        for (element in JsonArrayReader(json).readTopLevel().orEmpty()) {
            val pair = element as? List<*> ?: continue
            val reading = pair.getOrNull(0) as? String ?: continue
            if (reading.isEmpty()) continue
            val cands = (pair.getOrNull(1) as? List<*>).orEmpty().filterIsInstance<String>().toMutableList()

            if (cands.isEmpty()) {
                cands.add(reading)
            }
            segments.add(Segment(reading, cands))
        }

        if (segments.isEmpty()) {
            return listOf(original)
        }

        // 1文節のみの場合: そのまま候補リストを返す
        if (segments.size == 1) {
            val list = segments[0].candidates.toMutableList()
            list.addIfMissing(segments[0].reading)
            return list.distinct()
        }

        // 複数文節（文章全体・助詞助動詞を含む文）の場合: 全文を合成
        val results = mutableListOf<String>()

        // 1. 各文節の最有力候補 (index 0) の連結 -> 例: 「今日はダイヤを探そう」
        val bestSentence = segments.joinToString("") { it.candidates.firstOrNull() ?: it.reading }
        results.add(bestSentence)

        // 2. 各文節の第2候補などのバリエーションを合成
        val maxSegmentCands = segments.maxOfOrNull { it.candidates.size } ?: 1
        for (candIdx in 1 until maxSegmentCands) {
            val sentence = segments.joinToString("") { seg ->
                seg.candidates.getOrNull(candIdx) ?: seg.candidates.firstOrNull() ?: seg.reading
            }
            if (!results.contains(sentence)) {
                results.add(sentence)
            }
            if (results.size >= 5) break
        }

        // 3. 全文ひらがな
        val fullHiragana = segments.joinToString("") { it.reading }
        if (!results.contains(fullHiragana)) {
            results.add(fullHiragana)
        }

        return results.distinct()
    }
}

/**
 * Google CGI のレスポンス（文字列と配列だけからなる JSON）を、入れ子の List と String に読み込む小さな読み取り器。
 * 候補に「"」「]」が含まれる場合や、\uXXXX などのエスケープにも対応する。
 * 数値・null など文字列と配列以外の値は読み飛ばし、形式が壊れている場合は null を返す。
 */
private class JsonArrayReader(private val json: String) {
    private var pos = 0

    /** 最初の '[' から始まる配列を読む（前置きの文字があっても読めるよう、そこまでは読み飛ばす） */
    fun readTopLevel(): List<Any>? = try {
        pos = json.indexOf('[')
        if (pos < 0) null else readArray()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readArray(): List<Any> {
        expect('[')
        val items = mutableListOf<Any>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return items
        }
        while (true) {
            skipWhitespace()
            readValue()?.let { items.add(it) }
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return items
                else -> throw IllegalArgumentException("配列の区切りが不正")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '[' -> readArray()
        '"' -> readString()
        else -> {
            // 数値・true/false/null などは読み飛ばす
            val start = pos
            while (pos < json.length && json[pos] != ',' && json[pos] != ']' && !json[pos].isWhitespace()) pos++
            if (pos == start) throw IllegalArgumentException("値がない")
            null
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > json.length) throw IllegalArgumentException("\\u エスケープが途中で終わっている")
                        val code = json.substring(pos, pos + 4).toIntOrNull(16)
                            ?: throw IllegalArgumentException("\\u エスケープが不正")
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> throw IllegalArgumentException("不明なエスケープ")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun skipWhitespace() {
        while (pos < json.length && json[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < json.length) json[pos] else throw IllegalArgumentException("途中で終わっている")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) throw IllegalArgumentException("'$c' が必要")
    }
}
