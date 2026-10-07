package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.conversion.IConverter
import org.lwjgl.glfw.GLFW

/**
 * 1回のチャット・GUI入力セッション（入力バッファ・変換候補・状態遷移）を管理するクラス
 * 高い堅牢性・例外安全性・直しやすい構造を備えています。
 */
class MeltypeSession(
    private val romajiDetector: RomajiDetector,
    private val englishDetector: EnglishDetector,
    private val scoreEngine: ScoreEngine,
    private val slashCommandGate: SlashCommandGate,
    private val converter: IConverter = GoogleCgiConverter()
) {

    private val lock = Any()

    /** 現在入力中の生キーストローク（アルファベット列） */
    var rawBuffer: StringBuilder = StringBuilder()
        private set

    /** 現在のひらがな表示テキスト（下線プレビュー用） */
    var previewKana: String = ""
        private set

    /** 変換候補リスト（スレッドセーフにアクセス） */
    @Volatile
    var candidates: List<String> = emptyList()
        private set

    /** 現在選択中の候補インデックス (0-based) */
    var selectedCandidateIndex: Int = 0
        private set

    /** 現在IME入力・変換中かどうか */
    val isComposing: Boolean get() = synchronized(lock) { rawBuffer.isNotEmpty() }

    /** 変換候補ウィンドウを表示中かどうか */
    val hasCandidates: Boolean get() = synchronized(lock) { candidates.isNotEmpty() }

    /**
     * 文字入力イベント（charTyped）の処理
     * @return true の場合、このMODで入力を消費したためマイクラ標準の入力処理をキャンセルする
     */
    fun onCharTyped(c: Char, currentChatText: String, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // 1. スラッシュコマンド判定（最優先）
            if (slashCommandGate.shouldBypassIme(currentChatText, c)) {
                if (isComposing) {
                    flushAsIs(insertToChat)
                }
                return false // マイクラ標準処理に任せる
            }

            // 2. 空白（スペース）の処理
            if (c == ' ') {
                if (isComposing) {
                    // スペースキーは keyPressed 側で変換トリガーとして処理
                    return true
                }
                return false
            }

            // 3. 入力可能な英字またはハイフン
            if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
                // ★重要: 既に変換候補が出ている状態で英字が入力された場合、直前の単語を確定して新しく入力を開始
                if (hasCandidates) {
                    commitCurrent(insertToChat)
                }

                rawBuffer.append(c.lowercaseChar())
                updatePreview()
                return true
            }

            // 4. 英字以外の記号や数字が入力された場合
            if (isComposing) {
                // 候補が出ている状態での数字キーは keyPressed 側で選択済み
                commitCurrent(insertToChat)
                insertToChat(c.toString())
                return true
            }

            return false
        }
    }

    /**
     * 特殊キー入力イベント（keyPressed）の処理
     * @return true の場合、このMODでキーを消費した
     */
    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // コマンドモード中の場合、IMEキー処理はスキップ
            if (slashCommandGate.isCommandMode && !isComposing) {
                return false
            }

            if (!isComposing) return false

            when (keyCode) {
                // Space: 変換実行 / 次の候補へ（英語判定なら単語確定＋スペース）
                GLFW.GLFW_KEY_SPACE -> {
                    // 英語スコアが優勢な場合は、英単語確定＋半角スペースを挿入
                    val eval = scoreEngine.evaluate(rawBuffer.toString(), isFinal = true)
                    if (eval.verdict == Verdict.ENGLISH) {
                        commitCurrent(insertToChat)
                        insertToChat(" ")
                        return true
                    }

                    if (hasCandidates) {
                        val count = candidates.size
                        if (count > 0) {
                            selectedCandidateIndex = (selectedCandidateIndex + 1) % count
                        }
                    } else {
                        triggerConversion()
                    }
                    return true
                }

                // Enter: 確定
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commitCurrent(insertToChat)
                    return true
                }

                // Backspace: 1文字削除
                GLFW.GLFW_KEY_BACKSPACE -> {
                    if (hasCandidates) {
                        candidates = emptyList()
                        selectedCandidateIndex = 0
                        return true
                    }
                    if (rawBuffer.isNotEmpty()) {
                        rawBuffer.deleteCharAt(rawBuffer.length - 1)
                        updatePreview()
                        return true
                    }
                    return false
                }

                // Escape: 変換キャンセル
                GLFW.GLFW_KEY_ESCAPE -> {
                    reset()
                    return true
                }

                // Tab / Shift+Tab: 候補送り・戻し
                GLFW.GLFW_KEY_TAB -> {
                    if (hasCandidates) {
                        val count = candidates.size
                        if (count > 0) {
                            val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
                            selectedCandidateIndex = if (shift) {
                                if (selectedCandidateIndex - 1 < 0) count - 1 else selectedCandidateIndex - 1
                            } else {
                                (selectedCandidateIndex + 1) % count
                            }
                        }
                        return true
                    }
                }

                // 左右下矢印キー: 候補選択
                GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN -> {
                    if (hasCandidates && candidates.isNotEmpty()) {
                        selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
                        return true
                    }
                }
                GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> {
                    if (hasCandidates && candidates.isNotEmpty()) {
                        selectedCandidateIndex = if (selectedCandidateIndex - 1 < 0) candidates.size - 1 else selectedCandidateIndex - 1
                        return true
                    }
                }

                // 1〜9の数字キー (メインキー & テンキー): 候補のダイレクト選択
                in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 -> {
                    if (hasCandidates) {
                        return selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_1, insertToChat)
                    }
                }
                in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9 -> {
                    if (hasCandidates) {
                        return selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_KP_1, insertToChat)
                    }
                }
            }

            return false
        }
    }

    private fun selectCandidateByIndex(index: Int, insertToChat: (String) -> Unit): Boolean {
        if (hasCandidates && index in candidates.indices) {
            selectedCandidateIndex = index
            commitCurrent(insertToChat)
            return true
        }
        return false
    }

    /** 入力プレビューの更新 */
    private fun updatePreview() {
        val text = rawBuffer.toString()
        if (text.isEmpty()) {
            previewKana = ""
            candidates = emptyList()
            return
        }

        try {
            previewKana = romajiDetector.toKanaLenient(text)
        } catch (_: Throwable) {
            previewKana = text
        }
    }

    /** 変換リクエストの送信 */
    private fun triggerConversion() {
        val text = previewKana.ifEmpty { rawBuffer.toString() }
        val eval = try {
            scoreEngine.evaluate(rawBuffer.toString(), isFinal = true)
        } catch (_: Throwable) {
            DetectionResult(Verdict.UNDECIDED, 0, 0, "fallback")
        }

        // 英語スコアが優勢な場合は英字を第1候補に
        if (eval.verdict == Verdict.ENGLISH) {
            candidates = listOf(rawBuffer.toString())
            selectedCandidateIndex = 0
            return
        }

        // スペース押下時に即座に候補ウィンドウを出し、入力への応答性を最大化
        val immediateList = mutableListOf(text)
        if (!immediateList.contains(rawBuffer.toString())) {
            immediateList.add(rawBuffer.toString())
        }
        candidates = immediateList
        selectedCandidateIndex = 0

        // 日本語かな漢字変換を非同期実行
        converter.convertAsync(text).thenAccept { resultList ->
            synchronized(lock) {
                // 入力状態が変わっていなければ候補をセット
                if (rawBuffer.isNotEmpty()) {
                    val list = resultList.take(MeltypeConfig.maxCandidates).toMutableList()
                    if (!list.contains(text)) list.add(text)
                    if (!list.contains(rawBuffer.toString())) list.add(rawBuffer.toString())

                    candidates = list
                    if (selectedCandidateIndex !in candidates.indices) {
                        selectedCandidateIndex = 0
                    }
                }
            }
        }.exceptionally {
            // エラー時は既にセットした immediateList のままで安全
            null
        }
    }

    /** 現在選択中の候補（またはプレビュー文字列）を確定してチャット欄に挿入 */
    fun commitCurrent(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            val committedText = when {
                hasCandidates -> candidates.getOrNull(selectedCandidateIndex) ?: candidates.firstOrNull() ?: previewKana
                previewKana.isNotEmpty() -> {
                    val eval = try {
                        scoreEngine.evaluate(rawBuffer.toString(), isFinal = true)
                    } catch (_: Throwable) {
                        DetectionResult(Verdict.JAPANESE, 1, 0, "fallback")
                    }
                    if (eval.verdict == Verdict.ENGLISH) {
                        rawBuffer.toString()
                    } else {
                        previewKana
                    }
                }
                else -> rawBuffer.toString()
            }

            if (committedText.isNotEmpty()) {
                try {
                    insertToChat(committedText)
                } catch (_: Throwable) {
                }
            }
            reset()
        }
    }

    /** 生のアルファベットのまま確定して流し込む */
    private fun flushAsIs(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            if (rawBuffer.isNotEmpty()) {
                try {
                    insertToChat(rawBuffer.toString())
                } catch (_: Throwable) {
                }
            }
            reset()
        }
    }

    /** 入力状態をリセット */
    fun reset() {
        synchronized(lock) {
            rawBuffer.clear()
            previewKana = ""
            candidates = emptyList()
            selectedCandidateIndex = 0
        }
    }
}
