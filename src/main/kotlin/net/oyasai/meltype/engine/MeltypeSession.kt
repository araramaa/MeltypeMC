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

    /**
     * 変換の世代番号。候補や入力バッファがユーザー操作で変わるたびに増やす。
     * 遅れて届いた非同期の変換結果が、古い入力のものかどうかを見分けるために使う。
     */
    private var conversionGeneration = 0

    /** 現在の入力モード（HYBRID: 自動英日ハイブリッド / DIRECT: バニラ直接入力） */
    var currentInputMode: InputMode = MeltypeConfig.initialInputMode
        private set

    /** 文節伸縮マネージャー */
    private val clauseManager = ClauseManager()

    /** 変換の種類の判定と候補の作り方を決めるプランナー */
    private val conversionPlanner = ConversionPlanner(romajiDetector, scoreEngine, compoundWordSplitter, converter, learningStore)

    /** プレビュー文字列生成フォーマッター */
    private val previewFormatter = PreviewFormatter(romajiDetector, scoreEngine, compoundWordSplitter)

    /** 現在入力中の生キーストローク（アルファベット列） */
    var rawBuffer: StringBuilder = StringBuilder()
        private set

    /** 現在の文節伸縮における選択文字数（アルファベット数）。0 または rawBuffer.length の場合は全文字対象 */
    val clauseLength: Int get() = clauseManager.clauseLength

    /** 現在のプレビューテキスト（下線表示用: 日本語ならひらがな、英語なら英単語そのまま） */
    var previewKana: String = ""
        private set

    /** 変換候補リスト（スレッドセーフにアクセス） */
    @Volatile
    var candidates: List<String> = emptyList()
        private set

    /** 現在選択中の候補インデックス (0-based) */
    @Volatile
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
        /** JIS 半角/全角キースキャンコード (41 / 0x29) */
        const val SCAN_HANKAKU_ZENKAKU = 41

        /** JIS 変換キースキャンコード (121) */
        const val SCAN_HENKAN = 121

        /** JIS 無変換キースキャンコード (123) */
        const val SCAN_MUHENKAN = 123
    }

    /**
     * 半角/全角キー判定 (JIS スキャンコード 41 / GLFW_KEY_GRAVE_ACCENT / WORLDキー / F12)
     */
    fun isHankakuZenkakuKey(keyCode: Int, scanCode: Int): Boolean {
        if (scanCode == SCAN_HANKAKU_ZENKAKU) return true
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

                clauseManager.reset()
                rawBuffer.append(c)
                updatePreview()
                return true
            }

            // 4. 日本語入力中の句読点・記号（確定させずにバッファに保持して文の入力を継続）
            val punctuation = if (isComposing) toFullWidthPunctuation(c) else null
            if (punctuation != null) {
                appendToCompositionOrInsert(punctuation, insertToChat)
                return true
            }

            // 5. 非変換中（バッファが空）での波線記号入力（HYBRIDモード時は全角「～」を直接挿入）
            if (!isComposing && (c == '~' || c == '～' || c == '〜')) {
                insertToChat("～")
                return true
            }

            // 6. 外部IME（Windows IME等）から直接ひらがな・カタカナ・漢字等の確定文字が送られてきた場合
            // ★二重入力防止ガード: Meltype 側の未確定バッファを破棄し、外部IMEの文字のみをバニラにパススルー
            if (isJapaneseChar(c)) {
                if (isComposing) {
                    reset()
                }
                return false // Minecraftバニラ側の直接入力に任せる
            }

            // 7. 英字以外の記号や数字が入力された場合
            if (isComposing) {
                commitCurrent(insertToChat)
                insertToChat(c.toString())
                return true
            }

            return false
        }
    }

    /**
     * 記号・空白を入力中の文に続けて足す（入力中でなければチャット欄へ直接挿入）
     * 変換後（候補表示中）の場合は、選択中の候補を確定してから続けて入力する
     * （バッファに足すだけだと、確定する候補に含まれず消えてしまうため）
     */
    private fun appendToCompositionOrInsert(text: String, insertToChat: (String) -> Unit) {
        if (hasCandidates) {
            commitCurrent(insertToChat)
        }
        if (isComposing) {
            // 文節伸縮の残りがバッファに戻っている場合も、その末尾に足す
            rawBuffer.append(text)
            updatePreview()
        } else {
            insertToChat(text)
        }
    }

    /** 入力中に打った半角の句読点・記号を、日本語の記号にする（対象外の文字は null） */
    private fun toFullWidthPunctuation(c: Char): String? = when (c) {
        ',' -> "、"
        '.' -> "。"
        '?' -> "？"
        '!' -> "！"
        '~', '～', '〜' -> "～"
        else -> null
    }

    /** 外部IME確定文字判定（ひらがな、カタカナ、漢字） */
    private fun isJapaneseChar(c: Char): Boolean {
        val code = c.code
        return (code in 0x3040..0x309F) || // ひらがな
               (code in 0x30A0..0x30FF) || // カタカナ
               (code in 0x4E00..0x9FFF) || // CJK統合漢字
               (code in 0x3400..0x4DBF)    // CJK拡張A
    }

    /**
     * 特殊キー入力イベント（keyPressed）の処理
     * @return true の場合、このMODでキーを消費したためマイクラ標準のキー処理をキャンセルする
     */
    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // モードの切り替えや記号のショートカットは、どのモード・状態でも最優先で処理する
            if (handleGlobalKeys(keyCode, scanCode, modifiers, insertToChat)) return true

            // 直接入力モード時は、その他のすべてのキーをバニラにパススルー
            if (currentInputMode == InputMode.DIRECT) return false

            // コマンドモード中、または入力中でなければ、IMEキー処理はスキップ
            if (slashCommandGate.isCommandMode && !isComposing) return false
            if (!isComposing) return false

            return handleCompositionKey(keyCode, modifiers, insertToChat)
        }
    }

    /**
     * 入力モードの切り替えキーと、「～」のショートカットの処理（入力中かどうかに関係なく働く）
     */
    private fun handleGlobalKeys(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
        val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0

        return when {
            // Shift + F12 による「～」記号の入力
            shift && keyCode == GLFW.GLFW_KEY_F12 -> {
                appendToCompositionOrInsert("～", insertToChat)
                true
            }

            // 設定画面で登録した任意の切り替えキー
            MeltypeConfig.isToggleKey(keyCode, modifiers) -> {
                toggleInputMode(insertToChat)
                true
            }

            // 半角/全角キー（Shift 等の修飾キーが押されていない単独キー押下時のみ）
            noModifiers(modifiers) && isHankakuZenkakuKey(keyCode, scanCode) -> {
                skipNextGraveChar = true
                toggleInputMode(insertToChat)
                true
            }

            // Ctrl + Space
            ctrl && keyCode == GLFW.GLFW_KEY_SPACE -> {
                toggleInputMode(insertToChat)
                true
            }

            // 変換キー -> HYBRIDモード
            isHenkanKey(scanCode) -> {
                setInputMode(InputMode.HYBRID, insertToChat)
                true
            }

            // 無変換キー: 入力中は「ひらがな -> カタカナ -> 最初に入力した英字」を順に切り替え、入力中でなければ DIRECT モード
            isMuhenkanKey(scanCode) -> {
                if (isComposing) toggleMuhenkanCandidates() else setInputMode(InputMode.DIRECT, insertToChat)
                true
            }

            else -> false
        }
    }

    private fun noModifiers(modifiers: Int): Boolean =
        (modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT)) == 0

    /**
     * 入力中（バッファに文字がある）のときのキー処理
     * @return true の場合、キーを消費した
     */
    private fun handleCompositionKey(keyCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0

        return when (keyCode) {
            GLFW.GLFW_KEY_SPACE -> onSpaceKey(shift, insertToChat)
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> onEnterKey(shift, insertToChat)
            GLFW.GLFW_KEY_BACKSPACE -> onBackspaceKey()
            GLFW.GLFW_KEY_ESCAPE -> {
                reset()
                true
            }
            GLFW.GLFW_KEY_TAB -> onTabKey(shift)
            GLFW.GLFW_KEY_RIGHT -> if (shift) { expandClause(); true } else moveSelection(1)
            GLFW.GLFW_KEY_LEFT -> if (shift) { shrinkClause(); true } else moveSelection(-1)
            GLFW.GLFW_KEY_DOWN -> {
                if (!hasCandidates) {
                    triggerConversion()
                    true
                } else {
                    moveSelection(1)
                }
            }
            GLFW.GLFW_KEY_UP -> moveSelection(-1)

            // 1〜9の数字キー (メインキー & テンキー): 候補のダイレクト選択
            in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 ->
                hasCandidates && selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_1, insertToChat)
            in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9 ->
                hasCandidates && selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_KP_1, insertToChat)

            // F6〜F10: 最初に入力した文字そのままの形式変換
            GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10 -> {
                applyFunctionKeyConversion(keyCode)
                true
            }

            else -> false
        }
    }

    /**
     * Space: ハイブリッド入力の最重要キー
     * 候補が出ていれば次の候補へ、出ていなければ入力の種類を判定して変換（または英単語として確定）する
     */
    private fun onSpaceKey(shift: Boolean, insertToChat: (String) -> Unit): Boolean {
        // Shift+Space: バッファに明示的に半角スペースを足して、文を続けて入力
        if (shift) {
            appendToCompositionOrInsert(" ", insertToChat)
            return true
        }

        // 既に変換候補ウィンドウが出ている場合は次の候補へ
        if (hasCandidates) {
            selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
            return true
        }

        val currentRaw = rawBuffer.toString()

        // 英単語＋助詞/日本語の複合語・ハイブリッド文（例: "kyouhadiamondwotoriniikou", "diamondwosagasou"）
        compoundWordSplitter.splitSentence(currentRaw)?.let {
            applyPlan(conversionPlanner.planMultiCompound(it, currentRaw))
            return true
        }
        compoundWordSplitter.split(currentRaw)?.let {
            applyPlan(conversionPlanner.planCompound(it, currentRaw))
            return true
        }

        // 英語判定（例: "diamond", "Modrinth"）: 変換候補は出さず、英単語＋半角スペースとして即座に確定
        // ※日本語の日常語（sore, are, dare, sake 等）は日本語変換の候補を表示する
        if (scoreEngine.isEnglishInput(currentRaw)) {
            insertToChat("$currentRaw ")
            reset()
            return true
        }

        // 日本語判定（例: "kyouha", "sagasou"）: すぐにかな漢字変換を始めて、第一候補を表示
        triggerConversion()
        return true
    }

    /** Enter: 確定（Shift+Enter なら最初に入力した文字そのままで確定） */
    private fun onEnterKey(shift: Boolean, insertToChat: (String) -> Unit): Boolean {
        if (shift) flushAsIs(insertToChat) else commitCurrent(insertToChat)
        return true
    }

    /** Backspace: 候補表示中・文節伸縮中は変換を解除、それ以外は1文字削除 */
    private fun onBackspaceKey(): Boolean {
        if (hasCandidates || clauseManager.clauseLength > 0) {
            candidates = emptyList()
            selectedCandidateIndex = 0
            clauseManager.reset()
            updatePreview()
            return true
        }
        if (rawBuffer.isNotEmpty()) {
            clauseManager.reset()
            rawBuffer.deleteCharAt(rawBuffer.length - 1)
            updatePreview()
            return true
        }
        return false
    }

    /** Tab / Shift+Tab: 候補送り・戻し（候補が出ていなければ変換を始める） */
    private fun onTabKey(shift: Boolean): Boolean {
        if (!hasCandidates) {
            triggerConversion()
            return true
        }
        moveSelection(if (shift) -1 else 1)
        return true
    }

    /** F6〜F10: ひらがな / カタカナ / 全角英数 / 半角英数（最初に入力した文字そのまま）の候補を最上位にする */
    private fun applyFunctionKeyConversion(keyCode: Int) {
        val raw = rawBuffer.toString()
        val kana = romajiDetector.toKanaFinal(raw)
        applyDirectCandidate(
            when (keyCode) {
                GLFW.GLFW_KEY_F6 -> kana                                 // ひらがな
                GLFW.GLFW_KEY_F7 -> romajiDetector.toKatakana(kana)      // カタカナ
                GLFW.GLFW_KEY_F9 -> romajiDetector.toZenkakuAlpha(raw)   // 全角英数
                else -> raw                                              // F10: 半角英数そのまま
            }
        )
    }

    /**
     * 無変換トグル（ひらがな -> カタカナ -> 最初に入力した半角英字）
     */
    private fun toggleMuhenkanCandidates() {
        if (!isComposing) return
        val raw = rawBuffer.toString()
        val kana = romajiDetector.toKanaFinal(raw)
        val kata = romajiDetector.toKatakana(kana)
        val modes = listOf(kana, kata, raw).distinct()

        conversionGeneration++
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
     * 候補の選択位置を step 分だけ動かす（端では反対側へ循環）
     * @return 候補が無く動かせなかった場合は false
     */
    private fun moveSelection(step: Int): Boolean {
        val count = candidates.size
        if (count == 0) return false
        selectedCandidateIndex = Math.floorMod(selectedCandidateIndex + step, count)
        updatePreviewForCurrentCandidate()
        return true
    }

    /**
     * F6〜F10 による直接変換候補の即時適用
     */
    private fun applyDirectCandidate(text: String) {
        conversionGeneration++
        val raw = rawBuffer.toString()
        val kana = romajiDetector.toKanaFinal(raw)
        val list = mutableListOf(text)
        list.addIfMissing(raw)
        list.addIfMissing(kana)
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
     */
    private fun updatePreview() {
        conversionGeneration++
        val text = rawBuffer.toString()
        if (text.isEmpty()) {
            previewKana = ""
            candidates = emptyList()
            return
        }
        previewKana = previewFormatter.formatPreview(text)
    }

    /**
     * 文節（変換対象範囲）を 1 文字縮める（Shift + Left）
     * 最初に入力した文字を 1 文字ずつ選んで部分変換
     */
    fun shrinkClause() {
        if (rawBuffer.length <= 1) return
        clauseManager.shrink(rawBuffer.length)
        updateClauseConversion()
    }

    /**
     * 文節（変換対象範囲）を 1 文字伸ばす（Shift + Right）
     */
    fun expandClause() {
        if (rawBuffer.isEmpty()) return
        if (clauseManager.expand(rawBuffer.length)) {
            // 全文字対象に戻った場合は通常変換に復帰
            triggerConversion()
            return
        }
        updateClauseConversion()
    }

    /**
     * 文節伸縮時のかな漢字変換およびプレビュー更新
     */
    private fun updateClauseConversion() {
        if (!clauseManager.isPartial(rawBuffer.length)) {
            clauseManager.reset()
            triggerConversion()
            return
        }

        val (activeRawPart, remainderRawPart) = clauseManager.split(rawBuffer.toString())
        val activeKana = romajiDetector.toKanaFinal(activeRawPart)

        activeReading = activeKana
        activeRaw = activeRawPart
        val generation = ++conversionGeneration

        val katakana = romajiDetector.toKatakana(activeKana)
        val list = mutableListOf<String>()
        val learned = learningStore.getLearnedCandidates(activeKana)
        list.addAll(learned)
        list.addIfMissing(activeKana)
        if (katakana.isNotEmpty() && katakana != activeKana) list.addIfMissing(katakana)
        list.addIfMissing(activeRawPart)

        candidates = list.distinct().toMutableList()
        selectedCandidateIndex = 0

        val currentSelected = candidates.firstOrNull() ?: activeKana
        previewKana = previewFormatter.formatClausePreview(currentSelected, remainderRawPart)

        converter.convertAsync(activeKana).thenAccept { resultList ->
            synchronized(lock) {
                if (generation == conversionGeneration &&
                    rawBuffer.length >= clauseManager.clauseLength && clauseManager.clauseLength > 0
                ) {
                    val ranked = learningStore.rankCandidates(activeKana, resultList)
                    val fullList = mutableListOf<String>()
                    fullList.addAll(ranked)
                    fullList.addIfMissing(activeKana)
                    if (katakana.isNotEmpty() && katakana != activeKana) fullList.addIfMissing(katakana)
                    fullList.addIfMissing(activeRawPart)

                    candidates = CandidateFinalizer.finalize(fullList, activeRawPart, activeKana)
                    if (selectedCandidateIndex !in candidates.indices) {
                        selectedCandidateIndex = 0
                    }
                    val selected = candidates.getOrNull(selectedCandidateIndex) ?: activeKana
                    previewKana = previewFormatter.formatClausePreview(selected, remainderRawPart)
                }
            }
        }.exceptionally { null }
    }

    /**
     * 候補インデックス変更時に文節プレビューの表示を更新
     */
    private fun updatePreviewForCurrentCandidate() {
        if (clauseManager.isPartial(rawBuffer.length)) {
            val (activeRawPart, remainderRawPart) = clauseManager.split(rawBuffer.toString())
            val activeKana = romajiDetector.toKanaLenient(activeRawPart)
            val selected = candidates.getOrNull(selectedCandidateIndex) ?: activeKana
            previewKana = previewFormatter.formatClausePreview(selected, remainderRawPart)
        }
    }

    /**
     * 変換リクエストの送信
     * 候補の作り方は [ConversionPlanner] が決め、ここでは即時候補の表示と、最終候補への差し替えだけを行う
     */
    private fun triggerConversion() {
        applyPlan(conversionPlanner.plan(rawBuffer.toString(), previewKana))
    }

    /** 計画の即時候補を表示し、最終候補が届いたら差し替える */
    private fun applyPlan(plan: ConversionPlan) {
        activeReading = plan.reading
        activeRaw = plan.raw
        // 世代番号を増やしてから控える（即時に完了する結果は thenAccept が同期実行されるため）
        val generation = ++conversionGeneration
        candidates = plan.immediate
        selectedCandidateIndex = 0

        plan.final.thenAccept { finalList ->
            synchronized(lock) {
                // 依頼後に入力や候補が変わっていたら、古い結果は捨てる
                if (generation == conversionGeneration && rawBuffer.isNotEmpty()) {
                    candidates = finalList
                    if (!plan.keepSelection || selectedCandidateIndex !in finalList.indices) {
                        selectedCandidateIndex = 0
                    }
                }
            }
        }.exceptionally {
            null
        }
    }
    /** 現在選択中の候補（またはプレビュー文字列）を確定してチャット欄に挿入 */
    fun commitCurrent(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            val hadCandidates = hasCandidates
            val currentReading = activeReading
            val currentRaw = activeRaw

            // 文節伸縮による部分確定かどうか
            val isPartialClause = clauseManager.isPartial(rawBuffer.length)
            val remainderRaw = if (isPartialClause) clauseManager.split(rawBuffer.toString()).second else ""

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
                                // 変換せずに Enter で確定するときも、語尾の n は「ん」にする
                                previewKana.withFinalN()
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

            if (isPartialClause && remainderRaw.isNotEmpty()) {
                // 文節伸縮で確定された残りの文字列を次のバッファとして引き継ぐ
                rawBuffer.clear()
                rawBuffer.append(remainderRaw)
                clauseManager.reset()
                candidates = emptyList()
                selectedCandidateIndex = 0
                updatePreview()
            } else {
                reset()
            }
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
            conversionGeneration++
            rawBuffer.clear()
            clauseManager.reset()
            previewKana = ""
            candidates = emptyList()
            selectedCandidateIndex = 0
            skipNextGraveChar = false
            activeReading = ""
            activeRaw = ""
        }
    }
}
