package io.astrbot.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonaTemplatesTest {
    @Test fun bundledWarehouseHasUniqueUsableTemplatesAndChineseCompanions() {
        val data=JSONObject(File("src/main/assets/bigfish/persona-library.json").readText())
        val items=PersonaTemplates.parse(data)
        assertEquals(286,items.size)
        assertEquals(6,items.count { it.id.startsWith("bigfish-") })
        assertTrue(items.any { "鲸鱼" in it.name })
        assertEquals(items.size,items.map { it.id }.toSet().size)
        val row=data.getJSONArray("items").getJSONObject(0)
        data.getJSONArray("items").put(JSONObject(row.toString()))
        assertThrows(IllegalArgumentException::class.java) { PersonaTemplates.parse(data) }
    }
    @Test fun publicFeedUsesChinesePromptAndIgnoresUntranslatedRows() {
        val array=JSONArray("""[{"id":1,"zh":{"title":"中文角色","description":"中文设定","prompt":"English prompt","remark":"描述"},"tags":["life"]},{"id":2,"en":{"title":"other"}}]""")
        val templates=PersonaTemplates.parse(PersonaTemplates.source(array))
        assertEquals(1,templates.size);assertEquals("中文设定",templates.single().prompt)
        assertEquals(listOf("life"),templates.single().tags)
    }
    @Test fun importsCharacterCardAndAstrbotJsonWithoutLosingPersonality() {
        val card=JSONObject("""{"spec":"chara_card_v2","data":{"name":"小鲸","description":"温柔","personality":"幽默","scenario":"海边"}}""")
        val imported=PersonaTemplates.importCard(card)
        assertEquals("小鲸",imported.getString("persona_id"))
        for(word in listOf("温柔","幽默","海边"))assertTrue(imported.getString("system_prompt").contains(word))
        assertEquals(imported.getString("system_prompt"),PersonaTemplates.importCard(imported).getString("system_prompt"))
        assertThrows(IllegalArgumentException::class.java) { PersonaTemplates.importCard(JSONObject().put("name","empty")) }
    }
    @Test fun applyingPersonaChangesOnlyLocalRunnerPersonaAndKeepsModelAndCompression() {
        val profile=JSONObject("""{"agent_runner":{"runner_type":"local","config":{"persona":{"persona_id":"before","other":true},"model":{"provider_id":"my-model"},"compression":{"max_turns":20}}},"unknown":42}""")
        val result=PersonaTemplates.applyToProfile(profile,"after")
        val config=result.getJSONObject("agent_runner").getJSONObject("config")
        assertEquals("after",config.getJSONObject("persona").getString("persona_id"))
        assertTrue(config.getJSONObject("persona").getBoolean("other"))
        assertEquals("my-model",config.getJSONObject("model").getString("provider_id"))
        assertEquals(20,config.getJSONObject("compression").getInt("max_turns"))
        assertEquals(42,result.getInt("unknown"))
        assertEquals("before",profile.getJSONObject("agent_runner").getJSONObject("config").getJSONObject("persona").getString("persona_id"))
        profile.getJSONObject("agent_runner").put("runner_type","external")
        assertThrows(IllegalArgumentException::class.java) { PersonaTemplates.applyToProfile(profile,"after") }
    }
}
