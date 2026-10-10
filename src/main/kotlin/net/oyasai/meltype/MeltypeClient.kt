package net.oyasai.meltype

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.client.input.CharacterEvent
import net.oyasai.meltype.conversion.GoogleCgiConverter
import net.oyasai.meltype.engine.EnglishDetector
import net.oyasai.meltype.engine.MeltypeSession
import net.oyasai.meltype.engine.RomajiDetector
import net.oyasai.meltype.engine.ScoreEngine
import net.oyasai.meltype.engine.SlashCommandGate
import net.oyasai.meltype.gui.CompositionRenderer
import org.slf4j.LoggerFactory

/**
 * Meltype MC クライアントエントリポイント
 * Fabric Screen API を使用して、マッピング非依存かつ安全・確実にキーボードイベントを処理します。
 */
object MeltypeClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("MeltypeMC")

    lateinit var romajiDetector: RomajiDetector
        private set
    lateinit var englishDetector: EnglishDetector
        private set
    lateinit var scoreEngine: ScoreEngine
        private set
    lateinit var slashCommandGate: SlashCommandGate
        private set
    lateinit var session: MeltypeSession
        private set

    override fun onInitializeClient() {
        logger.info("[MeltypeMC] Initializing Meltype Japanese Input (Fabric Screen API mode)...")

        // 1. 各エンジンの初期化
        romajiDetector = RomajiDetector()
        englishDetector = EnglishDetector.loadFromResource("/dictionaries/english-words.txt")
        scoreEngine = ScoreEngine(romajiDetector, englishDetector)
        slashCommandGate = SlashCommandGate()

        // 2. セッションの構築
        session = MeltypeSession(
            romajiDetector = romajiDetector,
            englishDetector = englishDetector,
            scoreEngine = scoreEngine,
            slashCommandGate = slashCommandGate,
            converter = GoogleCgiConverter()
        )

        // 3. Fabric Screen API のライフサイクルイベント登録（BEFORE_INIT と AFTER_INIT 両対応で確実にアタッチ）
        ScreenEvents.BEFORE_INIT.register(ScreenEvents.BeforeInit { _, screen, _, _ ->
            registerScreenHandlers(screen)
        })
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ ->
            registerScreenHandlers(screen)
        })

        logger.info("[MeltypeMC] Successfully initialized! Auto English/Japanese detection and '/' command mode are ready.")
    }

    private val registeredScreens = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Screen, Boolean>())

    private fun registerScreenHandlers(screen: Screen) {
        if (!isTextInputActive(screen)) return
        if (!registeredScreens.add(screen)) return // 二重登録防止

        logger.info("[MeltypeMC] Attaching input handlers to screen: ${screen.javaClass.simpleName}")
        session.resetForNewScreen()

        // 特殊キー入力（Space, Enter, Backspace, Tab, 矢印キー等）のインターセプト
        ScreenKeyboardEvents.allowKeyPress(screen).register(ScreenKeyboardEvents.AllowKeyPress { s, keyEvent ->
            if (!isTextInputActive(s)) return@AllowKeyPress true

            try {
                val handled = session.onKeyPressed(keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers()) { commitText ->
                    insertTextToScreen(s, commitText)
                }
                // Meltypeで処理された場合はバニラ画面の通常キー処理をキャンセル
                !handled
            } catch (e: Throwable) {
                logger.error("[MeltypeMC] Error in allowKeyPress", e)
                true // 例外時はバニラ通常処理に安全にフォールバック
            }
        })

        // マウスクリックイベント（インジケータークリックでの入力モード切り替え）
        ScreenMouseEvents.allowMouseClick(screen).register(ScreenMouseEvents.AllowMouseClick { s, mouseButtonEvent ->
            try {
                if (mouseButtonEvent.button() == 0) { // 左クリック
                    if (CompositionRenderer.isIndicatorClicked(s, mouseButtonEvent.x(), mouseButtonEvent.y())) {
                        session.toggleInputMode { commitText ->
                            insertTextToScreen(s, commitText)
                        }
                        return@AllowMouseClick false // クリックを消費
                    }
                }
            } catch (e: Throwable) {
                logger.error("[MeltypeMC] Error in allowMouseClick", e)
            }
            true
        })

        // 文字入力イベント（charTyped）のインターセプト
        ScreenKeyboardEvents.allowCharType(screen).register(ScreenKeyboardEvents.AllowCharType { s, charEvent ->
            if (!isTextInputActive(s)) return@AllowCharType true

            try {
                val c = charEvent.codepoint().toChar()
                val currentText = getCurrentTextFromScreen(s)
                val handled = session.onCharTyped(c, currentText) { commitText ->
                    insertTextToScreen(s, commitText)
                }
                // Meltypeで処理された場合はバニラ画面への文字直接入力をキャンセル
                !handled
            } catch (e: Throwable) {
                logger.error("[MeltypeMC] Error in allowCharType", e)
                true // 例外時はバニラ通常処理に安全にフォールバック
            }
        })

        // 描画フェーズ（インライン候補ウィンドウ＆下線プレビュー＆モードインジケーター）
        ScreenEvents.afterForeground(screen).register(ScreenEvents.AfterForeground { s, extractor, mouseX, mouseY, _ ->
            if (isTextInputActive(s)) {
                try {
                    CompositionRenderer.render(s, extractor, session, mouseX, mouseY)
                } catch (_: Throwable) {
                }
            }
        })

        // 画面を閉じた時のセッションリセット
        ScreenEvents.remove(screen).register(ScreenEvents.Remove { _ ->
            try {
                session.resetForNewScreen()
                slashCommandGate.reset()
            } catch (_: Throwable) {
            }
        })
    }

    /**
     * テキスト入力がアクティブな画面・ウィジェットであるかを安全に判定
     */
    private fun isTextInputActive(screen: Screen): Boolean {
        val className = screen.javaClass.name
        if (screen is ChatScreen || screen is AnvilScreen || screen is AbstractSignEditScreen || screen is BookEditScreen) {
            return true
        }
        if (className.contains("Chat") || className.contains("Sign") || className.contains("Anvil") || className.contains("Book")) {
            return true
        }
        val focused = screen.focused
        if (focused is EditBox && focused.isFocused && focused.visible) {
            return true
        }
        if (focused != null && focused.javaClass.name.contains("EditBox")) {
            return true
        }
        return false
    }

    /**
     * 現在の入力テキスト（スラッシュコマンド判定用）を取得
     */
    private fun getCurrentTextFromScreen(screen: Screen): String {
        try {
            val focused = screen.focused
            if (focused is EditBox) {
                return focused.value
            }
        } catch (_: Throwable) {
        }
        return ""
    }

    /**
     * 変換確定したテキストを画面の対象テキストフィールドへ直接挿入
     */
    private fun insertTextToScreen(screen: Screen, text: String) {
        try {
            if (screen is ChatScreen) {
                screen.insertText(text, false)
                return
            }
            val focused = screen.focused
            if (focused is EditBox) {
                focused.insertText(text)
                return
            }
            // 看板（AbstractSignEditScreen）や本（BookEditScreen）向けフォールバック
            for (c in text) {
                screen.charTyped(CharacterEvent(c.code))
            }
        } catch (e: Throwable) {
            logger.error("[MeltypeMC] Failed to insert text to screen: $text", e)
        }
    }
}
