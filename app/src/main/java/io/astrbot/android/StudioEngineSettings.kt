// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private val sensitiveConfigKey=Regex("(?i)(password|secret|token|authorization|api[_-]?key|private[_-]?key|^key$)")
private fun containsConfigSecret(value: Any?): Boolean = when(value) {
    is JSONObject -> value.keys().asSequence().any { sensitiveConfigKey.containsMatchIn(it)||containsConfigSecret(value.opt(it)) }
    is JSONArray -> (0 until value.length()).any { containsConfigSecret(value.opt(it)) }
    else -> false
}

/** Native typed editor for SDK and plugin configuration. No web UI is embedded. */
@Composable internal fun StudioConfigFields(value: JSONObject, metadata: JSONObject = JSONObject(), labels: JSONObject = JSONObject(),
    errors: MutableMap<String,String>, path: String = "", enabled: Boolean = true, depth: Int = 0) {
    fun label(key: String, spec: JSONObject): String {
        val raw=spec.optString("description").ifBlank { spec.optString("name",key) }
        if(!raw.contains('.'))return raw
        var current: Any?=labels
        val parts=raw.substringAfter("config-metadata.",raw).split('.')
        for(part in parts) current=(current as? JSONObject)?.opt(part)
        return (current as? String)?.takeIf { it.isNotBlank() } ?: key
    }
    fun spec(key: String): JSONObject {
        metadata.optJSONObject(key)?.let { return it }
        metadata.optJSONObject("items")?.optJSONObject(key)?.let { return it }
        metadata.optJSONObject("metadata")?.optJSONObject(key)?.let { return it }
        for(group in metadata.keys()) {
            val parent=metadata.optJSONObject(group) ?: continue
            parent.optJSONObject("metadata")?.optJSONObject(key)?.let { return it }
        }
        return JSONObject()
    }
    value.keys().asSequence().toList().filterNot { it.contains("tts",true)||it.contains("stt",true) }.forEach { key ->
        val original=value.opt(key);val definition=spec(key);val field=if(path.isEmpty())key else "$path.$key"
        val title=label(key,definition)
        when(original) {
            is JSONObject -> {
                var expanded by remember(value,key){mutableStateOf(false)}
                TextButton(onClick={expanded=!expanded}){Text((if(expanded)"收起 " else "展开 ")+title)}
                if(expanded && depth<12) Column(Modifier.padding(start=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    StudioConfigFields(original,definition,labels,errors,field,enabled,depth+1)
                }
            }
            is Boolean -> {
                var checked by remember(value,key){mutableStateOf(original)}
                StudioSwitch(title,definition.optString("hint").takeUnless { it.startsWith("config-") }.orEmpty(),checked,enabled) {
                    checked=it;value.put(key,it)
                }
            }
            else -> {
                var text by remember(value,key){mutableStateOf(when(original){is JSONArray->original.toString(2);null,JSONObject.NULL->"";else->original.toString()})}
                val secret=sensitiveConfigKey.containsMatchIn(key)||(original is JSONArray&&containsConfigSecret(original))
                StudioField(text,{next ->
                    text=next
                    try {
                        val parsed: Any=when(original) {
                            is JSONArray -> JSONArray(next)
                            is Int,is Long -> next.toLongOrNull() ?: error("请输入整数")
                            is Number -> next.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("请输入有效数字")
                            null,JSONObject.NULL -> if(next.isEmpty())JSONObject.NULL else next
                            else -> next
                        }
                        value.put(key,parsed);errors.remove(field)
                    } catch(_:Exception){errors[field]=if(original is JSONArray)"请保留完整的列表格式，字符串需使用双引号" else "数值格式不正确"}
                },title,secret=secret,multiline=original is JSONArray||text.length>100,enabled=enabled)
                errors[field]?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable internal fun StudioEngineSettings(vm: StudioModel) {
    val context=LocalContext.current
    val labels=remember(context){runCatching{context.assets.open("bigfish/config-labels.json").bufferedReader().use{JSONObject(it.readText())}}.getOrDefault(JSONObject())}
    var profile by remember{mutableStateOf("default")}
    var profiles by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var document by remember{mutableStateOf<JSONObject?>(null)}
    var metadata by remember{mutableStateOf(JSONObject())}
    var error by remember{mutableStateOf<String?>(null)}
    var reload by remember{mutableIntStateOf(0)}
    var naming by remember{mutableStateOf<String?>(null)}
    var newName by remember{mutableStateOf("")}
    var deleting by remember{mutableStateOf(false)}
    val errors=remember(profile,reload){mutableStateMapOf<String,String>()}
    LaunchedEffect(profile,reload){
        document=null;error=null
        try {
            profiles=jsonRows(vm.api.bot("/api/v1/config-profiles"),"info_list")
            val result=vm.api.bot("/api/v1/config-profiles/${urlValue(profile)}") as JSONObject
            document=JSONObject(result.getJSONObject("config").toString());metadata=result.optJSONObject("metadata")?:JSONObject()
        } catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message?:"引擎设置暂时无法读取"}
    }
    StudioCard("AstrBot 引擎设置","在本软件内修改本机引擎配置，保存后由引擎应用。语音功能保持关闭。") {
        profiles.forEach { item ->
            val id=item.optString("id").ifBlank{item.optString("conf_id")}.ifBlank{"default"}
            LiquidChoice(profile==id,{profile=id},{Text(item.optString("name",id))},enabled=!vm.busy)
        }
        Row {
            TextButton(onClick={reload++},enabled=!vm.busy){Text("重新读取")}
            TextButton(onClick={naming="create";newName=""},enabled=!vm.busy&&document!=null&&errors.isEmpty()){Text("新增配置")}
        }
        if(profile!="default") Row {
            TextButton(onClick={naming="rename";newName=""},enabled=!vm.busy){Text("重命名")}
            TextButton(onClick={deleting=true},enabled=!vm.busy){Text("删除此配置")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    document?.let { current ->
        key(current) { StudioCard("配置项目") { StudioConfigFields(current,metadata,labels,errors,enabled=!vm.busy) } }
        LiquidButton(onClick={vm.run("保存引擎设置"){
            val next=JSONObject(current.toString())
            listOf("provider_stt_settings","provider_tts_settings").forEach { next.optJSONObject(it)?.put("enable",false) }
            vm.api.bot("/api/v1/config-profiles/${urlValue(profile)}","PUT",next)
            vm.notice="引擎设置已保存";reload++
        }},Modifier.fillMaxWidth(),enabled=!vm.busy&&errors.isEmpty()){Text("保存并应用")}
    }
    naming?.let { action -> AlertDialog(onDismissRequest={if(!vm.busy)naming=null},title={Text(if(action=="create")"新增聊天配置" else "重命名配置")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        StudioField(newName,{newName=it.take(100)},"名称",enabled=!vm.busy)
        if(action=="create")Text("复制当前配置；保存后可在会话页指定给某个好友或群。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={vm.run("保存配置名称"){
        require(newName.isNotBlank()){ "请填写名称" }
        if(action=="create") {
            val copy=JSONObject(requireNotNull(document).toString())
            listOf("provider_stt_settings","provider_tts_settings").forEach { copy.optJSONObject(it)?.put("enable",false) }
            val data=vm.api.bot("/api/v1/config-profiles","POST",JSONObject().put("name",newName).put("config",copy)) as JSONObject
            profile=data.getString("conf_id")
        } else vm.api.bot("/api/v1/config-profiles/${urlValue(profile)}","PATCH",JSONObject().put("name",newName))
        naming=null;reload++;vm.notice="配置已保存"
    }},enabled=!vm.busy&&newName.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={naming=null},enabled=!vm.busy){Text("取消")}}) }
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除此聊天配置？")},text={Text("聊天记录不会被删除。仍在使用的配置可能需要先解除会话绑定。")},confirmButton={TextButton(onClick={vm.run("删除聊天配置"){
        vm.api.bot("/api/v1/config-profiles/${urlValue(profile)}","DELETE");profile="default";deleting=false;reload++
    }},enabled=!vm.busy){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})

}

@Composable internal fun StudioPluginConfiguration(vm: StudioModel, plugin: String, dismiss: ()->Unit) {
    var data by remember(plugin){mutableStateOf<JSONObject?>(null)}
    var failure by remember(plugin){mutableStateOf<String?>(null)}
    val errors=remember(plugin){mutableStateMapOf<String,String>()}
    LaunchedEffect(plugin){
        try { data=vm.api.bot("/api/v1/plugins/config?plugin_id=${urlValue(plugin)}") as JSONObject }
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message?:"无法读取插件配置"}
    }
    AlertDialog(onDismissRequest={if(!vm.busy)dismiss()},title={Text("$plugin 配置")},text={
        Column(Modifier.heightIn(max=490.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(data==null&&failure==null)Text("正在读取配置…")
            data?.let { result ->
                val config=result.optJSONObject("config")
                if(config==null)Text("这个插件没有可编辑的配置。")
                else StudioConfigFields(config,result.optJSONObject("metadata")?:JSONObject(),errors=errors,enabled=!vm.busy)
            }
        }
    },confirmButton={TextButton(onClick={val config=data?.optJSONObject("config")?:return@TextButton;vm.run("保存插件配置"){
        vm.api.bot("/api/v1/plugins/config","PUT",JSONObject().put("plugin_id",plugin).put("config",config))
        vm.notice="插件配置已保存并交给引擎重载";dismiss()
    }},enabled=!vm.busy&&errors.isEmpty()&&data?.optJSONObject("config")!=null){Text("保存配置")}},dismissButton={TextButton(onClick=dismiss,enabled=!vm.busy){Text("关闭")}})
}

@Composable internal fun StudioSessionConfiguration(vm: StudioModel, origin: String, dismiss: ()->Unit) {
    var profiles by remember(origin){mutableStateOf<List<JSONObject>>(emptyList())}
    var failure by remember(origin){mutableStateOf<String?>(null)}
    LaunchedEffect(origin){try{profiles=jsonRows(vm.api.bot("/api/v1/config-profiles"),"info_list")}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message?:"读取配置失败"}}
    AlertDialog(onDismissRequest={if(!vm.busy)dismiss()},title={Text("专属聊天配置")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("为这位好友或这个群选择完整聊天配置。可在「引擎设置」中新建配置，单独设置人设、模型、上下文和知识库。",style=MaterialTheme.typography.bodySmall)
        failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        profiles.forEach { item -> val id=item.optString("id").ifBlank{item.optString("conf_id")}.ifBlank{"default"}
            LiquidOutlinedButton(onClick={vm.run("应用专属配置"){
                vm.api.bot("/api/v1/config-routes/${urlValue(origin)}","PUT",JSONObject().put("config_id",id));vm.notice="专属配置已应用";dismiss()
            }},Modifier.fillMaxWidth(),enabled=!vm.busy){Text(item.optString("name",id))}
        }
        TextButton(onClick={vm.run("恢复默认配置"){
            vm.api.bot("/api/v1/config-routes/${urlValue(origin)}","DELETE");vm.notice="此会话已恢复默认配置";dismiss()
        }},enabled=!vm.busy){Text("解除专属绑定，使用默认配置")}
    }},confirmButton={TextButton(onClick=dismiss,enabled=!vm.busy){Text("关闭")}})
}
