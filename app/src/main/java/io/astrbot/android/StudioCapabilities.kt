// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun StudioCapabilities(vm: StudioModel) {
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val install=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.run("导入技能"){vm.api.uploadSkill(uri);refresh++;vm.notice="技能包已交给本机引擎安装"}}
    LaunchedEffect(tab,refresh){
        loading=true;failure=null;rows=emptyList()
        try { rows=jsonRows(vm.api.bot(when(tab){0->"/api/v1/tools";1->"/api/v1/skills";else->"/api/v1/mcp/servers"}),"tools","skills","servers") }
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message?:"读取失败"}
        finally{loading=false}
    }
    StudioCard("工具与技能","按需管理本机引擎的能力；不会自动开启工具或安装外部服务。") {
        Row { listOf("工具","技能","MCP").forEachIndexed { i,label->FilterChip(tab==i,{tab=i},{Text(label)}) } }
        TextButton(onClick={refresh++},enabled=!loading){Text("刷新")}
        if(tab==1)TextButton(onClick={install.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!vm.busy){Text("从手机 ZIP 导入技能")}
        if(tab==2)TextButton(onClick={editingNew=true;editing=JSONObject().put("name","").put("url","").put("active",false)},enabled=!vm.busy){Text("添加 MCP 服务")}
        failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(loading)Text("正在读取…")
        if(!loading&&rows.isEmpty()&&failure==null)Text("当前没有可显示的项目。")
    }
    rows.forEach { item ->
        val name=item.optString("name").ifBlank { item.optString("skill_name",item.optString("id")) }
        StudioCard(name,item.optString("description").ifBlank{null}) {
            val active=item.optBoolean("active",item.optBoolean("enabled",true))
            StudioSwitch("启用",if(tab==0)item.optString("origin_display_name") else "修改将立即交给本机引擎应用",active,!vm.busy&&!item.optBoolean("readonly")){enabled->
                vm.run("调整能力"){
                    when(tab){
                        0->vm.api.bot("/api/v1/tools/${urlValue(name)}/enabled","PATCH",JSONObject().put("enabled",enabled))
                        1->vm.api.bot("/api/v1/skills/by-name","PATCH",JSONObject().put("skill_name",name).put("enabled",enabled))
                        else->vm.api.bot("/api/v1/mcp/servers/enabled","PATCH",JSONObject().put("server_name",name).put("enabled",enabled))
                    };refresh++
                }
            }
            if(tab==2){
                StudioStatus("连接",if(item.optBoolean("connected"))"已连接" else "未连接",item.optBoolean("connected"))
                Row {
                    TextButton(onClick={editingNew=false;editing=JSONObject(item.toString())},enabled=!vm.busy){Text("编辑")}
                    TextButton(onClick={vm.run("测试 MCP 连接"){vm.api.bot("/api/v1/mcp/servers/test","POST",JSONObject().put("server_name",name));vm.notice="MCP 连接测试成功";refresh++}},enabled=!vm.busy){Text("连接测试")}
                }
            }
            if(tab>0)TextButton(onClick={deleting=item},enabled=!vm.busy){Text("删除")}
        }
    }
    deleting?.let{item->val name=item.optString("name").ifBlank{item.optString("skill_name")};AlertDialog(onDismissRequest={deleting=null},title={Text("删除 $name？")},text={Text("只删除这个技能或 MCP 服务，其余项目保持原设置。")},confirmButton={TextButton(onClick={vm.run("删除能力"){
        vm.api.bot(if(tab==1)"/api/v1/skills/by-name?skill_name=${urlValue(name)}" else "/api/v1/mcp/servers/by-name?server_name=${urlValue(name)}","DELETE");deleting=null;refresh++
    }},enabled=!vm.busy){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
    editing?.let{value->
        val errors=remember(value){mutableStateMapOf<String,String>()}
        val oldName=remember(value){value.optString("name")}
        val config=remember(value){JSONObject(value.toString()).also { it.remove("name");it.remove("connected");it.remove("tools");it.remove("errlogs") }}
        var name by remember(value){mutableStateOf(oldName)}
        var advanced by remember(value){mutableStateOf(false)}
        var raw by remember(value){mutableStateOf(config.toString(2))}
        var rawError by remember(value){mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!vm.busy)editing=null},title={Text("MCP 服务")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            StudioField(name,{name=it},"名称",enabled=editingNew&&!vm.busy)
            Text("填入服务提供的配置。新建默认关闭，可保存后再手动开启。",style=MaterialTheme.typography.bodySmall)
            StudioSwitch("完整 JSON 配置","填写 command、args、headers 等字段",advanced,!vm.busy){next->
                if(next){raw=config.toString(2);errors.clear()}
                if(!next)runCatching { val parsed=JSONObject(raw);config.keys().asSequence().toList().forEach { config.remove(it) };parsed.keys().forEach { config.put(it,parsed.get(it)) } }.onFailure { rawError="JSON 格式有误" }
                if(next||rawError==null)advanced=next
            }
            if(advanced)StudioField(raw,{raw=it;rawError=runCatching { JSONObject(it) }.exceptionOrNull()?.let { "JSON 格式有误" }},"服务配置 JSON",secret=true,multiline=true,enabled=!vm.busy)
            else StudioConfigFields(config,errors=errors,enabled=!vm.busy)
            rawError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},confirmButton={TextButton(onClick={vm.run("保存 MCP"){
            require(name.isNotBlank()){ "请填写名称" }
            vm.api.bot(if(editingNew)"/api/v1/mcp/servers" else "/api/v1/mcp/servers/by-name",if(editingNew)"POST" else "PUT",
                JSONObject().put(if(editingNew)"name" else "server_name",name).put("config",if(advanced)JSONObject(raw) else config))
            editing=null;refresh++;vm.notice="MCP 配置已保存"
        }},enabled=!vm.busy&&errors.isEmpty()&&rawError==null){Text("保存")}},dismissButton={TextButton(onClick={editing=null},enabled=!vm.busy){Text("取消")}})
    }
}
