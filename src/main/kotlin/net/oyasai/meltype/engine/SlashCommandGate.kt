package net.oyasai.meltype.engine

import net.oyasai.meltype.config.MeltypeConfig

/**
 * スラッシュ ('/') 入力によるコマンド入力モードの自動切替ゲート
 * ユーザー指定機能: スラッシュを入力したときは勝手にコマンド入力モードになって欲しい
 */
class SlashCommandGate {

    /** 現在コマンド直接入力モード中かどうか */
    var isCommandMode: Boolean = false
        private set

    /**
     * チャット入力ボックスの現在のテキストと、新しく入力された文字から
     * コマンドモードへ移行すべきかを判定します。
     *
     * @param currentText チャット欄に既に入力されている文字列
     * @param newChar 新たに入力された文字
     * @return true の場合、Meltype IME をバイパスして直接文字を入力すべき
     */
    fun shouldBypassIme(currentText: String, newChar: Char): Boolean {
        if (!MeltypeConfig.autoCommandModeOnSlash) {
            return false
        }

        // 1. チャット欄が空、または選択全消し状態で '/' が入力された場合 -> 即座にコマンドモード開始
        if (currentText.isEmpty() && newChar == '/') {
            isCommandMode = true
            return true
        }

        // 2. 既に先頭が '/' で始まっている場合
        if (currentText.startsWith("/")) {
            isCommandMode = true

            // 発展機能: /tell, /msg, /w などの後、空白2つ以降のメッセージ部分は日本語入力を許可する判定も可能
            if (shouldAllowJapaneseInCommandArgs(currentText)) {
                return false
            }

            return true
        }

        // 先頭が '/' でない場合、コマンドモードは解除
        isCommandMode = false
        return false
    }

    /**
     * 特定のチャット系コマンド（/tell, /msg など）のメッセージ引数部分のみ
     * 日本語入力を復帰させる判定
     */
    private fun shouldAllowJapaneseInCommandArgs(text: String): Boolean {
        val parts = text.split(" ")
        if (parts.size < 3) return false
        val cmd = parts[0].lowercase()
        return cmd in listOf("/tell", "/msg", "/w", "/whisper", "/me", "/say")
    }

    /** チャット画面終了時や送信時に状態をリセット */
    fun reset() {
        isCommandMode = false
    }
}
