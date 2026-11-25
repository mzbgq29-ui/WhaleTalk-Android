// SPDX-License-Identifier: MIT
package io.astrbot.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** Text-only V1/V2/V3 adapter. Imported scripts and HTML are retained as data, never executed. */
internal object TavernCharacterCard {
    const val MAX_FILE = 16 * 1024 * 1024
    const val MAX_JSON = 2 * 1024 * 1024
    private val png=byteArrayOf(-119,80,78,71,13,10,26,10)
    fun decode(bytes: ByteArray): JSONObject {
        require(bytes.size in 1..MAX_FILE) { "角色卡文件需在 16 MB 以内" }
        if(!bytes.take(8).toByteArray().contentEquals(png)) {
            require(bytes.size<=MAX_JSON) { "JSON 角色卡需在 2 MB 以内" }
            return try { JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) }
            catch(_:org.json.JSONException){error("文件不是有效角色卡，请使用原始 PNG 或 JSON 文件")}
        }
        var pos=8;var found: JSONObject?=null;var v3: JSONObject?=null;var ended=false
        fun intAt(offset:Int):Long=(0..3).fold(0L){v,i->(v shl 8) or (bytes[offset+i].toLong() and 255)}
        while(pos<bytes.size) {
            require(pos+12<=bytes.size){ "PNG 角色卡不完整" }
            val length=intAt(pos);require(length<=MAX_FILE&&pos+12L+length<=bytes.size){ "PNG 数据长度异常" }
            val count=length.toInt();val type=bytes.copyOfRange(pos+4,pos+8).toString(Charsets.US_ASCII)
            val crc=CRC32().apply{update(bytes,pos+4,count+4)}.value
            require(crc==intAt(pos+8+count)){ "PNG 角色卡校验失败，文件可能已损坏" }
            if(type in setOf("tEXt","zTXt","iTXt")) {
                val chunk=bytes.copyOfRange(pos+8,pos+8+count);val zero=chunk.indexOf(0)
                if(zero>0) {
                    val key=chunk.copyOfRange(0,zero).toString(Charsets.ISO_8859_1)
                    if(key in setOf("chara","ccv3")) {
                        var start=zero+1;var compressed=false
                        when(type) {
                            "zTXt"->{require(start<chunk.size&&chunk[start].toInt()==0);start++;compressed=true}
                            "iTXt"->{
                                require(start+2<=chunk.size);compressed=chunk[start].toInt()==1
                                require(chunk[start].toInt() in 0..1 && chunk[start+1].toInt()==0);start+=2
                                repeat(2){while(start<chunk.size&&chunk[start].toInt()!=0)start++;require(start<chunk.size);start++}
                            }
                        }
                        val raw=chunk.copyOfRange(start,chunk.size)
                        val text=if(compressed) InflaterInputStream(ByteArrayInputStream(raw)).use { readLimited(it,MAX_JSON*2) } else raw
                        require(text.size<=MAX_JSON*2)
                        val decoded=if(text.toString(Charsets.UTF_8).trimStart().startsWith("{"))text else
                            Base64.getDecoder().decode(text.toString(Charsets.US_ASCII).filterNot{it.isWhitespace()})
                        require(decoded.size<=MAX_JSON)
                        val value=JSONObject(decoded.toString(Charsets.UTF_8))
                        if(key=="ccv3")v3=value else found=value
                    }
                }
            }
            pos+=count+12
            if(type=="IEND"){require(count==0);ended=true;break}
        }
        require(ended){ "PNG 角色卡缺少结尾" }
        return v3?:found?:error("这张 PNG 没有酒馆角色数据，请使用原始角色卡文件")
    }
    fun readLimited(input:java.io.InputStream,max:Int=MAX_FILE):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=max){"角色卡文件超过大小限制"};out.write(buffer,0,n)}
        return out.toByteArray()
    }
    fun data(value:JSONObject):JSONObject=value.optJSONObject("data")?:value
    fun greeting(value:JSONObject,user:String="用户"):String {
        val card=data(value);return render(card.optString("first_mes"),card.optString("name"),user)
    }
    fun render(text:String,name:String,user:String):String=text.replace(Regex("\\{\\{\\s*(char|user|original)\\s*}}",RegexOption.IGNORE_CASE)) {
        when(it.groupValues[1].lowercase()){ "char"->name;"user"->user;else->"" }
    }
    fun convert(value:JSONObject,user:String="用户"):JSONObject {
        val card=data(value);val name=card.optString("name").trim()
        require(name.length in 1..120){"角色卡缺少有效名称"}
        val sections=mutableListOf<String>()
        sections += "角色：$name\n默认用中文自然交流，遵循下列角色设定；除非用户指定其他语言。"
        val fields=listOf("system_prompt" to "角色指令", "description" to "人物设定", "personality" to "性格与说话风格", "scenario" to "故事背景", "mes_example" to "对话示例", "post_history_instructions" to "回复补充")
        var substance=false
        for((key,label) in fields) card.optString(key).trim().takeIf{it.isNotEmpty()}?.let {
            sections += "$label：\n${render(if(key=="mes_example")it.replace("\\n","\n") else it,name,user)}";substance=true
        }
        val opening=greeting(value,user)
        if(opening.isNotBlank())sections+="开场参考（只用于建立场景，不要每次回复重复）：\n$opening"
        val entries=card.optJSONObject("character_book")?.optJSONArray("entries")
        val lore=mutableListOf<String>()
        if(entries!=null) {
            require(entries.length()<=1000){"世界书条目过多，请精简后导入"}
            (0 until entries.length()).mapNotNull{entries.optJSONObject(it)}.filter{it.optBoolean("enabled",true)}
                .sortedBy{it.optInt("insertion_order",100)}.forEach { item->
                    val content=item.optString("content").trim()
                    if(content.isNotEmpty()) {
                        val keys=item.optJSONArray("keys")?.let{a->(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}}.orEmpty()
                        val condition=if(item.optBoolean("constant")||keys.isEmpty())"常驻设定" else "仅在相关情境或关键词（${keys.joinToString("、")}）出现时参考"
                        lore+="$condition：${render(content,name,user)}"
                    }
                }
        }
        require(substance||opening.isNotBlank()||lore.isNotEmpty()){"角色卡没有人物设定、开场或世界书"}
        if(lore.isNotEmpty())sections+="世界书参考（静态文字适配，不运行脚本）：\n"+lore.joinToString("\n\n")
        val prompt=sections.joinToString("\n\n")
        require(prompt.length<=65536){"角色卡文字超过 64K，请精简设定或世界书后导入"}
        val dialogs=JSONArray()
        if(opening.isNotBlank()){dialogs.put("开始新的对话");dialogs.put(opening)}
        return JSONObject().put("persona_id",name).put("system_prompt",prompt).put("begin_dialogs",dialogs)
    }
}
