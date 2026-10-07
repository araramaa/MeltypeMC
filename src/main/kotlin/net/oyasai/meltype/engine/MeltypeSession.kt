package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.conversion.IConverter
import net.oyasai.meltype.conversion.LocalDictionaryConverter
import org.lwjgl.glfw.GLFW

/**
 * 1回のチャット入力セッション（入力バッファ・変換候補・状態遷移）を管理するクラス
 */
class MeltypeSession(
    private val romajiDetector: RomajiDetector,
    private val englishDetector: EnglishDetector,
    private val scoreEngine: ScoreEngine,
    private val slashCommandGate: SlashCommandGate,
    private val converter: IConverter = GoogleCgiConverter()
) {

    /** 現在入力中の生キーストローク（アルファベット列） */
    var rawBuffer: StringBuilder = StringBuilder()
        private set

    /** 現在のひらがな表示テキスト（下線プレビュー用） */
    var previewKana: String = ""
        private set

    /** 変換候補リスト */
    var candidates: List<String> = emptyList()
        private set

    /** 現在選択中の候補インデックス (0-based) */
    var selectedCandidateIndex: Int = 0
        private set

    /** 現在IME入力・変換中かどうか */
    val isComposing: Boolean get() = rawBuffer.isNotEmpty()

    /** 変換候補ウィンドウを表示中かどうか */
    val hasCandidates: Boolean get() = candidates.isNotEmpty()

    /**
     * 文字入力イベント（charTyped）の処理
     * @return true の場合、このMODで入力を消費したためマイクラ標準の入力処理をキャンセルする
     */
    fun onCharTyped(c: Char, currentChatText: String, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        // 1. スラッシュコマンド判定（最優先）
        // スラッシュで始まる場合はIMEをバイパスして直接コマンド入力へ
        if (slashCommandGate.shouldBypassIme(currentChatText, c)) {
            // もし未確定バッファがあれば先に確定
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

        // 3. 入力可能な英字または記号か確認
        if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
            rawBuffer.append(c.lowercaseChar())
            updatePreview()
            return true
        }

        // 英字以外の記号や数字が入力された場合
        if (isComposing) {
            // 直前のバッファを確定してから記号を入力
            commitCurrent(insertToChat)
            insertToChat(c.toString())
            return true
        }

        return false
    }

    /**
     * 特殊キー入力イベント（keyPressed）の処理
     * @return true の場合、このMODでキーを消費した
     */
    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int, insertToChat: (String) -> Unit): Boolean {
        if (!MeltypeConfig.enabled) return false

        // コマンドモード中の場合、IMEキー処理はスキップ
        if (slashCommandGate.isCommandMode && !isComposing) {
            return false
        }

        if (!isComposing) return false

        when (keyCode) {
            // Space: 変換実行 / 次の候補へ
            GLFW.GLFW_KEY_SPACE -> {
                if (hasCandidates) {
                    // 次の候補へ
                    selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
                } else {
                    // 変換開始
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
                    // 候補選択を取り消してひらがな入力状態に戻る
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

            // Tab: 候補送り
            GLFW.GLFW_KEY_TAB -> {
                if (hasCandidates) {
                    val shift = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
                    if (shift) {
                        selectedCandidateIndex = if (selectedCandidateIndex - 1 < 0) candidates.size - 1 else selectedCandidateIndex - 1
                    } else {
                        selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
                    }
                    return true
                }
            }

            // 1〜9の数字キー: 候補のダイレクト選択
            in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 -> {
                if (hasCandidates) {
                    val index = keyCode - GLFW.GLFW_KEY_1
                    if (index < candidates.size) {
                        selectedCandidateIndex = index
                        commitCurrent(insertToChat)
                        return true
                    }
                }
            }
        }

        return false
    }

    /** 入力プレビューと英日判定の更新 */
    private fun updatePreview() {
        val text = rawBuffer.toString()
        if (text.isEmpty()) {
            previewKana = ""
            candidates = emptyList()
            return
        }

        // ひらがな変換プレビューを作成
        previewKana = romajiDetector.toKanaLenient(text)
    }

    /** 変換リクエストの送信 */
    private fun triggerConversion() {
        val text = previewKana.ifEmpty { rawBuffer.toString() }
        val eval = scoreEngine.evaluate(rawBuffer.toString(), isFinal = true)

        // 英語スコアが圧倒的に高い場合はそのまま英字を第一候補に
        if (eval.verdict == Verdict.ENGLISH) {
            candidates = listOf(rawBuffer.toString())
            selectedCandidateIndex = 0
            return
        }

        // 日本語かな漢字変換を非同期実行
        converter.convertAsync(text).thenAccept { resultList ->
            val list = resultList.take(MeltypeConfig.maxCandidates).toMutableList()
            if (!list.contains(text)) list.add(text)
            if (!list.contains(rawBuffer.toString())) list.add(rawBuffer.toString())

            candidates = list
            selectedCandidateIndex = 0
        }
    }

    /** 現在選択中の候補（またはプレビュー文字列）を確定してチャット欄に挿入 */
    fun commitCurrent(insertToChat: (String) -> Unit) {
        val committedText = when {
            hasCandidates -> candidates[selectedCandidateIndex]
            previewKana.isNotEmpty() -> {
                // Spaceを押さずにEnterした場合は、ScoreEngineで英単語かひらがなかを自動判定
                val eval = scoreEngine.evaluate(rawBuffer.toString(), isFinal = true)
                if (eval.verdict == Verdict.ENGLISH) {
                    rawBuffer.toString()
                } else {
                    previewKana
                }
            }
            else -> rawBuffer.toString()
        }

        if (committedText.isNotEmpty()) {
            insertToChat(committedText)
        }
        reset()
    }

    /** 生のアルファベットのまま確定して流し込む */
    private fun flushAsIs(insertToChat: (String) -> Unit) {
        if (rawBuffer.isNotEmpty()) {
            insertToChat(rawBuffer.toString())
        }
        reset()
    }

    /** 入力状態をリセット */
    fun reset() {
        rawBuffer.clear()
        previewKana = ""
        candidates = emptyList()
        selectedCandidateIndex = 0
    }
}
