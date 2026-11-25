// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal data class PersonaTemplate(val id: String, val name: String, val prompt: String,
    val description: String, val tags: List<String>, val source: String, val card: JSONObject? = null, val license: String = "")
internal object PersonaTemplates {
    fun parse(value: JSONObject): List<PersonaTemplate> {
        val array = value.getJSONArray("items"); require(array.length() in 1..5000)
        val seen = mutableSetOf<String>()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val id=item.getString("id");val name=item.getString("name").trim();val rawCard=item.optJSONObject("card");val prompt=(rawCard?.let{TavernCharacterCard.convert(it).getString("system_prompt")}?:item.getString("prompt")).trim()
            require(id.length in 1..120 && seen.add(id) && name.length in 1..160 && prompt.length in 1..65536)
            PersonaTemplate(id,name,prompt,item.optString("description").take(1000),
                item.optJSONArray("tags")?.let { tags -> (0 until tags.length()).map { tags.getString(it).take(40) } }.orEmpty(),item.optString("source","公开人设库"),rawCard,item.optString("license"))
        }
    }
    fun source(array: JSONArray): JSONObject {
        require(array.length() in 1..5000)
        val items=JSONArray()
        for(i in 0 until array.length()) {
            val row=array.getJSONObject(i);val zh=row.optJSONObject("zh") ?: row.optJSONObject("zh-Hans") ?: continue
            items.put(JSONObject().put("id","aishort-${row.get("id")}").put("name",zh.getString("title"))
                .put("prompt",zh.optString("description").ifBlank { zh.getString("prompt") })
                .put("description",zh.optString("remark")).put("tags",row.optJSONArray("tags") ?: JSONArray()).put("source","AI Short"))
        }
        return JSONObject().put("schema",1).put("items",items).also { parse(it) }
    }
    /** Supports AstrBot persona JSON and portable Character Card v2/v3 JSON. */
    fun importCard(value: JSONObject): JSONObject {
        if(!value.has("persona_id"))return TavernCharacterCard.convert(value)
        val name=value.getString("persona_id").trim();val text=value.optString("system_prompt")
        require(name.length in 1..120&&text.isNotBlank()&&text.length<=65536){"人设缺少名称或设定，或超过大小限制"}
        val dialogs=value.optJSONArray("begin_dialogs")?:JSONArray()
        require(dialogs.length()%2==0&&(0 until dialogs.length()).all{dialogs.opt(it) is String}){"预设对话格式不正确"}
        return JSONObject().put("persona_id",name).put("system_prompt",text).put("begin_dialogs",dialogs)
    }

    fun applyToProfile(original: JSONObject, persona: String): JSONObject {
        val value=JSONObject(original.toString())
        val runner=value.optJSONObject("agent_runner") ?: JSONObject().put("runner_type","local").put("config",JSONObject()).also { value.put("agent_runner",it) }
        require(runner.optString("runner_type","local")=="local") { "当前使用第三方 Agent，请在引擎设置中选择本机模型执行方式" }
        val config=runner.optJSONObject("config") ?: JSONObject().also { runner.put("config",it) }
        val role=config.optJSONObject("persona") ?: JSONObject().also { config.put("persona",it) }
        role.put("persona_id",persona)
        return value
    }
}
internal class PersonaWarehouseRepository(private val context: Context) {
    private val cache=File(context.filesDir,"manager/persona-library.json")
    private val preferences=context.getSharedPreferences("bigfish-personas",Context.MODE_PRIVATE)
    fun favorites(): Set<String> = preferences.getStringSet("favorites",emptySet()).orEmpty().toSet()
    fun favorite(id: String,on: Boolean) {
        val next=favorites().toMutableSet();if(on)next.add(id) else next.remove(id)
        check(preferences.edit().putStringSet("favorites",next).commit()) { "收藏未能保存" }
    }
    suspend fun importLocal(bytes: ByteArray): PersonaTemplate = withContext(Dispatchers.IO) {
        val card=TavernCharacterCard.decode(bytes)
        require(!card.has("persona_id")){"这是 AstrBot 人设 JSON，请在人设页导入；酒馆仓库接收角色卡 PNG/JSON"}
        val converted=TavernCharacterCard.convert(card)
        val raw=card.toString().toByteArray()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        val data=TavernCharacterCard.data(card)
        val item=JSONObject().put("id","local-"+hash.take(24)).put("name",converted.getString("persona_id"))
            .put("description",data.optString("creator_notes").ifBlank { data.optString("scenario") }.take(300))
            .put("tags",JSONArray(listOf("tavern","local"))).put("source",data.optString("creator").ifBlank{"手机导入"})
            .put("card",card)
        val folder=File(context.filesDir,"manager/tavern-cards").apply{mkdirs()}
        io.astrbot.android.runtime.DurableFiles.write(File(folder,"$hash.json"),item)
        PersonaTemplates.parse(JSONObject().put("items",JSONArray().put(item))).single()
    }
    private fun localCards(): List<PersonaTemplate> = File(context.filesDir,"manager/tavern-cards").listFiles().orEmpty()
        .filter{it.isFile&&!java.nio.file.Files.isSymbolicLink(it.toPath())&&it.extension=="json"&&it.length()<=TavernCharacterCard.MAX_JSON*2}
        .sortedByDescending{it.lastModified()}.take(2000).mapNotNull{file->runCatching{
            PersonaTemplates.parse(JSONObject().put("items",JSONArray().put(JSONObject(file.readText())))).single()
        }.getOrNull()}
    private fun bundled()=context.assets.open("bigfish/persona-library.json").bufferedReader().use { JSONObject(it.readText()) }
    suspend fun load(): List<PersonaTemplate> = withContext(Dispatchers.IO) {
        val original=PersonaTemplates.parse(bundled())
        val tavern=context.assets.open("bigfish/tavern-library.json").bufferedReader().use{PersonaTemplates.parse(JSONObject(it.readText()))}
        val online=runCatching { if(cache.isFile&&cache.length()<=12*1024*1024)PersonaTemplates.parse(JSONObject(cache.readText())) else emptyList() }.getOrDefault(emptyList())
        if(online.isEmpty())localCards()+tavern+original else (localCards()+tavern+original.filter { it.id.startsWith("bigfish-") }+online).distinctBy { it.id }
    }
    suspend fun synchronize(): List<PersonaTemplate> = withContext(Dispatchers.IO) {
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).build()
        try {
            var result: JSONObject?=null
            val urls=listOf("https://cdn.jsdelivr.net/gh/rockbenben/ChatGPT-Shortcut@main/src/data/prompt.json", "https://raw.githubusercontent.com/rockbenben/ChatGPT-Shortcut/main/src/data/prompt.json")
            for(url in urls) {
                try {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        check(response.isSuccessful)
                        val source=requireNotNull(response.body).source();source.request(12L*1024*1024+1)
                        require(source.buffer.size<=12L*1024*1024)
                        result=PersonaTemplates.source(JSONArray(source.readUtf8()))
                    }
                    if(result!=null)break
                } catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e }
            }
            val data=requireNotNull(result) { "公开人设库暂时无法连接，离线内容和收藏仍可使用" }
            io.astrbot.android.runtime.DurableFiles.write(cache,data)
            load()
        } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll() }
    }
}
