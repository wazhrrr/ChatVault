package com.r.chatvault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * 导入器：支持「选择文件夹（SAF 目录）」和「选择 ZIP」两种入口。
 * 增量策略：以 conversation.update_time 为版本号，相等则跳过，否则整条会话替换。
 */
class Importer(private val ctx: Context, private val store: Store) {

    data class Progress(val phase: String, val done: Int, val total: Int)

    private class Source(val name: String, val size: Long, val open: () -> InputStream)

    private class PendingAttachment(
        val id: String,
        val convId: String,
        val msgId: String,
        val name: String,
        val mime: String,
        val size: Long
    )

    suspend fun importFrom(uri: Uri, force: Boolean = false, onProgress: (Progress) -> Unit): ImportSummary =
        withContext(Dispatchers.IO) {
            val label = displayName(uri) ?: ""
            val isZip = label.endsWith(".zip", true) || label.contains("zip", true) && !label.endsWith(".json", true)

            var tempDir: File? = null
            val sources: List<Source> = if (isZip) {
                val dir = File(ctx.cacheDir, "inbox_${System.currentTimeMillis()}")
                dir.mkdirs()
                tempDir = dir
                onProgress(Progress("解压压缩包", 0, 0))
                unzipTo(uri, dir)
                dir.listFiles()?.filter { it.isFile }?.map { f ->
                    Source(f.name, f.length()) { f.inputStream() }
                } ?: emptyList()
            } else {
                collectFromTree(uri)
            }

            try {
                doImport(sources, force, onProgress)
            } finally {
                tempDir?.deleteRecursively()
            }
        }

