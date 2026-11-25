// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun StudioKnowledgeDocuments(vm: StudioModel, kb: JSONObject, dismiss: ()->Unit) {
    val id=kb.getString("kb_id")
    var page by remember(id){mutableIntStateOf(1)}
    var refresh by remember(id){mutableIntStateOf(0)}
    var rows by remember(id){mutableStateOf<List<JSONObject>>(emptyList())}
    var total by remember(id){mutableIntStateOf(0)}
    var failure by remember(id){mutableStateOf<String?>(null)}
    var loading by remember(id){mutableStateOf(false)}
    var detail by remember(id){mutableStateOf<List<JSONObject>?>(null)}
    var deletion by remember(id){mutableStateOf<JSONObject?>(null)}
    var importUrl by remember(id){mutableStateOf(false)}
    var url by remember(id){mutableStateOf("")}
    LaunchedEffect(id,page,refresh){
        loading=true;failure=null
        try{val data=vm.api.bot("/api/kb/document/list?kb_id=${urlValue(id)}&page=$page&page_size=20") as JSONObject;rows=jsonRows(data);total=data.optInt("total",rows.size)}
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message?:"文档列表读取失败"}
        finally{loading=false}
    }
    AlertDialog(onDismissRequest={if(!vm.busy)dismiss()},title={Text(kb.optString("kb_name","知识库文档"))},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("文档管理使用本机引擎；上传、网页导入与检索按已有知识库模型配置处理。",style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick={refresh++},enabled=!loading){Text("刷新")};TextButton(onClick={importUrl=true},enabled=!vm.busy){Text("导入网页")}}
            failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(loading)Text("正在读取…")
            if(!loading&&rows.isEmpty()&&failure==null)Text("还没有文档，可在知识库卡片选择导入文档。")
            rows.forEach { doc ->
                Text(doc.optString("doc_name","未命名文档"),style=MaterialTheme.typography.titleSmall)
                Text("${doc.optInt("chunk_count")} 个片段 · ${doc.optString("file_type")}",style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick={vm.run("查看知识片段"){
                        val data=vm.api.bot("/api/kb/chunk/list?kb_id=${urlValue(id)}&doc_id=${urlValue(doc.getString("doc_id"))}&page_size=20")
                        detail=jsonRows(data)
                    }},enabled=!vm.busy){Text("阅读片段")}
                    TextButton(onClick={deletion=doc},enabled=!vm.busy){Text("删除文档")}
                }
                HorizontalDivider()
            }
            Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()) {
                TextButton(onClick={page--},enabled=page>1&&!loading){Text("上一页")}
                Text("第 $page 页 · 共 $total 份",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={page++},enabled=page*20<total&&!loading){Text("下一页")}
            }
        }
    },confirmButton={TextButton(onClick=dismiss,enabled=!vm.busy){Text("完成")}})
    deletion?.let { doc -> AlertDialog(onDismissRequest={deletion=null},title={Text("删除文档？")},text={Text("这会删除 ${doc.optString("doc_name")} 及它的知识索引。")},confirmButton={TextButton(onClick={vm.run("删除知识文档"){
        vm.api.bot("/api/kb/document/delete","POST",JSONObject().put("kb_id",id).put("doc_id",doc.getString("doc_id")))
        deletion=null;refresh++
    }},enabled=!vm.busy){Text("删除")}},dismissButton={TextButton(onClick={deletion=null}){Text("取消")}}) }
    detail?.let { chunks -> AlertDialog(onDismissRequest={detail=null},title={Text("知识片段 · 前 20 条")},text={
        SelectionContainer { Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(chunks.isEmpty())Text("暂无片段，文档可能尚未完成索引。")
            chunks.forEachIndexed { index,chunk -> Text("${index+1}. "+chunk.optString("content").ifBlank{chunk.optString("text", "暂无可显示的文本")}) }
        } }
    },confirmButton={TextButton(onClick={detail=null}){Text("关闭")}}) }
    if(importUrl)AlertDialog(onDismissRequest={if(!vm.busy)importUrl=false},title={Text("导入网页内容")},text={StudioField(url,{url=it},"公开网页 HTTPS 地址",enabled=!vm.busy)},confirmButton={TextButton(onClick={vm.run("导入网页"){
        val parsed=java.net.URI(url.trim());require(parsed.scheme=="https"&&!parsed.host.isNullOrBlank()&&parsed.userInfo==null){"请填写没有账号密码的 HTTPS 网页地址"}
        vm.api.bot("/api/v1/knowledge-bases/${urlValue(id)}/documents/import-url","POST",JSONObject().put("url",url.trim()))
        importUrl=false;refresh++;vm.notice="网页导入已提交，可刷新查看处理结果"
    }},enabled=!vm.busy&&url.isNotBlank()){Text("导入")}},dismissButton={TextButton(onClick={importUrl=false},enabled=!vm.busy){Text("取消")}})
}
