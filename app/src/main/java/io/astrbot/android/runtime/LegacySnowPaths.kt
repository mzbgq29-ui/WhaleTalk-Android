package io.astrbot.android.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Data-only path rebasing for old SnowLuma configuration, never conversation identifiers. */
internal object LegacySnowPaths {
    private val prefixes=listOf("/persistent/napcat/config/snowluma", "/persistent/napcat/snowluma")
    fun translate(text: String): String {
        val prefix=prefixes.firstOrNull { text==it || text.startsWith("$it/") } ?: return text
        return "/persistent/snowluma"+text.removePrefix(prefix)
    }
    fun rebase(value: Any): Boolean {
        var changed=false
        fun entry(original: Any, put: (Any)->Unit) {
            if(original is String){val next=translate(original);if(next!=original){put(next);changed=true}}
            else if(original is JSONObject||original is JSONArray)changed=rebase(original)||changed
        }
        when(value){
            is JSONObject->value.keys().asSequence().toList().forEach { key->entry(value.get(key)){value.put(key,it)} }
            is JSONArray->(0 until value.length()).forEach { index->entry(value.get(index)){value.put(index,it)} }
        }
        return changed
    }
    fun migrate(persistent: File) {
        val root=File(persistent,"snowluma")
        if(!root.isDirectory||Files.isSymbolicLink(root.toPath()))return
        Files.walkFileTree(root.toPath(),object:SimpleFileVisitor<Path>(){
            override fun visitFile(path:Path,attrs:BasicFileAttributes):FileVisitResult {
                if(attrs.isRegularFile&&path.fileName.toString().endsWith(".json")&&attrs.size()<=8L*1024*1024){
                    val value=runCatching { JSONObject(path.toFile().readText()) }.getOrNull()
                    if(value!=null&&rebase(value))DurableFiles.write(path.toFile(),value)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
