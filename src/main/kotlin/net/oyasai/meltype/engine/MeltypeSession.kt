package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.ConversionLearningStore
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.conversion.IConverter
import org.lwjgl.glfw.GLFW

/**
 * 1回のチャット・GUI入力セッション（入力バッファ・変換候補・状態遷移）を管理するクラス
 * ［半角/全角キー不要］の自動英日ハイブリッド入力システムを実装しています。
 *
 * このクラスは「状態」と「キー・文字を受けたときの流れ」を持つ。
 * キーや文字の見分け・候補の作り方・確定する文字の決め方は、次の部品に任せている。
 * - [KeyClassifier] / [InputCharacters]: キーと文字の見分け
 * - [ConversionPlanner]: 変換の種類の判定と候補の作り方
 * - [CommitTextResolver]: 確定する文字の決め方と、学習の記録
 * - [FunctionKeyFormatter]: F6〜F10 の形式変換
 * - [ClauseManager]: 変換する範囲（文節）の伸縮
 *
 * 状態を変える処理は、すべて [lock] の中で行う（非同期の変換結果が戻ってくるため）。
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

    // ---------------------------------------------------------------------------------
    // 協力する部品
    // ---------------------------------------------------------------------------------

    /** 文節伸縮マネージャー */
    private val clauseManager = ClauseManager()

    /** 変換の種類の判定と候補の作り方を決めるプランナー */
    private val conversionPlanner = ConversionPlanner(romajiDetector, scoreEngine, compoundWordSplitter, converter, learningStore)

    /** プレビュー文字列生成フォーマッター */
    private val previewFormatter = PreviewFormatter(romajiDetector, scoreEngine, compoundWordSplitter)

    /** 確定する文字を決め、選んだ変換を学習として記録する部品 */
    private val commitTextResolver = CommitTextResolver(scoreEngine, compoundWordSplitter, learningStore)

    /** F6〜F10 の形式変換 */
    private val functionKeyFormatter = FunctionKeyFormatter(romajiDetector)

    // ---------------------------------------------------------------------------------
    // 状態
    // ---------------------------------------------------------------------------------

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

    /** Ctrl+Space で切り替えた直後に、続けて届く空白文字（U+0020）を入力させないためのフラグ */
    private var skipNextSpaceChar: Boolean = false

    /** 数字キーで候補を選んだ直後に、続けて届く数字の文字（その数字）を入力させないための記憶。なければ null */
    private var skipNextDigitChar: Char? = null

    /** 現在IME入力・変換中かどうか */
    val isComposing: Boolean get() = synchronized(lock) { rawBuffer.isNotEmpty() }

    /** 変換候補ウィンドウを表示中かどうか */
    val hasCandidates: Boolean get() = synchronized(lock) { candidates.isNotEmpty() }

    // ---------------------------------------------------------------------------------
    // 入力モード
    // ---------------------------------------------------------------------------------

    /**
     * 入力モードをトグル（HYBRID ↔ DIRECT）
     */
    fun toggleInputMode(insertToChat: ((String) -> Unit)? = null): InputMode {
        synchronized(lock) {
            finishCompositionBeforeModeChange(insertToChat)
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
                finishCompositionBeforeModeChange(insertToChat)
                currentInputMode = mode
            }
        }
    }

    /** モードを変える前に、入力中の文字を片付ける（確定できるなら確定、できないなら捨てる） */
    private fun finishCompositionBeforeModeChange(insertToChat: ((String) -> Unit)?) {
        if (isComposing && insertToChat != null) {
            commitCurrent(insertToChat)
        } else {
            reset()
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

    // ---------------------------------------------------------------------------------
    // 文字入力（charTyped）
    // ---------------------------------------------------------------------------------

    /**
     * 文字入力イベント（charTyped）の処理
     * @return true の場合、このMODで入力を消費したためマイクラ標準の入力処理をキャンセルする
     */
    fun onCharTyped(c: Char, currentChatText: String, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // 切り替えキーの直後に届く、余計な空白・バッククォートの文字は捨てる（モードに関係なく）
            if (shouldDiscardChar(c)) return true

            // 半角/全角キー（バッククォート）が charTyped として飛んできた場合の完全救済
            if (InputCharacters.isBackquote(c)) {
                toggleInputMode(insertToChat)
                return true
            }

            // 直接入力固定モード（DIRECT）時は、一切介入せずMinecraft標準に任せる
            if (currentInputMode == InputMode.DIRECT) return false

            return handleHybridChar(c, currentChatText, insertToChat)
        }
    }

    /**
     * 切り替えキーを押した直後に、続けて届く文字を捨てるか判定する。
     * - Ctrl+Space の直後に届く空白（U+0020）
     * - 半角/全角キーの直後に届くバッククォート等（`~` は記号として有効なので除外）
     * - 数字キーで候補を選んだ直後に届く、その数字
     */
    private fun shouldDiscardChar(c: Char): Boolean {
        skipNextDigitChar?.let { digit ->
            skipNextDigitChar = null
            if (c == digit) return true
        }
        if (skipNextSpaceChar) {
            skipNextSpaceChar = false
            if (c == ' ') return true
        }
        if (skipNextGraveChar) {
            skipNextGraveChar = false
            if (InputCharacters.isBackquote(c) || c == '\u0000') return true
        }
        return false
    }

    /**
     * ハイブリッド自動判別モード（HYBRID）での、1文字の処理
     * @return true の場合、入力を消費した
     */
    private fun handleHybridChar(c: Char, currentChatText: String, insertToChat: (String) -> Unit): Boolean {
        // 1. スラッシュコマンド判定（最優先: コマンド入力時は自動直接入力）
        if (slashCommandGate.shouldBypassIme(currentChatText, c)) {
            if (isComposing) flushAsIs(insertToChat)
            return false // マイクラ標準処理に任せる
        }

        // 2. 空白（スペース）: keyPressed 側で英単語確定または日本語変換として処理するので、入力中は文字を入れない
        if (c == ' ') return isComposing

        // 3. 入力可能な英字またはハイフン
        if (InputCharacters.isRomajiInputChar(c)) {
            // 直前の単語が変換中（候補表示中）なら、先にチャット欄へ確定して、新しい単語の入力をクリーンに始める
            if (hasCandidates) commitCurrent(insertToChat)

            clauseManager.reset()
            rawBuffer.append(c)
            updatePreview()
            return true
        }

        // 4. 日本語入力中の句読点・記号（確定させずにバッファに保持して、文の入力を続ける）
        val punctuation = if (isComposing) InputCharacters.toFullWidthPunctuation(c) else null
        if (punctuation != null) {
            appendToCompositionOrInsert(punctuation, insertToChat)
            return true
        }

        // 5. 入力中でないときの波線記号（HYBRID では全角「～」を直接入れる）
        if (!isComposing && InputCharacters.isTilde(c)) {
            insertToChat("～")
            return true
        }

        // 6. 外部IME（Windows IME等）から、ひらがな・カタカナ・漢字の確定文字が届いた場合
        //    二重入力を防ぐため、Meltype 側の未確定バッファは捨てて、外部IMEの文字だけをバニラに任せる
        if (InputCharacters.isJapanese(c)) {
            if (isComposing) reset()
            return false
        }

        // 7. 英字以外の記号や数字: 入力中なら、先に確定してからその文字を入れる
        if (isComposing) {
            commitCurrent(insertToChat)
            insertToChat(c.toString())
            return true
        }

        return false
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

    // ---------------------------------------------------------------------------------
    // キー入力（keyPressed）
    // ---------------------------------------------------------------------------------

    /**
     * 特殊キー入力イベント（keyPressed）の処理
     * @return true の場合、このMODでキーを消費したためマイクラ標準のキー処理をキャンセルする
     */
    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        synchronized(lock) {
            // 新しいキーが押されたら、前の Ctrl+Space の空白捨てを解除する（文字入力が届かなかったときに、次の空白を捨てないため）
            skipNextSpaceChar = false
            skipNextDigitChar = null

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
        return when {
            // Shift + F12 による「～」記号の入力
            KeyClassifier.hasShift(modifiers) && keyCode == GLFW.GLFW_KEY_F12 -> {
                appendToCompositionOrInsert("～", insertToChat)
                true
            }

            // 設定画面で登録した任意の切り替えキー
            MeltypeConfig.isToggleKey(keyCode, modifiers) -> {
                skipNextSpaceChar = keyCode == GLFW.GLFW_KEY_SPACE // 切り替えキーが Space を含むとき、空白が入らないようにする
                toggleInputMode(insertToChat)
                true
            }

            // 半角/全角キー（Shift 等の修飾キーが押されていない単独キー押下時のみ）
            KeyClassifier.hasNoModifiers(modifiers) && KeyClassifier.isHankakuZenkaku(keyCode, scanCode) -> {
                skipNextGraveChar = true
                toggleInputMode(insertToChat)
                true
            }

            // Ctrl + Space（続けて空白の文字入力が届くので、それを捨てる）
            KeyClassifier.hasCtrl(modifiers) && keyCode == GLFW.GLFW_KEY_SPACE -> {
                skipNextSpaceChar = true
                toggleInputMode(insertToChat)
                true
            }

            // 変換キー: 入力モードの切り替え（押すたびに あ/A ⇔ A）
            KeyClassifier.isHenkan(scanCode) -> {
                toggleInputMode(insertToChat)
                true
            }

            else -> false
        }
    }

    /**
     * 入力中（バッファに文字がある）のときのキー処理
     * @return true の場合、キーを消費した
     */
    private fun handleCompositionKey(keyCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        val shift = KeyClassifier.hasShift(modifiers)

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
            GLFW.GLFW_KEY_DOWN -> onDownKey()
            GLFW.GLFW_KEY_UP -> moveSelection(-1)

            // 1〜9の数字キー (メインキー & テンキー): 候補のダイレクト選択
            in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 ->
                hasCandidates && selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_1, insertToChat)
            in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9 ->
                hasCandidates && selectCandidateByIndex(keyCode - GLFW.GLFW_KEY_KP_1, insertToChat)

            else -> onOtherKey(keyCode)
        }
    }

    /** どれにも当てはまらないキー。F6〜F10 なら形式変換して消費、それ以外はバニラに任せる */
    private fun onOtherKey(keyCode: Int): Boolean {
        if (!functionKeyFormatter.isFormatKey(keyCode)) return false
        applyDirectCandidate(functionKeyFormatter.format(keyCode, rawBuffer.toString()))
        return true
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

    /** ↓: 候補が出ていなければ変換を始め、出ていれば次の候補へ */
    private fun onDownKey(): Boolean {
        if (!hasCandidates) {
            triggerConversion()
            return true
        }
        return moveSelection(1)
    }

    // ---------------------------------------------------------------------------------
    // 候補の選択
    // ---------------------------------------------------------------------------------

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

    private fun selectCandidateByIndex(index: Int, insertToChat: (String) -> Unit): Boolean {
        if (hasCandidates && index in candidates.indices) {
            selectedCandidateIndex = index
            commitCurrent(insertToChat)
            // このキーの数字が、続けて文字としても届くので、その1回を捨てる（commitCurrent の reset の後に記憶する）
            skipNextDigitChar = '1' + index
            return true
        }
        return false
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

    // ---------------------------------------------------------------------------------
    // プレビューと文節の伸縮
    // ---------------------------------------------------------------------------------

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

        // まず、学習済みの候補とかな・カタカナ・原文だけを、すぐに表示する
        candidates = clauseCandidates(learningStore.getLearnedCandidates(activeKana), activeKana, activeRawPart)
            .distinct()
        selectedCandidateIndex = 0
        previewKana = previewFormatter.formatClausePreview(candidates.firstOrNull() ?: activeKana, remainderRawPart)

        // 変換結果が届いたら、候補を差し替える（その間に入力が変わっていたら捨てる）
        converter.convertAsync(activeKana).thenAccept { resultList ->
            synchronized(lock) {
                if (generation == conversionGeneration &&
                    rawBuffer.length >= clauseManager.clauseLength && clauseManager.clauseLength > 0
                ) {
                    val ranked = learningStore.rankCandidates(activeKana, resultList)
                    val fullList = clauseCandidates(ranked, activeKana, activeRawPart)

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

    /** 文節の候補リスト: [base] のあとに、かな・カタカナ・原文（入力した英字）を、まだなければ足す */
    private fun clauseCandidates(base: List<String>, kana: String, raw: String): MutableList<String> {
        val katakana = romajiDetector.toKatakana(kana)
        val list = mutableListOf<String>()
        list.addAll(base)
        list.addIfMissing(kana)
        if (katakana.isNotEmpty() && katakana != kana) list.addIfMissing(katakana)
        list.addIfMissing(raw)
        return list
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

    // ---------------------------------------------------------------------------------
    // 変換の実行
    // ---------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------
    // 確定とリセット
    // ---------------------------------------------------------------------------------

    /** 現在選択中の候補（またはプレビュー文字列）を確定してチャット欄に挿入 */
    fun commitCurrent(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            val hadCandidates = hasCandidates
            val reading = activeReading
            val raw = activeRaw

            // 文節伸縮による部分確定かどうか
            val isPartialClause = clauseManager.isPartial(rawBuffer.length)
            val remainderRaw = if (isPartialClause) clauseManager.split(rawBuffer.toString()).second else ""

            val committedText = textToCommit()
            if (committedText.isNotEmpty()) {
                // 候補ウィンドウから選択・確定した場合は、変換を学習する
                if (hadCandidates) commitTextResolver.recordSelection(reading, raw, committedText)
                insertSafely(insertToChat, committedText)
            }

            if (isPartialClause && remainderRaw.isNotEmpty()) {
                carryOverRemainder(remainderRaw)
            } else {
                reset()
            }
        }
    }

    /** 確定する文字: 候補が出ていれば選択中の候補、なければ変換せずに確定するときの文字 */
    private fun textToCommit(): String =
        if (hasCandidates) {
            candidates.getOrNull(selectedCandidateIndex) ?: candidates.firstOrNull() ?: previewKana
        } else {
            commitTextResolver.resolveWithoutCandidates(rawBuffer.toString(), previewKana)
        }

    /** 文節伸縮で確定された残りの文字列を、次のバッファとして引き継ぐ */
    private fun carryOverRemainder(remainderRaw: String) {
        rawBuffer.clear()
        rawBuffer.append(remainderRaw)
        clauseManager.reset()
        candidates = emptyList()
        selectedCandidateIndex = 0
        updatePreview()
    }

    /** 生のアルファベットのまま確定して流し込む */
    private fun flushAsIs(insertToChat: (String) -> Unit) {
        synchronized(lock) {
            if (rawBuffer.isNotEmpty()) {
                insertSafely(insertToChat, rawBuffer.toString())
            }
            reset()
        }
    }

    /** チャット欄などへの挿入で例外が出ても、MOD の処理は止めない */
    private fun insertSafely(insertToChat: (String) -> Unit, text: String) {
        try {
            insertToChat(text)
        } catch (_: Throwable) {
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
