package io.astrbot.android.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqHotUpdateGuardTest {
    @Test fun leavesPinnedConfigUnchanged() {
        val document = JSONObject().put("curVersion", "3.2.35-54395").put("onErrorVersions", JSONArray())
        val decision = QqHotUpdateGuard.inspect(document, "3.2.35-54395")
        assertEquals("already-pinned", decision.action)
        assertFalse(decision.isolated)
        assertNull(decision.message)
    }

    @Test fun isolatesNewerVersionWithoutDroppingAccountFields() {
        val document = JSONObject()
            .put("curVersion", "3.2.36-99999")
            .put("prevVersion", "3.2.35-54395")
            .put("sessionHint", "keep-me")
        val decision = QqHotUpdateGuard.inspect(document, "3.2.35-54395")
        assertTrue(decision.isolated)
        assertEquals("3.2.36-99999", decision.current)
        val updated = requireNotNull(decision.updated)
        assertEquals("3.2.35-54395", updated.getString("curVersion"))
        assertEquals("3.2.35-54395", updated.getString("prevVersion"))
        assertEquals("keep-me", updated.getString("sessionHint"))
        val errors = updated.getJSONArray("onErrorVersions")
        assertEquals("3.2.36-99999", errors.getString(0))
        assertTrue(decision.message!!.contains("3.2.36-99999"))
        assertEquals("3.2.36-99999", document.getString("curVersion"))
    }

    @Test fun readsCurrentVersionAlias() {
        val document = JSONObject().put("currentVersion", "3.2.36-1")
        assertEquals("3.2.36-1", QqHotUpdateGuard.currentVersion(document))
        val decision = QqHotUpdateGuard.inspect(document, "3.2.35-54395")
        assertTrue(decision.isolated)
        assertEquals("3.2.35-54395", decision.updated!!.getString("curVersion"))
    }
}
