package net.oyasai.meltype

import net.oyasai.meltype.engine.HashSlashMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 「#/」で始まる発言の、先頭の「#」を外す部品のテスト */
class HashSlashMessageTest {

    @Test
    fun testStripsLeadingHashBeforeSlash() {
        assertEquals("/tp 100 64 100", HashSlashMessage.strip("#/tp 100 64 100"))
        // WorldEdit のコマンド（スラッシュ2つ）も、先頭の # だけを外す
        assertEquals("//br smooth", HashSlashMessage.strip("#//br smooth"))
        assertEquals("/", HashSlashMessage.strip("#/"))
    }

    @Test
    fun testKeepsOtherMessagesAsIs() {
        // 先頭が「#/」でないものは、そのまま
        assertEquals("こんにちは", HashSlashMessage.strip("こんにちは"))
        assertEquals("#hello", HashSlashMessage.strip("#hello"))
        assertEquals("# /tp", HashSlashMessage.strip("# /tp"))
        assertEquals("/tp", HashSlashMessage.strip("/tp"))
        assertEquals("", HashSlashMessage.strip(""))
        // 文の途中の「#/」は、外さない
        assertEquals("見て #/tp を", HashSlashMessage.strip("見て #/tp を"))
    }
}
