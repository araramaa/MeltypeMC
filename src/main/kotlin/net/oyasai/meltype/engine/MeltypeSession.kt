package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.conversion.IConverter
import org.lwjgl.glfw.GLFW

/**
 * 1回のチャット・GUI入力セッション（入力バッファ・変換候補・状態遷移）を管理するクラス
 * ［半角/全角キー不要］の自動英日ハイブリッド入力システムを実装しています。
 */
class MeltypeSession(
    private val romajiDetector: RomajiDetector,
    private val englishDetector: EnglishDetector,
    private val scoreEngine: ScoreEngine,
    private val slashCommandGate: SlashCommandGate,
    private val converter: IConverter = GoogleCgiConverter(),
    private val compoundWordSplitter: CompoundWordSplitter = CompoundWordSplitter(englishDetector, romajiDetector)
) {

    private val lock = Any()

    /** 現在の入力モード（HYBRID: 自動英日ハイブリッド / DIRECT: バニラ直接入力） */
    var currentInputMode: InputMode = MeltypeConfig.initialInputMode
        private set

    /** 現在入力中の生キーストローク（アルファベット列） */
    var rawBuffer: StringBuilder = StringBuilder()
        private set

    /** 現在のプレビューテキスト（下線表示用: 日本語ならひらがな、英語なら英単語そのまま） */
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
     * 入力モードをトグル（HYBRID ↔ DIRECT）
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

    companion object {
        /** JIS 半角/全角キースキャンコード (10進: 41, 16進: 0x29) */
        const val SCAN_HANKAKU_ZENKAKU_DEC = 41
        const val SCAN_HANKAKU_ZENKAKU_HEX = 0x29

        /** JIS 変換キースキャンコード (121) */
        const val SCAN_HENKAN = 121

        /** JIS 無変換キースキャンコード (123) */
        const val SCAN_MUHENKAN = 123
    }

    /**
     * 半角/全角キー判定 (JIS スキャンコード 41 / 0x29 / GLFW_KEY_GRAVE_ACCENT / WORLDキー / F12)
     */
    fun isHankakuZenkakuKey(keyCode: Int, scanCode: Int): Boolean {
        if (scanCode == SCAN_HANKAKU_ZENKAKU_DEC || scanCode == SCAN_HANKAKU_ZENKAKU_HEX) return true
        if (keyCode == GLFW.GLFW_KEY_GRAVE_ACCENT) return true
        if (keyCode == GLFW.GLFW_KEY_WORLD_1 || keyCode == GLFW.GLFW_KEY_WORLD_2) return true
        if (keyCode == GLFW.GLFW_KEY_F12) return true
        return false
    }

    /** 変換キー判定 (JIS スキャンコード 121) */
    fun isHenkanKey(scanCode: Int): Boolean = scanCode == SCAN_HENKAN

    /** 無変換キー判定 (JIS スキャンコード 123) */
    fun isMuhenkanKey(scanCode: Int): Boolean = scanCode == SCAN_MUHENKAN

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
                if (c == '`' || c == '~' || c == '｀' || c == '\u0000') {
                    return true
                }
            }

            // 半角/全角キー（バッククォート）が charTyped として飛んできた場合の完全救済（OSにキーイベントが吸われた環境対応）
            if (c == '`' || c == '~' || c == '｀') {
                toggleInputMode(insertToChat)
                return true
            }

            // 直接入力固定モード（DIRECT）時は、一切介入せずMinecraft標準に任せる
            if (currentInputMode == InputMode.DIRECT) {
                return false
            }

            // --- ここから下はハイブリッド自動判別モード（HYBRID）の処理 ---

            // 1. スラッシュコマンド判定（最優先: コマンド入力時は自動直接入力）
            if (slashCommandGate.shouldBypassIme(currentChatText, c)) {
                if (isComposing) {
                    flushAsIs(insertToChat)
                }
                return false // マイクラ標準処理に任せる
            }

            // 2. 空白（スペース）の処理: keyPressed 側で英単語確定または日本語変換として処理
            if (c == ' ') {
                if (isComposing) {
                    return true
                }
                return false
            }

            // 3. 入力可能な英字またはハイフン
            if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
                // ★重要: 直前の単語が変換中（候補表示中、または前単語が存在）の状態で英字が入力された場合
                // 直前の単語をチャット欄へ自動確定して、新しい単語の入力をクリーンに開始！
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
            // 1. 半角/全角キーによるモード切り替え（ハイブリッド ↔ 直接入力）
            if (isHankakuZenkakuKey(keyCode, scanCode)) {
                skipNextGraveChar = true
                toggleInputMode(insertToChat)
                return true
            }

            // Ctrl+Space によるモード切り替え
            val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0
            if (ctrl && keyCode == GLFW.GLFW_KEY_SPACE) {
                toggleInputMode(insertToChat)
                return true
            }

            // 2. 変換キー -> HYBRIDモード
            if (isHenkanKey(scanCode)) {
                setInputMode(InputMode.HYBRID, insertToChat)
                return true
            }

            // 3. 無変換キー -> DIRECTモード
            if (isMuhenkanKey(scanCode)) {
                setInputMode(InputMode.DIRECT, insertToChat)
                return true
            }

            // 直接入力モード時は、その他のすべてのキーをバニラにパススルー
            if (currentInputMode == InputMode.DIRECT) {
                return false
            }

            // コマンドモード中の場合、IMEキー処理はスキップ
            if (slashCommandGate.isCommandMode && !isComposing) {
                return false
            }

            if (!isComposing) return false

            when (keyCode) {
                // Space: ハイブリッド入力の最重要キー
                GLFW.GLFW_KEY_SPACE -> {
                    val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
                    if (shift) {
                        // Shift+Space: バッファ内に明示的に半角スペースを追加して文章継続
                        rawBuffer.append(' ')
                        updatePreview()
                        return true
                    }

                    val currentRaw = rawBuffer.toString()

                    // 既に変換候補ウィンドウが出ている場合は次の候補へ
                    if (hasCandidates) {
                        val count = candidates.size
                        if (count > 0) {
                            selectedCandidateIndex = (selectedCandidateIndex + 1) % count
                        }
                        return true
                    }

                    // ★英単語＋助詞/日本語の複合語判定（例: "diamondwosagasou"）
                    // 英単語の直後に助詞が連続している場合は、即時英語確定ではなく複合語変換を行う
                    val compound = compoundWordSplitter.split(currentRaw)
                    if (compound != null) {
                        triggerCompoundConversion(compound, currentRaw)
                        return true
                    }

                    // 単語の英日判定（スペース押下時に即時同期判定）
                    val eval = scoreEngine.evaluate(currentRaw, isFinal = true)

                    // ★英単語判定（例: "diamond", "apple", "test" 等）
                    // 漢字変換候補ウィンドウは出さず、英単語＋半角スペースとして即座に確定！
                    if (eval.verdict == Verdict.ENGLISH || englishDetector.isWord(currentRaw)) {
                        insertToChat("$currentRaw ")
                        reset()
                        return true
                    }

                    // ★日本語判定（例: "kyouha", "sagasou" 等）
                    // 即座にかな漢字変換をトリガーし、第一候補を表示
                    triggerConversion()
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

    /**
     * 入力プレビューの更新
     * 英単語一致・英語優勢時は英字のままプレビュー（ぢあもんdに化けるのを防止）
     */
    private fun updatePreview() {
        val text = rawBuffer.toString()
        if (text.isEmpty()) {
            previewKana = ""
            candidates = emptyList()
            return
        }

        // スペース区切りの場合
        if (text.contains(' ')) {
            previewKana = text.split(' ').joinToString(" ") { token ->
                if (token.isEmpty()) ""
                else if (englishDetector.isWord(token)) token
                else {
                    val compound = compoundWordSplitter.split(token)
                    if (compound != null) "${compound.english} ${compound.japaneseKana}"
                    else romajiDetector.toKanaLenient(token)
                }
            }
            return
        }

        // 英単語＋日本語の複合語判定（例: "diamondwosagasou" -> "diamond をさがそう"）
        val compound = compoundWordSplitter.split(text)
        if (compound != null) {
            previewKana = "${compound.english} ${compound.japaneseKana}"
            return
        }

        // 英単語辞書完全一致、または英語判定の場合は英語のままプレビュー！
        val eval = scoreEngine.evaluate(text, isFinal = false)
        if (eval.verdict == Verdict.ENGLISH || englishDetector.isWord(text)) {
            previewKana = text
            return
        }

        try {
            previewKana = romajiDetector.toKanaLenient(text)
        } catch (_: Throwable) {
            previewKana = text
        }
    }

    /**
     * 変換リクエストの送信（単一単語およびスペース区切りハイブリッド文対応）
     */
    private fun triggerConversion() {
        val raw = rawBuffer.toString()
        val text = previewKana.ifEmpty { raw }

        // 1. スペース区切りハイブリッド文（例: "kyouha diamond wo sagasou"）の処理
        if (raw.contains(' ')) {
            triggerHybridSentenceConversion(raw)
            return
        }

        // 2. 英単語＋助詞/日本語の複合語（例: "diamondwosagasou" -> "diamondを探そう"）の処理
        val compound = compoundWordSplitter.split(raw)
        if (compound != null) {
            triggerCompoundConversion(compound, raw)
            return
        }

        // 3. 単一単語の処理
        val eval = try {
            scoreEngine.evaluate(raw, isFinal = true)
        } catch (_: Throwable) {
            DetectionResult(Verdict.UNDECIDED, 0, 0, "fallback")
        }

        // 英語スコアが優勢な場合は英字を第1候補に
        if (eval.verdict == Verdict.ENGLISH || englishDetector.isWord(raw)) {
            candidates = listOf(raw)
            selectedCandidateIndex = 0
            return
        }

        // 即座に応答可能な初期候補をセット（応答性最大化）
        val immediateList = mutableListOf(text)
        if (!immediateList.contains(raw)) {
            immediateList.add(raw)
        }
        candidates = immediateList
        selectedCandidateIndex = 0

        // Google CGI による高精度かな漢字変換
        converter.convertAsync(text).thenAccept { resultList ->
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val list = resultList.take(MeltypeConfig.maxCandidates).toMutableList()
                    if (!list.contains(text)) list.add(text)
                    if (!list.contains(raw)) list.add(raw)

                    candidates = list
                    if (selectedCandidateIndex !in candidates.indices) {
                        selectedCandidateIndex = 0
                    }
                }
            }
        }.exceptionally {
            null
        }
    }

    /**
     * 英単語＋助詞/日本語の複合語（例: "diamondwosagasou"）を変換
     * 英単語（"diamond"）は保護し、日本語部分（"wosagasou" -> "を探そう"）のみを変換して結合
     */
    private fun triggerCompoundConversion(compound: CompoundSplit, raw: String) {
        val initialKana = "${compound.english}${compound.japaneseKana}"
        candidates = listOf(initialKana, raw)
        selectedCandidateIndex = 0

        converter.convertAsync(compound.japaneseKana).thenAccept { resultList ->
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val list = resultList.take(MeltypeConfig.maxCandidates)
                        .map { "${compound.english}$it" }
                        .toMutableList()
                    if (!list.contains(initialKana)) list.add(initialKana)
                    if (!list.contains(raw)) list.add(raw)

                    candidates = list
                    selectedCandidateIndex = 0
                }
            }
        }.exceptionally {
            null
        }
    }

    /**
     * スペース区切りハイブリッド文（例: "kyouha diamond wo sagasou"）の変換
     * 英単語は英語のまま保護し、日本語トークンのみを変換して結合する
     */
    private fun triggerHybridSentenceConversion(rawSentence: String) {
        val tokens = rawSentence.split(' ')

        // 即座に初期プレビュー候補を生成
        val immediateSentence = tokens.joinToString(" ") { token ->
            if (token.isEmpty()) ""
            else if (englishDetector.isWord(token)) token
            else {
                val compound = compoundWordSplitter.split(token)
                if (compound != null) "${compound.english}${compound.japaneseKana}"
                else romajiDetector.toKanaLenient(token)
            }
        }
        candidates = listOf(immediateSentence, rawSentence)
        selectedCandidateIndex = 0

        // 各トークンごとに変換（複合語、英単語、日本語をそれぞれ最適処理）
        val convertedTokensFutures = tokens.map { token ->
            val compound = compoundWordSplitter.split(token)
            if (compound != null) {
                converter.convertAsync(compound.japaneseKana).thenApply { res ->
                    val best = res.firstOrNull() ?: compound.japaneseKana
                    listOf("${compound.english}$best")
                }.exceptionally { listOf("${compound.english}${compound.japaneseKana}") }
            } else if (token.isEmpty() || englishDetector.isWord(token) || scoreEngine.evaluate(token, isFinal = true).verdict == Verdict.ENGLISH) {
                java.util.concurrent.CompletableFuture.completedFuture(listOf(token))
            } else {
                val kana = romajiDetector.toKanaLenient(token)
                converter.convertAsync(kana).thenApply { res ->
                    if (res.isNotEmpty()) res else listOf(kana)
                }.exceptionally { listOf(kana) }
            }
        }

        // すべてのトークンの変換が完了したら結合
        val allFutures = java.util.concurrent.CompletableFuture.allOf(*convertedTokensFutures.toTypedArray())
        allFutures.thenAccept {
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val tokenCandidates = convertedTokensFutures.map { it.get() }
                    val synthesized1 = tokenCandidates.joinToString(" ") { it.firstOrNull() ?: "" }
                    val list = mutableListOf(synthesized1)
                    if (!list.contains(immediateSentence)) list.add(immediateSentence)
                    if (!list.contains(rawSentence)) list.add(rawSentence)

                    candidates = list
                    selectedCandidateIndex = 0
                }
            }
        }
    }

    /** 現在選択中の候補（またはプレビュー文字列）を確定してチャット欄に挿入 */
    fun commitCurrent(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            val committedText = when {
                hasCandidates -> candidates.getOrNull(selectedCandidateIndex) ?: candidates.firstOrNull() ?: previewKana
                previewKana.isNotEmpty() -> {
                    val rawStr = rawBuffer.toString()
                    val compound = compoundWordSplitter.split(rawStr)
                    if (compound != null) {
                        "${compound.english}${compound.japaneseKana}"
                    } else {
                        val eval = try {
                            scoreEngine.evaluate(rawStr, isFinal = true)
                        } catch (_: Throwable) {
                            DetectionResult(Verdict.JAPANESE, 1, 0, "fallback")
                        }
                        if (eval.verdict == Verdict.ENGLISH || englishDetector.isWord(rawStr)) {
                            rawStr
                        } else {
                            previewKana
                        }
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
