package io.astrbot.android

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class TavernCharacterCardTest {
    private fun card(name:String="澜汐")=JSONObject().put("spec","chara_card_v3").put("data",JSONObject()
        .put("name",name).put("description","{{char}} 是守护灯塔的成年人。")
        .put("personality","轻快，坦率").put("scenario","海湾的雨夜")
        .put("first_mes","{{user}}，要一起看潮汐吗？").put("mes_example","{{user}}: 好呀\n{{char}}: 那就出发。"))
    private fun chunk(out:DataOutputStream,type:String,data:ByteArray) {
        val name=type.toByteArray();out.writeInt(data.size);out.write(name);out.write(data)
        out.writeInt(CRC32().apply{update(name);update(data)}.value.toInt())
    }
    private fun png(vararg metadata:Pair<String,ByteArray>):ByteArray {
        val b=ByteArrayOutputStream();DataOutputStream(b).use { out->
            out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            val h=ByteArrayOutputStream();DataOutputStream(h).use{it.writeInt(1);it.writeInt(1);it.write(byteArrayOf(8,6,0,0,0))};chunk(out,"IHDR",h.toByteArray())
            for((type,data) in metadata)chunk(out,type,data)
            val image=ByteArrayOutputStream();DeflaterOutputStream(image).use{it.write(ByteArray(5))};chunk(out,"IDAT",image.toByteArray());chunk(out,"IEND",ByteArray(0))
        };return b.toByteArray()
    }
    private fun encoded(c:JSONObject)=Base64.getEncoder().encode(c.toString().toByteArray())
    @Test fun convertsCompleteCardAndOpeningWithoutLosingFields() {
        val payload=TavernCharacterCard.convert(card(),"旅人")
        val text=payload.getString("system_prompt")
        for(word in listOf("澜汐","轻快，坦率","海湾的雨夜","那就出发","旅人"))assertTrue(word,text.contains(word))
        assertFalse(text.contains("{{char}}"));assertFalse(text.contains("{{user}}"))
        assertEquals(2,payload.getJSONArray("begin_dialogs").length())
        assertEquals("旅人，要一起看潮汐吗？",payload.getJSONArray("begin_dialogs").getString(1))
    }
    @Test fun retainsActiveLoreAndExcludesDisabledLore() {
        val c=card();c.getJSONObject("data").put("character_book",JSONObject().put("entries",JSONArray()
            .put(JSONObject().put("keys",JSONArray().put("灯塔")).put("content","灯塔信号是蓝色").put("enabled",true))
            .put(JSONObject().put("content","不该出现的设定").put("enabled",false))))
        val text=TavernCharacterCard.convert(c).getString("system_prompt")
        assertTrue(text.contains("灯塔信号是蓝色"));assertFalse(text.contains("不该出现的设定"))
    }
    @Test fun pngCharaAndCcv3RoundTripAndV3TakesPriority() {
        val value=png("tEXt" to ("chara\u0000".toByteArray()+encoded(card("旧角色"))),"tEXt" to ("ccv3\u0000".toByteArray()+encoded(card("新角色"))))
        assertEquals("新角色",TavernCharacterCard.data(TavernCharacterCard.decode(value)).getString("name"))
    }
    @Test fun compressedAndInternationalPngMetadataWork() {
        val zipped=ByteArrayOutputStream();DeflaterOutputStream(zipped).use{it.write(encoded(card()))}
        val z=png("zTXt" to ("chara\u0000\u0000".toByteArray()+zipped.toByteArray()))
        assertEquals("澜汐",TavernCharacterCard.data(TavernCharacterCard.decode(z)).getString("name"))
        val i=png("iTXt" to ("ccv3\u0000\u0000\u0000\u0000\u0000".toByteArray()+encoded(card())))
        assertEquals("澜汐",TavernCharacterCard.data(TavernCharacterCard.decode(i)).getString("name"))
    }
    @Test fun rejectsDamagedTruncatedAndNonCardPng() {
        val good=png("tEXt" to ("chara\u0000".toByteArray()+encoded(card())))
        val bad=good.copyOf();bad[45]=(bad[45].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){TavernCharacterCard.decode(bad)}
        assertThrows(IllegalArgumentException::class.java){TavernCharacterCard.decode(good.copyOf(good.size-5))}
        assertThrows(IllegalStateException::class.java){TavernCharacterCard.decode(png())}
    }
    @Test fun jsonUtf8AndLegacyV1WorkAndNameIsLiteral() {
        val c=card("鲸鱼$1");val plain=TavernCharacterCard.data(c)
        val raw=("\uFEFF"+plain.toString()).toByteArray()
        val decoded=TavernCharacterCard.decode(raw)
        assertEquals("鲸鱼$1",TavernCharacterCard.convert(decoded).getString("persona_id"))
        assertTrue(TavernCharacterCard.convert(decoded).getString("system_prompt").contains("鲸鱼$1 是守护灯塔"))
    }
    @Test fun neverRunsCardScriptsAndRejectsOversizedSetting() {
        val c=card();c.getJSONObject("data").put("extensions",JSONObject().put("script","throw new Error('must not run')"))
        assertFalse(TavernCharacterCard.convert(c).getString("system_prompt").contains("must not run"))
        c.getJSONObject("data").put("description","a".repeat(66000))
        assertThrows(IllegalArgumentException::class.java){TavernCharacterCard.convert(c)}
    }
    @Test fun bundledTavernCardsHaveCompleteAndConvertibleProfiles() {
        val c=JSONObject(java.io.File("src/main/assets/bigfish/tavern-library.json").readText())
        val parsed=PersonaTemplates.parse(c)
        assertEquals(11,parsed.size)
        assertTrue(parsed.all{it.card!=null&&it.prompt.length>500})
        assertTrue(parsed.any{it.name.contains("深海鲸鱼娘")})
    }
    @Test fun openingOnlyAndWorldbookOnlyCardsRemainImportable() {
        val opening=JSONObject().put("name","迎客者").put("first_mes","欢迎来到潮汐镇。")
        assertTrue(TavernCharacterCard.convert(opening).getString("system_prompt").contains("欢迎来到潮汐镇"))
        val book=JSONObject().put("name","潮汐镇").put("character_book",JSONObject().put("entries",JSONArray().put(JSONObject().put("content","镇上的路灯是蓝色的。"))))
        assertTrue(TavernCharacterCard.convert(book).getString("system_prompt").contains("镇上的路灯是蓝色"))
    }
    @Test fun invalidJsonHasUsefulErrorWithoutEchoingRawData() {
        try { TavernCharacterCard.decode("not-json-private-fixture".toByteArray());fail("Invalid JSON accepted") }
        catch(e:IllegalStateException){assertTrue(e.message!!.contains("有效角色卡"));assertFalse(e.message!!.contains("private-fixture"))}
    }

}
