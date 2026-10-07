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

    /**
     * Google CGI レスポンス形式: [["ひらがな", ["候補1", "候補2", ...]], ...]
     * 軽量に正規表現または文字列走査で候補配列を抽出
     */
    private fun parseGoogleCgiResponse(json: String, original: String): List<String> {
        val results = mutableListOf<String>()
        try {
            // "候補" の文字列を抽出する簡易パーサー
            val pattern = Pattern.compile("\\[\\\"([^\\\"]+)\\\",\\[([^\\]]+)\\]\\]")
            val matcher = pattern.matcher(json)
            if (matcher.find()) {
                val candidatePart = matcher.group(2)
                val candMatcher = Pattern.compile("\\\"([^\\\"]+)\\\"")
                val itemMatcher = candMatcher.matcher(candidatePart)
                while (itemMatcher.find()) {
                    results.add(itemMatcher.group(1))
                }
            }
        } catch (_: Exception) {
        }

        if (results.isEmpty()) {
            results.add(original)
        }
        return results
    }
}
