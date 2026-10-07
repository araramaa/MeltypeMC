package net.oyasai.meltype.conversion

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

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

    private val cache = ConcurrentHashMap<String, List<String>>()

    override fun convertAsync(reading: String): CompletableFuture<List<String>> {
        val trimmed = reading.trim()
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
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
                .thenApply { response ->
                    if (response.statusCode() == 200) {
                        val parsed = parseGoogleCgiResponse(response.body(), trimmed)
                        if (parsed.isNotEmpty()) {
                            cache[trimmed] = parsed
                            parsed
                        } else {
                            fallback.convertAsync(trimmed).join()
                        }
                    } else {
                        fallback.convertAsync(trimmed).join()
                    }
                }
                .exceptionally {
                    // 通信エラー時はローカル辞書へフォールバック
                    fallback.convertAsync(trimmed).join()
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
    private fun parseGoogleCgiResponse(json: String, original: String): List<String> {
        val segments = mutableListOf<Segment>()
        try {
            // [["読み", ["候補1", "候補2", ...]], ...] の全文節を走査
            val segmentPattern = Pattern.compile("\\[\\\"([^\\\"]+)\\\",\\s*\\[([^\\]]*)\\]\\]")
            val segmentMatcher = segmentPattern.matcher(json)
            while (segmentMatcher.find()) {
                val reading = segmentMatcher.group(1)
                val candBlock = segmentMatcher.group(2)

                val cands = mutableListOf<String>()
                val candPattern = Pattern.compile("\\\"([^\\\"]*)\\\"")
                val candMatcher = candPattern.matcher(candBlock)
                while (candMatcher.find()) {
                    cands.add(candMatcher.group(1))
                }

                if (cands.isEmpty()) {
                    cands.add(reading)
                }
                segments.add(Segment(reading, cands))
            }
        } catch (_: Exception) {
        }

        if (segments.isEmpty()) {
            return listOf(original)
        }

        // 1文節のみの場合: そのまま候補リストを返す
        if (segments.size == 1) {
            val list = segments[0].candidates.toMutableList()
            if (!list.contains(segments[0].reading)) list.add(segments[0].reading)
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
