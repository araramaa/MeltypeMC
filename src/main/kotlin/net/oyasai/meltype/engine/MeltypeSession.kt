package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.conversion.IConverter
import org.lwjgl.glfw.GLFW

/**
 * 1回のチャット・GUI入力セッション（入力バッファ・変換候補・状態遷移）を管理するクラス
 * 半角/全角キーによる「半角英数字入力モード」と「日本語入力モード」の切り替えをサポートします。
 */
class MeltypeSession(
    private val romajiDetector: RomajiDetector,
    private val englishDetector: EnglishDetector,
    private val scoreEngine: ScoreEngine,
    private val slashCommandGate: SlashCommandGate,
    private val converter: IConverter = GoogleCgiConverter()
) {

    private val lock = Any()

    /** 現在の入力モード（半角英数 / 日本語） */
    var currentInputMode: InputMode = MeltypeConfig.initialInputMode
        private set

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

    /** 半角/全角キー押下直後のバッククォート/チルダ文字入力をブロックするフラグ */
    private var skipNextGraveChar: Boolean = false

    /** 現在IME入力・変換中かどうか */
    val isComposing: Boolean get() = synchronized(lock) { rawBuffer.isNotEmpty() }

    /** 変換候補ウィンドウを表示中かどうか */
    val hasCandidates: Boolean get() = synchronized(lock) { candidates.isNotEmpty() }

    /**
     * 入力モードをトグル（半角英数 ↔ 日本語）
     */
    fun toggleInputMode(insertToChat: ((String) -> Unit)? = null): InputMode {
        synchronized(lock) {
            if (isComposing && insertToChat != null) {
                commitCurrent(insertToChat)
            } else {
                reset()
            }
            currentInputMode = currentInputMode.toggle()
            return currentInputMode
        }
    }

    /**
     * 入力モードを明示的に設定
     */
    fun setInputMode(mode: InputMode, insertToChat: ((String) -> Unit)? = null) {
        synchronized(lock) {
            if (currentInputMode != mode) {
                if (isComposing && insertToChat != null) {
                    commitCurrent(insertToChat)
                } else {
                    reset()
                }
                currentInputMode = mode
            }
        }
    }

    /**
     * 新しい画面を開いた際の初期化
     */
    fun resetForNewScreen() {
        synchronized(lock) {
            reset()
            if (!MeltypeConfig.rememberLastInputMode) {
                currentInputMode = MeltypeConfig.initialInputMode
            }
        }
    }

    /**
     * 半角/全角キー判定
     * - スキャンコード 41 (0x29): 日本語JIS 106/109キーボードの半角/全角キー
     * - GLFW_KEY_GRAVE_ACCENT (96): 同一位置のUSキー対応
     * - GLFW_KEY_WORLD_1 (161) / GLFW_KEY_WORLD_2 (162): 国際化キー対応
     */
    fun isHankakuZenkakuKey(keyCode: Int, scanCode: Int): Boolean {
        if (scanCode == 41) return true
        if (keyCode == GLFW.GLFW_KEY_GRAVE_ACCENT) return true
        if (keyCode == GLFW.GLFW_KEY_WORLD_1 || keyCode == GLFW.GLFW_KEY_WORLD_2) return true
        return false
    }

    /** 変換キー判定 (JIS スキャンコード 121 / 0x79) */
    fun isHenkanKey(scanCode: Int): Boolean = scanCode == 121

    /** 無変換キー判定 (JIS スキャンコード 123 / 0x7B) */
    fun isMuhenkanKey(scanCode: Int): Boolean = scanCode == 123

    /** カタカナ/ひらがなキー判定 (JIS スキャンコード 112 / 0x70) */
    fun isHiraganaKatakanaKey(scanCode: Int): Boolean = scanCode == 112

    /**
     * 文字入力イベント（charTyped）の処理
     * @return true の場合、このMODで入力を消費したためマイクラ標準の入力処理をキャンセルする
     */
    fun onCharTyped(c: Char, currentChatText: String, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // 半角/全角キー押下直後のバッククォート/チルダ文字等の誤入力を抑止
            if (skipNextGraveChar) {
                skipNextGraveChar = false
                if (c == '`' || c == '~' || c == '\u0000') {
                    return true
                }
            }

            // 半角英数字入力モード時は、一切介入せずMinecraft標準（直接入力）に任せる
            if (currentInputMode == InputMode.ENGLISH) {
                return false
            }

            // --- ここから下は日本語入力モード（InputMode.JAPANESE）の処理 ---

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
                // 既に変換候補が出ている状態で英字が入力された場合、直前の候補を確定して新しく入力を開始
                if (hasCandidates) {
                    commitCurrent(insertToChat)
                }

                rawBuffer.append(c.lowercaseChar())
                updatePreview()
                return true
            }

            // 4. 日本語入力中の句読点・記号（確定させずにバッファに保持して文の入力を継続）
            if (isComposing && (c == ',' || c == '.' || c == '?' || c == '!' || c == '~')) {
                val punctuation = when (c) {
                    ',' -> "、"
                    '.' -> "。"
                    '?' -> "？"
                    '!' -> "！"
                    '~' -> "〜"
                    else -> c.toString()
                }
                rawBuffer.append(punctuation)
                updatePreview()
                return true
            }

            // 5. 英字以外の記号や数字が入力された場合
            if (isComposing) {
                commitCurrent(insertToChat)
                insertToChat(c.toString())
                return true
            }

            return false
        }
    }

    /**
     * 特殊キー入力イベント（keyPressed）の処理
     * @return true の場合、このMODでキーを消費したためマイクラ標準のキー処理をキャンセルする
     */
    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // 1. 半角/全角キーによるモード切り替え（最優先判定）
            if (isHankakuZenkakuKey(keyCode, scanCode)) {
                skipNextGraveChar = true
                toggleInputMode(insertToChat)
                return true // キーを消費してチャット欄への誤入力を防ぐ
            }

            // 2. 変換キー / カタカナひらがなキー -> 日本語入力モード
            if (isHenkanKey(scanCode) || isHiraganaKatakanaKey(scanCode)) {
                setInputMode(InputMode.JAPANESE, insertToChat)
                return true
            }

            // 3. 無変換キー -> 半角英数字入力モード
            if (isMuhenkanKey(scanCode)) {
                setInputMode(InputMode.ENGLISH, insertToChat)
                return true
            }

            // 4. 半角英数字モードの場合は、その他のすべてのキーをバニラにパススルー（直接入力）
            if (currentInputMode == InputMode.ENGLISH) {
                return false
            }

            // --- ここから下は日本語入力モード（InputMode.JAPANESE）の処理 ---

            // コマンドモード中の場合、IMEキー処理はスキップ
            if (slashCommandGate.isCommandMode && !isComposing) {
                return false
            }

            if (!isComposing) return false

            when (keyCode) {
                // Space: 変換実行 / 次の候補へ（Shift+Space で文中に半角スペース追加）
                GLFW.GLFW_KEY_SPACE -> {
                    val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
                    if (shift) {
                        // Shift+Space: 変換ではなく半角スペースをバッファに追加（日英混在・長文入力用）
                        rawBuffer.append(' ')
                        updatePreview()
                        return true
                    }

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

    /** 入力状態をリセット（モードは維持） */
    fun reset() {
        synchronized(lock) {
            rawBuffer.clear()
            previewKana = ""
            candidates = emptyList()
            selectedCandidateIndex = 0
            skipNextGraveChar = false
        }
    }
}
