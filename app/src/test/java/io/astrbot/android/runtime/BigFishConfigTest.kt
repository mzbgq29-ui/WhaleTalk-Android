package io.astrbot.android.runtime

import io.astrbot.android.core.BigFishInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BigFishConfigTest {
    @Test fun appliesLightResourcePolicyAndKeepsAudioDisabled() {
        val original = JSONObject("""{"provider_tts_settings":{"enable":true,"provider_id":"old-tts"},"provider_stt_settings":{"enable":true}}""")
        val light = BigFishInput(input.owner, input.endpoint, input.model, input.key,
            options = io.astrbot.android.core.BigFishOptions(mode = io.astrbot.android.core.BigFishResourceMode.LIGHT))
        val result = BigFishConfig.astrbot(original, light, token)
        val config = result.getJSONObject("agent_runner").getJSONObject("config")
        assertEquals(8, config.getJSONObject("compression").getInt("max_turns"))
        assertEquals(4, config.getJSONObject("misc").getInt("max_steps"))
        assertEquals(768, result.getJSONArray("provider").getJSONObject(0).getJSONObject("custom_extra_body").getInt("max_tokens"))
        assertFalse(result.getJSONObject("provider_tts_settings").getBoolean("enable"))
        assertFalse(result.getJSONObject("provider_stt_settings").getBoolean("enable"))
        assertEquals("old-tts", result.getJSONObject("provider_tts_settings").getString("provider_id"))
        assertTrue(original.getJSONObject("provider_tts_settings").getBoolean("enable"))
        assertEquals("discard", result.getJSONObject("platform_settings").getJSONObject("rate_limit").getString("strategy"))
    }
    @Test fun advancedOptionsRoundTripAndOnlyAdvertiseVisionWhenChosen() {
        val options = io.astrbot.android.core.BigFishOptions(io.astrbot.android.core.BigFishResourceMode.BALANCED, "小鲸鱼", true, true, true, true, true)
        assertEquals(options, BigFishConfig.readOptions(BigFishConfig.optionsJson(options)))
        val advanced = BigFishInput(input.owner, input.endpoint, "gpt-5", input.key, options = options)
        val result = BigFishConfig.astrbot(JSONObject(), advanced, token)
        val model = result.getJSONArray("provider").getJSONObject(0)
        assertEquals("image", model.getJSONArray("modalities").getString(1))
        assertEquals(1536, model.getJSONObject("custom_extra_body").getInt("max_completion_tokens"))
        assertFalse(model.getJSONObject("custom_extra_body").has("max_tokens"))
        assertEquals("/", result.getJSONArray("wake_prefix").getString(0))
        assertEquals(1, result.getJSONArray("wake_prefix").length())
        val platform = result.getJSONObject("platform_settings")
        assertTrue(platform.getBoolean("friend_message_needs_wake_prefix"))
        assertTrue(platform.getBoolean("reply_with_quote")); assertTrue(platform.getBoolean("reply_with_mention"))
        assertTrue(platform.getBoolean("ignore_at_all"))
    }
    private val input = BigFishInput("100001", "https://api.deepseek.com/v1", "deepseek-chat", "sk-test-not-a-real-secret", "100002", "200001")
    private val token = "ab".repeat(32)
    @Test fun createsActualAstrbotProviderAndLocalRunnerContract() {
        val result = BigFishConfig.astrbot(JSONObject(), input, token)
        val source = result.getJSONArray("provider_sources").getJSONObject(0)
        val model = result.getJSONArray("provider").getJSONObject(0)
        assertEquals(BigFishConfig.SOURCE, model.getString("provider_source_id"))
        assertEquals("openai_chat_completion", source.getString("type"))
        assertEquals(input.key, source.getJSONArray("key").getString(0))
        assertEquals(input.model, model.getString("model"))
        assertEquals(BigFishConfig.MODEL, result.getJSONObject("agent_runner").getJSONObject("config").getJSONObject("model").getString("provider_id"))
        assertEquals(1, result.getJSONObject("agent_runner").getJSONObject("config").getJSONObject("model").getInt("request_max_retries"))
        assertEquals("none", result.getJSONObject("provider_settings").getString("computer_use_runtime"))
        // Export the actual builder output for a separate pinned-upstream semantic check.
        val fixture = java.io.File("build/bigfish-fixtures/astrbot.json")
        fixture.parentFile!!.mkdirs(); fixture.writeText(result.toString(2))
    }
    @Test fun bindsAllowListsToRealOnebotSessionIdentity() {
        val result = BigFishConfig.astrbot(JSONObject(), input, token)
        val settings = result.getJSONObject("platform_settings")
        val ids = settings.getJSONArray("id_whitelist")
        assertEquals("qq:FriendMessage:100002", ids.getString(0))
        assertEquals("qq:FriendMessage:100001", ids.getString(1))
        assertEquals("200001", ids.getString(2))
        assertFalse(settings.getBoolean("wl_ignore_admin_on_group"))
        assertTrue(settings.getBoolean("enable_id_white_list"))
        assertTrue(settings.getBoolean("ignore_bot_self_message"))
    }
    @Test fun preservingCustomConfigurationAndRepeatedApplyDoesNotDuplicateProviders() {
        val old = JSONObject("""{"dashboard":{"port":6500},"other":{"value":42},"provider":[{"id":"custom","model":"other"}],"agent_runner":{"config":{"persona":{"persona_id":"custom"}}}}""")
        val result = BigFishConfig.astrbot(BigFishConfig.astrbot(old, input, token), input, token)
        assertEquals(6500, result.getJSONObject("dashboard").getInt("port"))
        assertEquals(42, result.getJSONObject("other").getInt("value"))
        assertEquals(2, result.getJSONArray("provider").length())
        assertEquals("custom", result.getJSONObject("agent_runner").getJSONObject("config").getJSONObject("persona").getString("persona_id"))
        assertFalse(old.has("provider_sources"))
    }
    @Test fun preservesOldPlatformIdentityForExistingMemoriesAndUsesQqForNewInstall() {
        val old=JSONObject("""{"platform":[{"id":"napcat","type":"aiocqhttp"}]}""")
        val migrated=BigFishConfig.astrbot(old,input,token)
        assertEquals("napcat",migrated.getJSONArray("platform").getJSONObject(0).getString("id"))
        assertEquals("napcat:FriendMessage:100002",migrated.getJSONObject("platform_settings").getJSONArray("id_whitelist").getString(0))
        assertEquals("qq",BigFishConfig.astrbot(JSONObject(),input,token).getJSONArray("platform").getJSONObject(0).getString("id"))
    }
    @Test fun savingModelAndReplySettingsKeepsTheSnowLumaBridge() {
        val original = BigFishConfig.astrbot(JSONObject(), input, token)
        val changed = BigFishInput(input.owner, input.endpoint, "gpt-5", input.key, "100003", "200003")
        val updated = BigFishConfig.astrbot(original, changed, token)

        assertEquals(QqPlatform.bridge(original), QqPlatform.bridge(updated))
        assertEquals("gpt-5", updated.getJSONArray("provider").getJSONObject(0).getString("model"))
        assertEquals("qq:FriendMessage:100003", updated.getJSONObject("platform_settings").getJSONArray("id_whitelist").getString(0))
    }
    @Test fun snowLumaBridgeChangesWithItsPortTokenOrEnabledState() {
        val original = BigFishConfig.astrbot(JSONObject(), input, token)
        val bridge = requireNotNull(QqPlatform.bridge(original))
        val platform = requireNotNull(QqPlatform.configuration(original))

        platform.put("ws_reverse_port", 6200)
        assertNotEquals(bridge, QqPlatform.bridge(original))
        platform.put("ws_reverse_port", bridge.port).put("ws_reverse_token", "cd".repeat(32))
        assertNotEquals(bridge, QqPlatform.bridge(original))
        platform.put("ws_reverse_token", token).put("enable", false)
        assertNotEquals(bridge, QqPlatform.bridge(original))
    }
    @Test fun preferencesKeepTheCustomBridgeAndExistingProviderConnection() {
        val original = BigFishConfig.astrbot(JSONObject(), input, token)
        val bridge = requireNotNull(QqPlatform.configuration(original))
        bridge.put("ws_reverse_port", 6200).put("ws_reverse_token", "configured-token").put("enable", false)
        val originalSource = original.getJSONArray("provider_sources").getJSONObject(0)
        originalSource.put("timeout", 42)

        val updated = BigFishConfig.astrbot(original, input, token, preferencesOnly = true, firstSetup = false)

        assertEquals(QqPlatform.bridge(original), QqPlatform.bridge(updated))
        assertEquals(42, updated.getJSONArray("provider_sources").getJSONObject(0).getInt("timeout"))
    }
    @Test fun rejectsInvalidPairingSecret() {
        assertThrows(IllegalArgumentException::class.java) { BigFishConfig.astrbot(JSONObject(), input, "") }
    }
    @Test fun bailianNonStreamingContractDisablesThinkingWithoutAffectingOtherProviders() {
        val ali = BigFishInput("100001", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", input.key)
        val configured = BigFishConfig.astrbot(JSONObject(), ali, token)
        assertFalse(configured.getJSONArray("provider").getJSONObject(0).getJSONObject("custom_extra_body").getBoolean("enable_thinking"))
        assertFalse(BigFishConfig.astrbot(JSONObject(), input, token).getJSONArray("provider").getJSONObject(0).getJSONObject("custom_extra_body").has("enable_thinking"))
    }
}
