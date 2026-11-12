// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun StudioPersonaLibrary(vm: StudioModel) {
    val context=LocalContext.current;val links=LocalUriHandler.current
    val repository=remember(context){PersonaWarehouseRepository(context)}
    var rows by remember { mutableStateOf<List<PersonaTemplate>>(emptyList()) }
    var favorites by remember { mutableStateOf(repository.favorites()) }
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("酒馆角色") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var selected by remember { mutableStateOf<PersonaTemplate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tagMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<JSONObject?>(null) }
    val tags=remember { mapOf("companion" to "陪伴", "fantasy" to "幻想", "adventure" to "冒险", "modern" to "现代", "original" to "原创", "local" to "我的卡片", "life" to "生活", "academic" to "学术", "ai" to "AI", "code" to "编程", "company" to "职场", "games" to "游戏", "interesting" to "趣味", "language" to "语言", "mind" to "思维", "music" to "音乐", "pedagogy" to "教学", "professional" to "专业", "social" to "社交", "text" to "文字", "tool" to "工具", "write" to "写作") }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.run("导入酒馆角色卡"){
        val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{TavernCharacterCard.readLimited(it)}}
        val item=repository.importLocal(bytes);rows=repository.load();mode="酒馆角色";category="全部";query="";selected=item
        vm.notice="角色卡已保存在手机，可预览后导入机器人"
    }}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val data=exporting;exporting=null
        if(uri!=null&&data!=null)vm.run("导出酒馆角色卡"){withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(data.toString(2).toByteArray())}};vm.notice="酒馆角色卡已导出"}
    }
    LaunchedEffect(Unit){try{rows=repository.load()}catch(_:Exception){error="本地人设库读取失败"}}
    val base=rows.filter{if(mode=="酒馆角色")it.card!=null else it.card==null}
    val displayed=base.filter { item ->
        (category=="全部" || category=="收藏"&&item.id in favorites || category in item.tags) &&
            (query.isBlank() || (item.name+item.description+item.tags.joinToString{tags[it]?:it}).contains(query,true))
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("酒馆角色","提示词模板").forEach{name->FilterChip(mode==name,{mode=name;category="全部"},{Text(name)})}
        }
        StudioField(query,{query=it.take(100)},if(mode=="酒馆角色")"搜索角色、世界或故事" else "搜索旧模板")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            listOf("全部","收藏").forEach{name->FilterChip(category==name,{category=name},{Text(name)})}
            Box { TextButton(onClick={tagMenu=true}){Text(tags[category]?:"分类")}
                DropdownMenu(tagMenu,{tagMenu=false},Modifier.heightIn(max=360.dp)) {
                    base.flatMap{it.tags}.distinct().filter{it!="tavern"}.forEach{tag->DropdownMenuItem(text={Text(tags[tag]?:tag)},onClick={category=tag;tagMenu=false})}
                }
            }
        }
        if(mode=="酒馆角色") {
            Text("${base.size} 张角色卡 · 支持酒馆 V1 / V2 / V3",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={import.launch(arrayOf("image/png","application/json","text/plain","application/octet-stream"))},enabled=!vm.busy){Text("导入 PNG / JSON")}
                TextButton(onClick={runCatching{links.openUri("https://chub.ai/")}.onFailure{vm.error="浏览器暂时无法打开，可继续使用离线角色卡"}}){Text("角色卡站点 ↗")}
            }
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${base.size} 份旧模板",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={vm.run("同步提示词模板"){rows=repository.synchronize();vm.notice="模板已更新，酒馆角色卡和收藏保留"}},enabled=!vm.busy){Text("同步 AI Short")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            items(displayed,key={it.id}){item->StudioCard(item.name,item.description.ifBlank{item.source}){
                Text(item.tags.filter{it!="tavern"}.joinToString(" · "){tags[it]?:it},style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick={selected=item}){Text("预览与导入")}
                    TextButton(onClick={runCatching{repository.favorite(item.id,item.id !in favorites);favorites=repository.favorites()}.onFailure{vm.error="收藏未能保存"}}){Text(if(item.id in favorites)"已收藏" else "收藏")}
                }
            }}
            if(displayed.isEmpty())item{StudioEmpty("没有匹配的角色","换个关键词，或导入手机上的酒馆角色卡。")}
        }
    }
    selected?.let{item->
        var name by remember(item.id){mutableStateOf(item.name.take(100))}
        var activeCard by remember(item.id){mutableStateOf(item.card?.let{JSONObject(it.toString())})}
        var prompt by remember(item.id){mutableStateOf(item.prompt)}
        val originalData=item.card?.let{TavernCharacterCard.data(it)}
        val greetings=remember(item.id){originalData?.let{d->listOf(d.optString("first_mes"))+d.optJSONArray("alternate_greetings").let{a->if(a==null)emptyList()else(0 until a.length()).map{a.optString(it)}}}.orEmpty().filter{it.isNotBlank()}.distinct()}
        var greetingIndex by remember(item.id){mutableIntStateOf(0)}
        fun import(apply:Boolean){vm.run(if(apply)"导入并应用角色" else "导入角色"){
            require(name.isNotBlank()&&prompt.isNotBlank()){"请填写名称和设定"}
            val existing=jsonRows(vm.api.bot("/api/persona/list")).firstOrNull{it.optString("persona_id")==name}
            require(existing==null||existing.optString("system_prompt")==prompt){"同名角色已有不同设定，请换一个名称，以免覆盖原有编辑"}
            val payload=activeCard?.let{TavernCharacterCard.convert(it)}?:JSONObject()
            payload.put("persona_id",name).put("system_prompt",prompt)
            if(existing==null)vm.api.bot("/api/persona/create","POST",payload)
            if(apply)vm.api.applyPersona(name)
            vm.notice=if(apply)"角色已应用到默认聊天；新会话使用新设定" else "角色已导入，原有默认人设保留"
            selected=null
        }}
        AlertDialog(onDismissRequest={if(!vm.busy)selected=null},title={Text(item.name)},text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("来源：${item.source}"+(if(item.license.isNotBlank())" · ${item.license}" else ""),style=MaterialTheme.typography.bodySmall)
                if(activeCard!=null) {
                    Text("导入人物设定、性格、场景、开场和对话示例；世界书转为静态参考。酒馆脚本和变量面板不会运行。",style=MaterialTheme.typography.bodySmall)
                    if(greetings.size>1) {
                        Text("选择开场（切换会重新生成设定）",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) { greetings.forEachIndexed{i,text->FilterChip(greetingIndex==i,{
                            greetingIndex=i;activeCard=JSONObject(item.card!!.toString()).also{TavernCharacterCard.data(it).put("first_mes",text)}
                            prompt=TavernCharacterCard.convert(activeCard!!).getString("system_prompt")
                        },{Text("开场 ${i+1}")})} }
                    }
                    activeCard?.let{card->TavernCharacterCard.greeting(card).takeIf{it.isNotBlank()}?.let{opening->Text("开场参考",style=MaterialTheme.typography.titleSmall);SelectionContainer{Text(opening)}}}
                    TextButton(onClick={exporting=activeCard;export.launch("${name.take(60)}.json")},enabled=!vm.busy){Text("导出酒馆 JSON")}
                }
                StudioField(name,{name=it.take(120)},"导入后名称",enabled=!vm.busy)
                StudioField(prompt,{prompt=it.take(65536)},"完整角色设定",multiline=true,enabled=!vm.busy)
                vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                Text("不会自动向 QQ 发送开场白；已有会话的独立人设保持原设置。",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={import(true)},enabled=!vm.busy){Text("导入并应用")}},dismissButton={Row{
            TextButton(onClick={import(false)},enabled=!vm.busy){Text("只导入")};TextButton(onClick={selected=null},enabled=!vm.busy){Text("取消")}
        }})
    }
}
