package io.astrbot.android.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class QqKnownAccountsTest {
    @Test fun extractsUinFieldsFromJson() {
        val text = """{"sessionHint":"keep","account":"123456789","nested":{"uin":"987654321"}}"""
        assertEquals(listOf("123456789", "987654321"), QqKnownAccounts.extractUins(text))
    }

    @Test fun scansNumericDirectoriesAndJsonFiles() {
        val root = createTempDirectory("qq-home").toFile()
        try {
            File(root, ".config/QQ/123456").mkdirs()
            val accounts = File(root, ".config/QQ/accounts.json")
            accounts.writeText("""{"uin":"234567890"}""")
            File(root, "Documents/Tencent Files/345678901").mkdirs()
            val listed = QqKnownAccounts.list(root).map { it.uin }
            assertTrue(listed.containsAll(listOf("123456", "234567890", "345678901")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun ignoresOutsideOrInvalidEntries() {
        val root = createTempDirectory("qq-home").toFile()
        try {
            File(root, ".config/QQ/not-an-account").mkdirs()
            File(root, ".config/QQ/12").mkdirs()
            assertTrue(QqKnownAccounts.list(root).isEmpty())
            assertTrue(QqKnownAccounts.extractUins("""{"uin":"12"}""").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
