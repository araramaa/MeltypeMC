package net.oyasai.meltype.conversion

import net.oyasai.meltype.config.MeltypeConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Google 日本語入力 CGI API を利用した高精度かな漢字変換エンジン
 * エンドポイント: https://www.google.com/transliterate?langpair=ja-Hira|ja&text=...
 *
 * 通信に失敗したとき・結果が読めないとき・設定で OFF のときは、[fallback]（ローカル辞書）で変換する。
 * レスポンスの読み取りは [GoogleResponseParser] が行う。
 */
class GoogleCgiConverter(
    private val fallback: IConverter = LocalDictionaryConverter()
) : IConverter {

    private companion object {
        const val ENDPOINT = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text="
        const val USER_AGENT = "MeltypeMC/1.0"
        const val HTTP_OK = 200

        /** 接続のタイムアウト、リクエスト全体のタイムアウト（チャットの入力を待たせないよう、短くしてある） */
        val CONNECT_TIMEOUT: Duration = Duration.ofMillis(1000)
        val REQUEST_TIMEOUT: Duration = Duration.ofMillis(1200)

        /** 変換結果をおぼえておく数（古いものから消える） */
        const val MAX_CACHE_SIZE = 1000
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    /** 読み → 変換結果。最近使ったものを残す（LRU）、スレッドセーフなキャッシュ */
    private val cache: MutableMap<String, List<String>> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, List<String>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                return size > MAX_CACHE_SIZE
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

        cache[trimmed]?.let {
            return CompletableFuture.completedFuture(it)
        }

        return try {
            requestFromGoogle(trimmed)
                .thenCompose { response -> handleResponse(response, trimmed) }
                .exceptionallyCompose {
                    // 通信エラー時はローカル辞書へフォールバック
                    fallback.convertAsync(trimmed)
                }
        } catch (_: Throwable) {
            fallback.convertAsync(trimmed)
        }
    }

    /** Google へ、読みを送って、変換を頼む（非同期） */
    private fun requestFromGoogle(reading: String): CompletableFuture<HttpResponse<String>> {
        val encoded = URLEncoder.encode(reading, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT + encoded))
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    /** レスポンスを候補にして、キャッシュに入れる。使えない結果のときは、ローカル辞書の結果にする */
    private fun handleResponse(response: HttpResponse<String>, reading: String): CompletableFuture<List<String>> {
        if (response.statusCode() != HTTP_OK) return fallback.convertAsync(reading)

        val parsed = parseGoogleCgiResponse(response.body(), reading)
        if (parsed.isEmpty()) return fallback.convertAsync(reading)

        cache[reading] = parsed
        return CompletableFuture.completedFuture(parsed)
    }

    /** Google CGI のレスポンスを、候補のリストにする（実際の読み取りは [GoogleResponseParser]） */
    internal fun parseGoogleCgiResponse(json: String, original: String): List<String> =
        GoogleResponseParser.parse(json, original)
}
