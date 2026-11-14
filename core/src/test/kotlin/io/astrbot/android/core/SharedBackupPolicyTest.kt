package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class SharedBackupPolicyTest {
    @Test fun keepsLoginAndConfigButDropsCaches() {
        assertTrue(SharedBackupPolicy.retain("snowluma/bootstrap-password"))
        assertTrue(SharedBackupPolicy.retain("astrbot/data/cmd_config.json"))
        assertTrue(SharedBackupPolicy.retain("astrbot/data/plugins/demo/main.py"))
        assertTrue(SharedBackupPolicy.retain("home/.config/QQ/versions/config.json"))
        assertFalse(SharedBackupPolicy.retain("home/.config/QQ/Cache/index"))
        assertFalse(SharedBackupPolicy.retain("home/.config/QQ/GPUCache/data_0"))
        assertFalse(SharedBackupPolicy.retain("astrbot/data/logs/astrbot.log"))
        assertFalse(SharedBackupPolicy.retain("home/.config/QQ/nt_qq/nt_data/pic/a.jpg"))
        assertFalse(SharedBackupPolicy.retain("persistent/other"))
        assertFalse(SharedBackupPolicy.included("home/.cache/thumb"))
    }

    @Test fun keepsLatestFiveArchives() {
        val items = (1..7).map { "id$it" to it.toLong() }
        assertEquals(listOf("id2", "id1"), SharedBackupPolicy.idsToDelete(items, 5))
        assertEquals(emptyList<String>(), SharedBackupPolicy.idsToDelete(items.take(5)))
    }
}
