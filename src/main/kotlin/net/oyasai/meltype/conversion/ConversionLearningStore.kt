package net.oyasai.meltype.conversion

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 学習済み候補の統計データ（確定回数、最終確定日時）
 */
data class LearnedCandidate(
    val candidate: String,
    var count: Int = 1,
    var lastUsed: Long = System.currentTimeMillis()
)

/**
 * かな漢字変換の学習エンジン
 * ユーザーが確定した候補の頻度・直近度を自動記録し、次回以降の変換時に最有力候補としてトップに配置します。
 * .minecraft/config/meltype/learned_words.txt に永続保存されます。
 */
class ConversionLearningStore(
    val storageFile: File? = getDefaultStorageFile()
) {

    // reading -> (candidate -> LearnedCandidate)
    private val memoryStore = ConcurrentHashMap<String, ConcurrentHashMap<String, LearnedCandidate>>()

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Meltype-LearningIO").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var dirty = false

    init {
        load()
    }

    companion object {
        /**
         * デフォルトの学習データ保存先ファイルパスを取得
         * Fabric 環境では .minecraft/config/meltype/learned_words.txt
         * テスト環境等ではフォールバック先パス
         */
        fun getDefaultStorageFile(): File {
            return try {
                val fabricClass = Class.forName("net.fabricmc.loader.api.FabricLoader")
                val getInstance = fabricClass.getMethod("getInstance")
                val loader = getInstance.invoke(null)
                val getConfigDir = fabricClass.getMethod("getConfigDir")
                val configPath = getConfigDir.invoke(loader) as java.nio.file.Path
                configPath.resolve("meltype").resolve("learned_words.txt").toFile()
            } catch (_: Throwable) {
                File("build/tmp/meltype/learned_words.txt")
            }
        }
    }

    /**
     * 読みに対する候補リストを学習履歴に基づいて再ランク付け（最有力候補を先頭へ）
     */
    fun rankCandidates(reading: String, defaultCandidates: List<String>): List<String> {
        val candidateMap = memoryStore[reading]
        if (candidateMap.isNullOrEmpty()) {
            return defaultCandidates
        }

        // カウント降順、同数の場合は最終使用日時降順
        val learnedSorted = candidateMap.values
            .sortedWith(compareByDescending<LearnedCandidate> { it.count }.thenByDescending { it.lastUsed })
            .map { it.candidate }

        val result = mutableListOf<String>()
        // 1. まず学習済み候補を優先度順に追加
        for (c in learnedSorted) {
            if (!result.contains(c)) {
                result.add(c)
            }
        }
        // 2. 残りのデフォルト変換候補を追加
        for (c in defaultCandidates) {
            if (!result.contains(c)) {
                result.add(c)
            }
        }
        return result
    }

    /**
     * 特定の読みに対して学習済みの候補一覧を取得
     */
    fun getLearnedCandidates(reading: String): List<String> {
        val candidateMap = memoryStore[reading] ?: return emptyList()
        return candidateMap.values
            .sortedWith(compareByDescending<LearnedCandidate> { it.count }.thenByDescending { it.lastUsed })
            .map { it.candidate }
    }

    /**
     * ユーザーによる候補選択を学習（確定回数を加算し、最終使用時刻を更新）
     */
    fun recordSelection(reading: String, candidate: String) {
        val trimmedReading = reading.trim()
        val trimmedCandidate = candidate.trim()
        if (trimmedReading.isEmpty() || trimmedCandidate.isEmpty()) return

        val candidateMap = memoryStore.computeIfAbsent(trimmedReading) { ConcurrentHashMap() }
        val stats = candidateMap.computeIfAbsent(trimmedCandidate) {
            LearnedCandidate(trimmedCandidate, 0, 0)
        }
        stats.count += 1
        stats.lastUsed = System.currentTimeMillis()

        saveAsync()
    }

    /**
     * ファイルからの学習データ読み込み
     */
    fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return

        try {
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split('\t')
                    if (parts.size >= 2) {
                        val reading = parts[0]
                        val candidate = parts[1]
                        val count = parts.getOrNull(2)?.toIntOrNull() ?: 1
                        val lastUsed = parts.getOrNull(3)?.toLongOrNull() ?: System.currentTimeMillis()

                        val candidateMap = memoryStore.computeIfAbsent(reading) { ConcurrentHashMap() }
                        candidateMap[candidate] = LearnedCandidate(candidate, count, lastUsed)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * ファイルへの同期保存
     */
    fun save() {
        val file = storageFile ?: return
        try {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("# Meltype MC Conversion Learning Dictionary\n")
                writer.write("# reading\tcandidate\tcount\tlastUsed\n")
                for ((reading, candidateMap) in memoryStore) {
                    for ((candidate, stats) in candidateMap) {
                        writer.write("$reading\t$candidate\t${stats.count}\t${stats.lastUsed}\n")
                    }
                }
            }

            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Throwable) {
                tempFile.renameTo(file)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 非同期で学習データをディスクに保存（ゲームの描画やメインスレッドをブロックしない）
     */
    fun saveAsync() {
        dirty = true
        ioExecutor.submit {
            if (dirty) {
                dirty = false
                save()
            }
        }
    }

    /**
     * メモリ内の学習データをクリア
     */
    fun clear() {
        memoryStore.clear()
        saveAsync()
    }
}
