package net.oyasai.meltype

import net.oyasai.meltype.config.MeltypeConfig
import net.oyasai.meltype.engine.*
import org.lwjgl.glfw.GLFW
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
/** 設定（保存・読み込み・切り替えキー）のテスト */
class ConfigTest : MeltypeTestBase() {

    /**
     * 設定の保存と読み込みテスト
     */
    @Test
    fun testMeltypeConfigPersistence() {
        val originalCandidates = MeltypeConfig.maxCandidates
        val originalInitialMode = MeltypeConfig.initialInputMode

        try {
            MeltypeConfig.maxCandidates = 7
            MeltypeConfig.initialInputMode = InputMode.HYBRID
            MeltypeConfig.save()

            // 変更が保持されていること
            assertEquals(7, MeltypeConfig.maxCandidates)
            assertEquals(InputMode.HYBRID, MeltypeConfig.initialInputMode)

            // 再ロード
            MeltypeConfig.load()
            assertEquals(7, MeltypeConfig.maxCandidates)
            assertEquals(InputMode.HYBRID, MeltypeConfig.initialInputMode)
        } finally {
            MeltypeConfig.maxCandidates = originalCandidates
            MeltypeConfig.initialInputMode = originalInitialMode
            // setUp で一時的に変えた値を、設定ファイルへ保存しないよう先に戻す
            MeltypeConfig.useGoogleCgi = originalUseGoogleCgi
            MeltypeConfig.learningEnabled = originalLearningEnabled
            MeltypeConfig.save()
        }
    }

    @Test
    fun testMeltypeConfigPropertiesPersistence() {
        val tempFile = java.io.File.createTempFile("meltype_test_config", ".properties")
        try {
            val props = java.util.Properties()
            props.setProperty("japaneseThreshold", "3")
            props.setProperty("useGoogleCgi", "false")

            tempFile.writer(java.nio.charset.StandardCharsets.UTF_8).use {
                props.store(it, "Test")
            }

            // 直接プロパティ読み込みの検証
            val loaded = java.util.Properties()
            tempFile.reader(java.nio.charset.StandardCharsets.UTF_8).use { loaded.load(it) }

            assertEquals(3, loaded.getProperty("japaneseThreshold")?.toIntOrNull())
            assertEquals(false, loaded.getProperty("useGoogleCgi")?.toBooleanStrictOrNull())
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 設定画面で登録した切り替えキーで入力モードが切り替わり、通常の入力キーは登録できないことの検証
     */
    @Test
    fun testCustomToggleKey() {
        assertFalse(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_A, 0))
        assertFalse(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_SPACE, 0))
        assertFalse(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_ENTER, 0))
        assertFalse(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0))
        assertTrue(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_F8, 0))
        assertTrue(MeltypeConfig.isAllowedToggleKey(GLFW.GLFW_KEY_J, GLFW.GLFW_MOD_CONTROL))

        val oldCode = MeltypeConfig.toggleKeyCode
        val oldMods = MeltypeConfig.toggleKeyModifiers
        try {
            MeltypeConfig.toggleKeyCode = GLFW.GLFW_KEY_F8
            MeltypeConfig.toggleKeyModifiers = 0
            val session = MeltypeSession(
                romajiDetector = romajiDetector,
                englishDetector = englishDetector,
                scoreEngine = scoreEngine,
                slashCommandGate = slashGate,
                learningStore = newTempLearningStore()
            )
            val before = session.currentInputMode
            assertTrue(session.onKeyPressed(GLFW.GLFW_KEY_F8, 0, 0) {})
            assertNotEquals(before, session.currentInputMode)
            // 修飾キーが違う場合は反応しない
            val after = session.currentInputMode
            assertFalse(session.onKeyPressed(GLFW.GLFW_KEY_F8, 0, GLFW.GLFW_MOD_CONTROL) {})
            assertEquals(after, session.currentInputMode)
        } finally {
            MeltypeConfig.toggleKeyCode = oldCode
            MeltypeConfig.toggleKeyModifiers = oldMods
        }
    }
}
