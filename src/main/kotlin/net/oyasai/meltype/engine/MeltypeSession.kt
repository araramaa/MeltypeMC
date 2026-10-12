package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.ConversionLearningStore
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
    private val compoundWordSplitter: CompoundWordSplitter = CompoundWordSplitter(englishDetector, romajiDetector),
    val learningStore: ConversionLearningStore = ConversionLearningStore()
) {

    private val lock = Any()

    /** 現在変換中の読み・入力文字列（学習記録用） */
    private var activeReading: String = ""
    private var activeRaw: String = ""

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
            // 半角/全角キー押下直後のバッククォート文字等の誤入力を抑止（~ は記号として有効なため除外）
            if (skipNextGraveChar) {
                skipNextGraveChar = false
                if (c == '`' || c == '｀' || c == '\u0000') {
                    return true
                }
            }

            // 半角/全角キー（バッククォート）が charTyped として飛んできた場合の完全救済（~ は記号として有効なため除外）
            if (c == '`' || c == '｀') {
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

                rawBuffer.append(c)
                updatePreview()
                return true
            }

            // 4. 日本語入力中の句読点・記号（確定させずにバッファに保持して文の入力を継続）
            if (isComposing && (c == ',' || c == '.' || c == '?' || c == '!' || c == '~' || c == '～' || c == '〜')) {
                val punctuation = when (c) {
                    ',' -> "、"
                    '.' -> "。"
                    '?' -> "？"
                    '!' -> "！"
                    '~', '～', '〜' -> "～"
                    else -> c.toString()
                }
                rawBuffer.append(punctuation)
                updatePreview()
                return true
            }

            // 5. 非変換中（バッファが空）での波線記号入力（HYBRIDモード時は全角「～」を直接挿入）
            if (!isComposing && (c == '~' || c == '～' || c == '〜')) {
                insertToChat("～")
                return true
            }

            // 6. 英字以外の記号や数字が入力された場合
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
            // 0. Shift + F12 による「～」記号の入力（ユーザー要望）
            val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
            if (shift && keyCode == GLFW.GLFW_KEY_F12) {
                if (isComposing) {
                    rawBuffer.append("～")
                    updatePreview()
                } else {
                    insertToChat("～")
                }
                return true
            }

            // 1. 半角/全角キーによるモード切り替え（ハイブリッド ↔ 直接入力）
            // Shift等の修飾キーが押されていない単独キー押下時のみモード切り替えを行う
            val noModifiers = (modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT)) == 0
            if (noModifiers && isHankakuZenkakuKey(keyCode, scanCode)) {
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

            // 3. 無変換キー: 入力中は「ひらがな -> カタカナ -> 最初に入力した英字」を順にトグル選択！
            if (isMuhenkanKey(scanCode)) {
                if (isComposing) {
                    toggleMuhenkanCandidates()
                    return true
                }
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

                    // ★英単語＋助詞/日本語の複合語・ハイブリッド文判定（例: "kyouhadiamondwotoriniikou", "diamondwosagasou"）
                    val multiCompound = compoundWordSplitter.splitSentence(currentRaw)
                    if (multiCompound != null) {
                        triggerMultiCompoundConversion(multiCompound, currentRaw)
                        return true
                    }

                    val compound = compoundWordSplitter.split(currentRaw)
                    if (compound != null) {
                        triggerCompoundConversion(compound, currentRaw)
                        return true
                    }

                    // ★英語判定（例: "diamond", "apple", "Modrinth", "curseforge" 等）
                    // 漢字変換候補ウィンドウは出さず、英単語＋半角スペースとして即座に確定！
                    // ※日本語日常語（sore, are, dare, sake 等）は日本語変換候補を表示！
                    if (scoreEngine.isEnglishInput(currentRaw)) {
                        insertToChat("$currentRaw ")
                        reset()
                        return true
                    }

                    // ★日本語判定（例: "kyouha", "sagasou", "sore", "douro" 等）
                    // 即座にかな漢字変換をトリガーし、第一候補を表示
                    triggerConversion()
                    return true
                }

                // Enter: 確定 (Shift+Enter なら最初に入力した文字そのまま即座に確定挿入！)
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
                    if (shift && isComposing) {
                        flushAsIs(insertToChat)
                        return true
                    }
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

                // Tab / Shift+Tab: 候補送り・戻し（未表示時は候補表示）
                GLFW.GLFW_KEY_TAB -> {
                    if (!hasCandidates && isComposing) {
                        triggerConversion()
                        return true
                    }
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

                // 左右下矢印キー: 候補選択（未表示時は下矢印で候補表示）
                GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN -> {
                    if (!hasCandidates && isComposing && keyCode == GLFW.GLFW_KEY_DOWN) {
                        triggerConversion()
                        return true
                    }
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

                // F6〜F10: 無変換ショートカット（最初に入力した文字そのままの形式変換）
                GLFW.GLFW_KEY_F6 -> {
                    if (isComposing) {
                        applyDirectCandidate(romajiDetector.toKanaLenient(rawBuffer.toString()))
                        return true
                    }
                }
                GLFW.GLFW_KEY_F7 -> {
                    if (isComposing) {
                        applyDirectCandidate(romajiDetector.toKatakana(romajiDetector.toKanaLenient(rawBuffer.toString())))
                        return true
                    }
                }
                GLFW.GLFW_KEY_F9 -> {
                    if (isComposing) {
                        applyDirectCandidate(romajiDetector.toZenkakuAlpha(rawBuffer.toString()))
                        return true
                    }
                }
                GLFW.GLFW_KEY_F10 -> {
                    // F10: 最初に入力した半角アルファベットそのまま！
                    if (isComposing) {
                        applyDirectCandidate(rawBuffer.toString())
                        return true
                    }
                }
            }

            return false
        }
    }

    /**
     * 無変換トグル（ひらがな -> カタカナ -> 最初に入力した半角英字）
     */
    private fun toggleMuhenkanCandidates() {
        if (!isComposing) return
        val raw = rawBuffer.toString()
        val kana = romajiDetector.toKanaLenient(raw)
        val kata = romajiDetector.toKatakana(kana)
        val modes = listOf(kana, kata, raw).distinct()

        val curSelected = candidates.getOrNull(selectedCandidateIndex)
        val nextIdx = if (curSelected != null && modes.contains(curSelected)) {
            (modes.indexOf(curSelected) + 1) % modes.size
        } else {
            0
        }
        candidates = modes
        selectedCandidateIndex = nextIdx
    }

    /**
     * F6〜F10 による直接変換候補の即時適用
     */
    private fun applyDirectCandidate(text: String) {
        val raw = rawBuffer.toString()
        val kana = romajiDetector.toKanaLenient(raw)
        val list = mutableListOf(text)
        if (!list.contains(raw)) list.add(raw)
        if (!list.contains(kana)) list.add(kana)
        candidates = list
        selectedCandidateIndex = 0
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
                else if (scoreEngine.isPureEnglishWord(token)) token
                else {
                    val compound = compoundWordSplitter.split(token)
                    if (compound != null) "${compound.english} ${compound.japaneseKana}"
                    else romajiDetector.toKanaLenient(token)
                }
            }
            return
        }

        // 複合語・ハイブリッド文判定（例: "kyouhadiamondwotoriniikou" -> "きょうはdiamondをとりにいこう"）
        val multiCompound = compoundWordSplitter.splitSentence(text)
        if (multiCompound != null) {
            previewKana = multiCompound.previewText
            return
        }

        // 英単語＋日本語の複合語判定（例: "diamondwosagasou" -> "diamond をさがそう"）
        val compound = compoundWordSplitter.split(text)
        if (compound != null) {
            previewKana = "${compound.english} ${compound.japaneseKana}"
            return
        }

        // 英語（英単語辞書一致、ローマ字として無効な英字列、大文字を含む英字列等）は英語のままプレビュー！
        if (scoreEngine.isEnglishInput(text)) {
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
        activeReading = text
        activeRaw = raw

        // 1. スペース区切りハイブリッド文（例: "kyouha diamond wo sagasou"）の処理
        if (raw.contains(' ')) {
            triggerHybridSentenceConversion(raw)
            return
        }

        // 2. 複合語・ハイブリッド文（例: "kyouhadiamondwotoriniikou" -> "今日はdiamondを取りに行こう"）
        val multiCompound = compoundWordSplitter.splitSentence(raw)
        if (multiCompound != null) {
            triggerMultiCompoundConversion(multiCompound, raw)
            return
        }

        // 3. 英単語＋助詞/日本語の複合語（例: "diamondwosagasou" -> "diamondを探そう"）の処理
        val compound = compoundWordSplitter.split(raw)
        if (compound != null) {
            triggerCompoundConversion(compound, raw)
            return
        }

        // 4. 単一単語の処理
        val isEnglishDominant = scoreEngine.isEnglishInput(raw)
        val initialKana = romajiDetector.toKanaLenient(raw)
        val initialKatakana = romajiDetector.toKatakana(initialKana)
        val rawCandidate = raw
        val kanaCandidate = if (initialKana.isNotEmpty()) initialKana else raw
        val katakanaCandidate = if (initialKatakana.isNotEmpty() && initialKatakana != initialKana) initialKatakana else null

        // 即座に応答可能な初期候補をセット（★最初に入力した文字そのまま選べるように常時確保）
        val immediateList = mutableListOf<String>()
        if (isEnglishDominant) {
            immediateList.add(rawCandidate)
            if (kanaCandidate != rawCandidate) immediateList.add(kanaCandidate)
            if (katakanaCandidate != null && katakanaCandidate != rawCandidate) immediateList.add(katakanaCandidate)
        } else {
            val learnedList = learningStore.getLearnedCandidates(text)
            immediateList.addAll(learnedList)
            if (!immediateList.contains(kanaCandidate)) immediateList.add(kanaCandidate)
            if (katakanaCandidate != null && !immediateList.contains(katakanaCandidate)) immediateList.add(katakanaCandidate)
            if (!immediateList.contains(rawCandidate)) immediateList.add(rawCandidate)
        }

        candidates = immediateList.distinct().take(9).toMutableList()
        selectedCandidateIndex = 0

        // Google CGI / 辞書 による高精度かな漢字変換（最初に入力した英字・かなを必ず候補に保持！）
        val convertTarget = if (isEnglishDominant && initialKana.isNotEmpty()) initialKana else text
        converter.convertAsync(convertTarget).thenAccept { resultList ->
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val fullList = mutableListOf<String>()
                    if (isEnglishDominant) {
                        fullList.add(rawCandidate) // 第1候補: 英語
                        fullList.addAll(resultList) // 第2候補以降: 漢字・カタカナ等
                        if (!fullList.contains(kanaCandidate)) fullList.add(kanaCandidate)
                        if (katakanaCandidate != null && !fullList.contains(katakanaCandidate)) fullList.add(katakanaCandidate)
                    } else {
                        val ranked = learningStore.rankCandidates(text, resultList)
                        fullList.addAll(ranked)
                        if (!fullList.contains(kanaCandidate)) fullList.add(kanaCandidate)
                        if (katakanaCandidate != null && !fullList.contains(katakanaCandidate)) fullList.add(katakanaCandidate)
                        // ★最初に入力したアルファベットそのままを必ず候補に含める！
                        if (!fullList.contains(rawCandidate)) fullList.add(rawCandidate)
                    }

                    // 最大表示数（数字キー1〜9で直接選択可能な 7〜9 件、rawCandidate が必ず含まれる件数）
                    val maxCount = maxOf(MeltypeConfig.maxCandidates, 7).coerceAtMost(9)
                    val distinct = fullList.distinct().toMutableList()
                    // 万が一 rawCandidate が maxCount 外にはみ出そうな場合は末尾に押し戻して必ず保持
                    if (!distinct.take(maxCount).contains(rawCandidate) && distinct.contains(rawCandidate)) {
                        distinct.remove(rawCandidate)
                        distinct.add(maxCount - 1, rawCandidate)
                    }
                    candidates = distinct.take(maxCount).toMutableList()
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
     * 複数セグメントからなるハイブリッド文（例: "kyouhadiamondwotoriniikou" -> "今日はdiamondを取りに行こう"）の変換
     * 各日本語セグメントを Google CGI / 辞書 で変換し、英語セグメントと結合して候補を生成
     */
    private fun triggerMultiCompoundConversion(split: MultiCompoundSplit, raw: String) {
        val preview = split.previewText
        activeReading = preview
        activeRaw = raw

        // 即座に応答可能な初期候補
        val immediateList = listOf(split.noSpaceText, split.previewText, raw).distinct().toMutableList()
        candidates = immediateList
        selectedCandidateIndex = 0

        // 各セグメントの変換 Future
        val segmentFutures = split.segments.map { segment ->
            if (segment.isEnglish) {
                java.util.concurrent.CompletableFuture.completedFuture(listOf(segment.raw))
            } else {
                converter.convertAsync(segment.reading).thenApply { res ->
                    if (res.isNotEmpty()) res else listOf(segment.reading)
                }.exceptionally { listOf(segment.reading) }
            }
        }

        val allFutures = java.util.concurrent.CompletableFuture.allOf(*segmentFutures.toTypedArray())
        allFutures.thenAccept {
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val segmentResults = segmentFutures.map { it.get() }
                    // 第1候補: スペースなし結合（例: "今日はdiamondを取りに行こう"）
                    val synthesizedNoSpace = split.segments.mapIndexed { idx, seg ->
                        if (seg.isEnglish) seg.raw else segmentResults[idx].firstOrNull() ?: seg.reading
                    }.joinToString("")

                    // 第2候補: 英単語の周囲にスペースを入れた結合（例: "今日は diamond を取りに行こう"）
                    val synthesizedSpaced = split.segments.mapIndexed { idx, seg ->
                        if (seg.isEnglish) " ${seg.raw} " else segmentResults[idx].firstOrNull() ?: seg.reading
                    }.joinToString("").replace("  ", " ").trim()

                    val fullList = mutableListOf(synthesizedNoSpace, synthesizedSpaced)
                    if (!fullList.contains(split.previewText)) fullList.add(split.previewText)
                    if (!fullList.contains(raw)) fullList.add(raw)

                    candidates = fullList.distinct().take(MeltypeConfig.maxCandidates).toMutableList()
                    selectedCandidateIndex = 0
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
        activeReading = raw
        activeRaw = raw
        val initialKana = "${compound.english}${compound.japaneseKana}"

        // ★学習済み候補の事前適用（複合語全体および日本語部分）
        val learnedFull = learningStore.getLearnedCandidates(raw)
        val learnedJp = learningStore.getLearnedCandidates(compound.japaneseKana).map { "${compound.english}$it" }
        val immediateList = (learnedFull + learnedJp + listOf(initialKana, raw)).distinct().toMutableList()

        candidates = immediateList
        selectedCandidateIndex = 0

        converter.convertAsync(compound.japaneseKana).thenAccept { resultList ->
            synchronized(lock) {
                if (rawBuffer.isNotEmpty()) {
                    val fullList = resultList.map { "${compound.english}$it" }
                    val ranked = learningStore.rankCandidates(raw, fullList)
                    val list = ranked.take(MeltypeConfig.maxCandidates).toMutableList()
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
            else if (scoreEngine.isPureEnglishWord(token)) token
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
            } else if (token.isEmpty() || scoreEngine.isPureEnglishWord(token) || scoreEngine.evaluate(token, isFinal = true).verdict == Verdict.ENGLISH) {
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
            val hadCandidates = hasCandidates
            val currentReading = activeReading
            val currentRaw = activeRaw

            val committedText = when {
                hasCandidates -> candidates.getOrNull(selectedCandidateIndex) ?: candidates.firstOrNull() ?: previewKana
                previewKana.isNotEmpty() -> {
                    val rawStr = rawBuffer.toString()
                    val multiCompound = compoundWordSplitter.splitSentence(rawStr)
                    if (multiCompound != null) {
                        multiCompound.noSpaceText
                    } else {
                        val compound = compoundWordSplitter.split(rawStr)
                        if (compound != null) {
                            "${compound.english}${compound.japaneseKana}"
                        } else {
                            if (scoreEngine.isEnglishInput(rawStr)) {
                                rawStr
                            } else {
                                previewKana
                            }
                        }
                    }
                }
                else -> rawBuffer.toString()
            }

            if (committedText.isNotEmpty()) {
                // ★変換学習: 候補ウィンドウから選択・確定した場合に学習
                if (hadCandidates && currentReading.isNotEmpty()) {
                    learningStore.recordSelection(currentReading, committedText)
                    if (currentRaw.isNotEmpty() && currentRaw != currentReading) {
                        learningStore.recordSelection(currentRaw, committedText)
                    }
                    val compound = compoundWordSplitter.split(currentRaw)
                    if (compound != null && committedText.startsWith(compound.english)) {
                        val jpPart = committedText.removePrefix(compound.english)
                        if (jpPart.isNotEmpty()) {
                            learningStore.recordSelection(compound.japaneseKana, jpPart)
                        }
                    }
                }

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
            activeReading = ""
            activeRaw = ""
        }
    }
}