    // ---------- 数据源 ----------

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun collectFromTree(uri: Uri): List<Source> {
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: return emptyList()
        val out = ArrayList<Source>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 6) return
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (isInteresting(f.name ?: "")) {
                    out.add(Source(f.name ?: "unknown", f.length()) { ctx.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("无法读取 ${f.name}") })
                }
            }
        }
        walk(root, 0)
        return out
    }

    private fun isInteresting(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".dat") ||
            n == "conversation_asset_file_names.json" ||
            (n.startsWith("conversations-") && n.endsWith(".json"))
    }

    private fun unzipTo(uri: Uri, dir: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法打开压缩包")
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            val buffer = ByteArray(64 * 1024)
            while (entry != null && count < 2000) {
                val raw = entry.name.replace('\\', '/')
                val base = raw.substringAfterLast('/')
                if (!entry.isDirectory && isInteresting(base)) {
                    val target = File(dir, base)
                    if (!target.exists()) {
                        target.outputStream().use { os ->
                            while (true) {
                                val n = zis.read(buffer)
                                if (n <= 0) break
                                os.write(buffer, 0, n)
                            }
                        }
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // ---------- 主流程 ----------

    private suspend fun doImport(
        sources: List<Source>,
        force: Boolean,
        onProgress: (Progress) -> Unit
    ): ImportSummary {
        val jsonFiles = sources.filter { it.name.startsWith("conversations-") && it.name.endsWith(".json") }
            .sortedBy { it.name }
        val datFiles = sources.filter { it.name.endsWith(".dat") }.associateBy { it.name.removeSuffix(".dat") }
        val nameMap = sources.firstOrNull { it.name == "conversation_asset_file_names.json" }
            ?.let { parseNameMap(it) } ?: emptyMap()

        if (jsonFiles.isEmpty()) {
            throw IllegalStateException("没有找到 conversations-*.json，请确认选择的是 ChatGPT 导出包的根目录")
        }

        var added = 0
        var updated = 0
        var skipped = 0
        var msgTotal = 0
        val pending = ArrayList<PendingAttachment>()
        val db = store.writableDatabase

        jsonFiles.forEachIndexed { index, src ->
            coroutineContext.ensureActive()
            onProgress(Progress("解析会话", index, jsonFiles.size))
            val arr = try {
                JSONArray(src.open().bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (t: Throwable) {
                throw IllegalStateException("解析 ${src.name} 失败：${t.message}")
            }
            for (i in 0 until arr.length()) {
                coroutineContext.ensureActive()
                val o = arr.optJSONObject(i) ?: continue
                val convId = o.optString("id").ifBlank { o.optString("conversation_id") }
                if (convId.isBlank()) continue
                val title = o.optString("title").ifBlank { "未命名会话" }
                val createSec = o.optDouble("create_time", 0.0).toLong()
                val updateSec = o.optDouble("update_time", createSec.toDouble()).toLong()
                val existing = store.existingUpdateTs(convId)

                if (!force && existing != null && existing >= updateSec && updateSec > 0) {
                    skipped++
                    continue
                }

                val mapping = o.optJSONObject("mapping") ?: continue
                val path = buildPath(mapping, o.optString("current_node"))
                if (path.isEmpty()) {
                    skipped++
                    continue
                }

                val defaultModel = o.optString("default_model_slug").ifBlank { null }
                val messages = ArrayList<Message>()
                var chars = 0
                var attachmentsInConv = 0

                for ((ord, nodeId) in path.withIndex()) {
                    val node = mapping.optJSONObject(nodeId) ?: continue
                    val msg = node.optJSONObject("message") ?: continue
                    val role = msg.optJSONObject("author")?.optString("role") ?: "assistant"
                    if (role != "user" && role != "assistant") continue

                    val content = msg.optJSONObject("content")
                    val ctype = content?.optString("content_type") ?: "text"
                    val sb = StringBuilder()
                    val pointerIds = LinkedHashSet<String>()
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        for (p in 0 until parts.length()) {
                            when (val part = parts.opt(p)) {
                                is String -> sb.append(part)
                                is JSONObject -> {
                                    val t = part.optString("text", "")
                                    if (t.isNotEmpty()) sb.append(t)
                                    val ptr = part.optString("asset_pointer", "")
                                    if (ptr.startsWith("file-service://")) {
                                        pointerIds.add(ptr.removePrefix("file-service://"))
                                    }
                                }
                            }
                        }
                    }

                    val md = msg.optJSONObject("metadata")
                    val atts = md?.optJSONArray("attachments")
                    val metaIds = LinkedHashSet<String>()
                    if (atts != null) {
                        for (a in 0 until atts.length()) {
                            val ao = atts.optJSONObject(a) ?: continue
                            val aid = ao.optString("id")
                            if (aid.isBlank()) continue
                            metaIds.add(aid)
                            val aname = ao.optString("name").ifBlank { nameMap["$aid.dat"] ?: aid }
                            pending.add(
                                PendingAttachment(
                                    id = aid,
                                    convId = convId,
                                    msgId = nodeId,
                                    name = aname,
                                    mime = ao.optString("mime_type"),
                                    size = ao.optLong("size", 0L)
                                )
                            )
                        }
                    }
                    for (pid in pointerIds) {
                        if (pid in metaIds) continue
                        pending.add(
                            PendingAttachment(
                                id = pid,
                                convId = convId,
                                msgId = nodeId,
                                name = nameMap["$pid.dat"] ?: pid,
                                mime = "",
                                size = 0L
                            )
                        )
                    }
                    attachmentsInConv += (metaIds + pointerIds).size

                    var text = sb.toString()
                    var reasoning: String? = null
                    if (ctype == "thoughts" || ctype == "reasoning_recap") {
                        reasoning = text
                        text = ""
                    }

                    val ts = msg.optDouble("create_time", 0.0).toLong().takeIf { it > 0 } ?: createSec
                    val model = md?.optString("model_slug")?.ifBlank { null } ?: defaultModel
                    chars += text.length

                    messages.add(
                        Message(
                            id = nodeId,
                            convId = convId,
                            role = role,
                            text = text,
                            reasoning = reasoning,
                            ts = ts,
                            order = ord,
                            contentType = ctype,
                            model = model
                        )
                    )
                }

                if (messages.isEmpty()) {
                    skipped++
                    continue
                }

                db.beginTransaction()
                try {
                    store.clearConvContent(db, convId)
                    store.putConversation(
                        db,
                        Conversation(
                            id = convId,
                            title = title,
                            createTs = createSec,
                            updateTs = updateSec,
                            msgCount = messages.size,
                            charCount = chars,
                            model = defaultModel,
                            category = Classifier.classify(title),
                            starred = existing != null && store.starOf(convId),
                            attachmentCount = attachmentsInConv
                        )
                    )
                    for (m in messages) store.putMessage(db, m)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                msgTotal += messages.size
                if (existing == null) added++ else updated++
            }
        }

        // 附件实体还原
        onProgress(Progress("还原附件", 0, pending.size + datFiles.size))
        val blobDir = File(ctx.filesDir, "blobs").apply { mkdirs() }
        val referenced = HashSet<String>()
        var attOk = 0
        var attMissing = 0

        db.beginTransaction()
        try {
            for ((idx, pa) in pending.withIndex()) {
                coroutineContext.ensureActive()
                if (idx % 20 == 0) onProgress(Progress("还原附件", idx, pending.size))
                referenced.add(pa.id)
                val src = datFiles[pa.id]
                var kind = kindOfName(pa.name)
                var path: String? = null
                var exists = false
                var size = pa.size
                if (src != null) {
                    val probe = sniff(src)
                    kind = if (probe.first == "zip" && kindOfName(pa.name) == "doc") "doc" else probe.first
                    val target = File(blobDir, "${pa.id}.${probe.second}")
                    if (!target.exists() || target.length() != src.size) {
                        runCatching {
                            src.open().use { input -> target.outputStream().use { input.copyTo(it) } }
                        }
                    }
                    if (target.exists() && target.length() > 0) {
                        path = target.absolutePath
                        exists = true
                        size = target.length()
                        attOk++
                    }
                }
                if (!exists) attMissing++
                store.putAttachment(
                    db,
                    Attachment(
                        id = pa.id,
                        convId = pa.convId,
                        msgId = pa.msgId,
                        name = pa.name,
                        mime = pa.mime.ifBlank { guessMime(pa.name) },
                        size = size,
                        path = path,
                        kind = kind,
                        exists = exists
                    )
                )
            }

            // 未被任何会话引用的导出文件，也登记到「文件」里
            var orphan = 0
            for ((id, src) in datFiles) {
                if (id in referenced) continue
                val probe = sniff(src)
                val displayName = nameMap["$id.dat"] ?: id
                val orphanKind = if (probe.first == "zip" && kindOfName(displayName) == "doc") "doc" else probe.first
                val target = File(blobDir, "$id.${probe.second}")
                if (!target.exists() || target.length() != src.size) {
                    runCatching { src.open().use { input -> target.outputStream().use { input.copyTo(it) } } }
                }
                val ok = target.exists() && target.length() > 0
                store.putAttachment(
                    db,
                    Attachment(
                        id = id,
                        convId = null,
                        msgId = null,
                        name = displayName,
                        mime = guessMime(displayName),
                        size = if (ok) target.length() else src.size,
                        path = if (ok) target.absolutePath else null,
                        kind = orphanKind,
                        exists = ok
                    )
                )
                orphan++
            }
            db.setTransactionSuccessful()
            return ImportSummary(
                added = added,
                updated = updated,
                skipped = skipped,
                messages = msgTotal,
                attachments = attOk,
                missing = attMissing,
                orphanFiles = orphan,
                files = jsonFiles.size + datFiles.size
            )
        } finally {
            db.endTransaction()
        }
    }

    /** 沿 current_node 向上回溯，得到真正展示的对话路径 */
    private fun buildPath(mapping: JSONObject, current: String): List<String> {
        if (current.isBlank()) {
            // 兜底：找没有子节点的叶子
            return emptyList()
        }
        val ids = ArrayList<String>()
        var cur: String? = current
        val guard = HashSet<String>()
        while (!cur.isNullOrBlank() && guard.add(cur)) {
            val node = mapping.optJSONObject(cur) ?: break
            ids.add(cur)
            cur = node.optString("parent").ifBlank { null }
        }
        ids.reverse()
        return ids
    }

    private fun parseNameMap(src: Source): Map<String, String> {
        return try {
            val text = src.open().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            val map = HashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = o.optString(k)
            }
            map
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** 用文件头判断真实类型，返回 (kind, 扩展名) */
    private fun sniff(src: Source): Pair<String, String> {
        val head = ByteArray(16)
        val n = try {
            src.open().use { it.read(head) }
        } catch (t: Throwable) {
            -1
        }
        if (n <= 0) return "other" to "bin"
        fun u(i: Int): Int = head[i].toInt() and 0xFF
        return when {
            n >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "image" to "jpg"
            n >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> "image" to "png"
            n >= 4 && u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 -> "image" to "gif"
            n >= 12 && u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 -> "image" to "webp"
            n >= 4 && u(0) == 0x25 && u(1) == 0x50 && u(2) == 0x44 && u(3) == 0x46 -> "pdf" to "pdf"
            n >= 4 && u(0) == 0x50 && u(1) == 0x4B && (u(2) == 0x03 || u(2) == 0x05) -> "zip" to "zip"
            n >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> "video" to "mp4"
            looksLikeText(head, n) -> "text" to "txt"
            else -> "other" to "bin"
        }
    }

    private fun looksLikeText(head: ByteArray, n: Int): Boolean {
        var printable = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return false
            if (b == 9 || b == 10 || b == 13 || b in 32..126 || b >= 0xC0) printable++
        }
        return printable >= n * 0.85
    }

    private fun kindOfName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "image"
            "mp4", "mov", "mkv", "webm", "avi" -> "video"
            "zip", "rar", "7z", "apk", "jar" -> "zip"
            "pdf" -> "pdf"
            "txt", "md", "json", "kml", "xml", "csv", "log", "html", "htm", "ini", "yaml", "yml", "sh", "bat" -> "text"
            "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> "doc"
            else -> "other"
        }
    }
}
