package io.astrbot.android.runtime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class LegacySnowPathsTest {
    @Test fun translatesOnlyExactGuestPathsAndKeepsHistoryIdentifiers() {
        val value=JSONObject("""{"path":"/persistent/napcat/config/snowluma/config","items":["/persistent/napcat/snowluma",{"umo":"napcat:FriendMessage:123","prompt":"Discuss /persistent/napcat/snowluma","similar":"/persistent/napcat/snowluma-other"}]}""")
        assertTrue(LegacySnowPaths.rebase(value))
        assertEquals("/persistent/snowluma/config",value.getString("path"))
        assertEquals("/persistent/snowluma",value.getJSONArray("items").getString(0))
        val nested=value.getJSONArray("items").getJSONObject(1)
        assertEquals("napcat:FriendMessage:123",nested.getString("umo"))
        assertEquals("Discuss /persistent/napcat/snowluma",nested.getString("prompt"))
        assertEquals("/persistent/napcat/snowluma-other",nested.getString("similar"))
        assertFalse(LegacySnowPaths.rebase(value))
    }
}
