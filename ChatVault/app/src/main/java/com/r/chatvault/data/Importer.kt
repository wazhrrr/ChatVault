package com.r.chatvault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.r.chatvault.R
import com.r.chatvault.util.AppLocale
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * 导入器：
 *  - ChatGPT 导出包（文件夹 / ZIP）：增量导入、保留全部分支、抽取代码块与文本文档
 *  - Markdown 聊天记录：多选文件，按文件修改时间作为会话时间
 */
class Importer(private val ctx: Context, private val store: Store) {

    data class Progress(val phase: String, val done: Int, val total: Int)

    private fun str(resId: Int, vararg args: Any): String = AppLocale.str(ctx, resId, *args)

    private class Source(val name: String, val size: Long, val open: () -> InputStream)

    private class PendingAttachment(
        val id: String,
        val convId: String,
        val msgId: String,
        val name: String,
        val mime: String,
        val size: Long
    )

    private class ParsedContent(val text: String, val pointers: List<String>, val ctype: String)

    private class NodeData(
        val role: String,
        val text: String,
        val reasoning: String?,
        val ctype: String,
        val ts: Long,
        val model: String?
    )

    // ================= ChatGPT 导出包 =================

    suspend fun importFrom(uri: Uri, force: Boolean = false, onProgress: (Progress) -> Unit): ImportSummary =
        withContext(Dispatchers.IO) {
            val label = displayName(uri) ?: ""
            val isZip = label.endsWith(".zip", true) || label.contains("zip", true) && !label.endsWith(".json", true)

            var tempDir: File? = null
            val sources: List<Source> = if (isZip) {
                val dir = File(ctx.cacheDir, "inbox_${System.currentTimeMillis()}")
                dir.mkdirs()
                tempDir = dir
                onProgress(Progress(str(R.string.phase_unzip), 0, 0))
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
                    out.add(
                        Source(f.name ?: "unknown", f.length()) {
                            ctx.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException(str(R.string.err_read_file, f.name ?: ""))
                        }
                    )
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
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException(str(R.string.err_open_zip))
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
            throw IllegalStateException(str(R.string.err_no_conversations))
        }

        var added = 0
        var updated = 0
        var skipped = 0
        var msgTotal = 0
        val pending = ArrayList<PendingAttachment>()
        val db = store.writableDatabase

        jsonFiles.forEachIndexed { index, src ->
            coroutineContext.ensureActive()
            onProgress(Progress(str(R.string.phase_parse_convs), index, jsonFiles.size))
            val arr = try {
                JSONArray(src.open().bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (t: Throwable) {
                throw IllegalStateException(str(R.string.err_parse_file, src.name, t.message ?: ""))
            }
            for (i in 0 until arr.length()) {
                coroutineContext.ensureActive()
                val o = arr.optJSONObject(i) ?: continue
                val convId = o.optString("id").ifBlank { o.optString("conversation_id") }
                if (convId.isBlank()) continue
                val title = o.optString("title").ifBlank { str(R.string.unnamed_chat) }
                val createSec = o.optDouble("create_time", 0.0).toLong()
                val updateSec = o.optDouble("update_time", createSec.toDouble()).toLong()
                val existing = store.existingUpdateTs(convId)
                // 旧版本导入的数据没有分支信息；同版本或更新的包重导一次即可补齐，且不覆盖更新的数据
                val needsBranchUpgrade =
                    existing != null && !store.hasBranchInfo(convId) && existing <= updateSec
                // 上次导入在附件阶段被中断：补解析一次，避免附件永久缺失
                val needsAttachmentRepair = existing != null && store.needsAttachmentRepair(convId)

                if (!force && !needsBranchUpgrade && !needsAttachmentRepair &&
                    existing != null && existing >= updateSec && updateSec > 0
                ) {
                    skipped++
                    continue
                }

                val mapping = o.optJSONObject("mapping") ?: continue
                val activePath = buildPath(mapping, o.optString("current_node"))
                if (activePath.isEmpty()) {
                    skipped++
                    continue
                }
                val activeSet = activePath.toHashSet()
                val activeOrd = HashMap<String, Int>()
                activePath.forEachIndexed { idx, id -> activeOrd[id] = idx }

                val defaultModel = o.optString("default_model_slug").ifBlank { null }
                val nodes = LinkedHashMap<String, NodeData>()
                var attachmentsInConv = 0

                // 1) 收集 mapping 里所有 user/assistant 节点，标记哪些在活跃路径上
                val keys = mapping.keys()
                while (keys.hasNext()) {
                    val nodeId = keys.next()
                    val node = mapping.optJSONObject(nodeId) ?: continue
                    val msg = node.optJSONObject("message") ?: continue
                    val role = msg.optJSONObject("author")?.optString("role") ?: "assistant"
                    if (role != "user" && role != "assistant") continue

                    val parsed = parseContent(msg.optJSONObject("content"))
                    var text = parsed.text
                    var reasoning: String? = null
                    if (parsed.ctype == "thoughts" || parsed.ctype == "reasoning_recap") {
                        reasoning = text
                        text = ""
                    }

                    val md = msg.optJSONObject("metadata")
                    val ts = msg.optDouble("create_time", 0.0).toLong().takeIf { it > 0 } ?: createSec
                    // optString 会把 JSON null 变成字符串 "null"，这里显式排除
                    val slug = if (md == null || md.isNull("model_slug")) null else md.optString("model_slug")
                    val model = slug?.ifBlank { null } ?: defaultModel
                    val isActive = nodeId in activeSet

                    if (isActive && md != null) {
                        val atts = md.optJSONArray("attachments")
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
                        for (pid in parsed.pointers) {
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
                        attachmentsInConv += (metaIds + parsed.pointers).size
                    }

                    nodes[nodeId] = NodeData(role, text, reasoning, parsed.ctype, ts, model)
                }

                if (nodes.isEmpty()) {
                    skipped++
                    continue
                }

                // 2) 计算可见的消息级父子关系（跳过 system/tool 节点）
                val parentOf = HashMap<String, String?>()
                for (id in nodes.keys) {
                    var p = mapping.optJSONObject(id)?.optString("parent")?.ifBlank { null }
                    var guard = 0
                    while (p != null && p !in nodes && guard < 500) {
                        p = mapping.optJSONObject(p)?.optString("parent")?.ifBlank { null }
                        guard++
                    }
                    parentOf[id] = if (p != null && p in nodes) p else null
                }
                val childMap = HashMap<String, MutableList<String>>()
                for ((id, p) in parentOf) {
                    if (p != null) childMap.getOrPut(p) { ArrayList() }.add(id)
                }

                // 3) 生成消息（含废弃分支，active=0）
                val messages = ArrayList<Message>(nodes.size)
                var chars = 0
                var activeCount = 0
                for ((id, nd) in nodes) {
                    val isActive = id in activeSet
                    if (isActive) {
                        chars += nd.text.length
                        activeCount++
                    }
                    val kids = childMap[id] ?: emptyList()
                    messages.add(
                        Message(
                            id = id,
                            convId = convId,
                            role = nd.role,
                            text = nd.text,
                            reasoning = nd.reasoning,
                            ts = nd.ts,
                            order = activeOrd[id] ?: -1,
                            contentType = nd.ctype,
                            model = nd.model,
                            parentId = parentOf[id],
                            childIds = JSONArray(kids).toString(),
                            active = isActive
                        )
                    )
                }
                if (activeCount == 0) {
                    skipped++
                    continue
                }

                db.beginTransaction()
                try {
                    val keepStar = existing != null && store.starOf(convId)
                    val keepAlias = store.aliasOf(convId)
                    store.clearConvContent(db, convId)
                    store.putConversation(
                        db,
                        Conversation(
                            id = convId,
                            title = title,
                            createTs = createSec,
                            updateTs = updateSec,
                            msgCount = activeCount,
                            charCount = chars,
                            model = defaultModel,
                            category = Classifier.classify(title),
                            starred = keepStar,
                            attachmentCount = attachmentsInConv,
                            alias = keepAlias
                        )
                    )
                    for (m in messages) {
                        store.putMessage(db, m)
                        store.putCodeBlocksForMessage(db, m)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                msgTotal += activeCount
                if (existing == null) added++ else updated++
            }
        }

        // 附件实体还原
        onProgress(Progress(str(R.string.phase_restore_atts), 0, pending.size + datFiles.size))
        val blobDir = File(ctx.filesDir, "blobs").apply { mkdirs() }
        // 已被会话引用的附件：包含本次跳过的会话，防止重复导入时把附件误降级成孤儿
        val referenced = HashSet<String>(store.referencedAttachmentIds())

        db.beginTransaction()
        try {
            for ((idx, pa) in pending.withIndex()) {
                coroutineContext.ensureActive()
                if (idx % 20 == 0) onProgress(Progress(str(R.string.phase_restore_atts), idx, pending.size))
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
                        if (kind == "text") {
                            val text = readFileText(target, 200_000)
                            if (text.isNotBlank()) store.putAttachmentText(db, pa.id, pa.convId, pa.name, text)
                        }
                    }
                }
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
                if (ok && orphanKind == "text") {
                    val text = readFileText(target, 200_000)
                    if (text.isNotBlank()) store.putAttachmentText(db, id, null, displayName, text)
                }
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
            }
            db.setTransactionSuccessful()
            val attTotals = store.attachmentTotals()
            return ImportSummary(
                added = added,
                updated = updated,
                skipped = skipped,
                messages = msgTotal,
                attachments = attTotals.first,
                missing = attTotals.second,
                orphanFiles = attTotals.third,
                files = jsonFiles.size + datFiles.size
            )
        } finally {
            db.endTransaction()
        }
    }

    // ================= Markdown 导入 =================

    suspend fun importMarkdown(uris: List<Uri>, onProgress: (Progress) -> Unit): ImportSummary =
        withContext(Dispatchers.IO) {
            var added = 0
            var updated = 0
            var skipped = 0
            var msgTotal = 0
            val db = store.writableDatabase

            uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onProgress(Progress(str(R.string.phase_parse_md), index, uris.size))
                try {
                    val name = displayName(uri) ?: "note.md"
                    val lname = name.lowercase()
                    if (!lname.endsWith(".md") && !lname.endsWith(".markdown") && !lname.endsWith(".txt")) {
                        skipped++
                        return@forEachIndexed
                    }
                    val raw = readUriText(uri)
                    if (raw.isBlank()) {
                        skipped++
                        return@forEachIndexed
                    }
                    val fallback = name.substringBeforeLast('.').ifBlank { str(R.string.unnamed_note) }
                    val parsed = MarkdownChat.parse(raw, fallback)
                    if (parsed.messages.isEmpty()) {
                        skipped++
                        return@forEachIndexed
                    }

                    val convId = "md-" + sha1("$name|${parsed.title}").take(20)
                    val mtime = DocumentFile.fromSingleUri(ctx, uri)?.lastModified()?.div(1000) ?: 0L
                    val baseTs = if (mtime > 0) mtime else System.currentTimeMillis() / 1000
                    val existing = store.existingUpdateTs(convId)
                    if (existing != null && existing >= baseTs) {
                        skipped++
                        return@forEachIndexed
                    }
                    val chars = parsed.messages.sumOf { it.text.length }

                    db.beginTransaction()
                    try {
                        val keepStar = existing != null && store.starOf(convId)
                        val keepAlias = store.aliasOf(convId)
                        store.clearConvContent(db, convId)
                        store.putConversation(
                            db,
                            Conversation(
                                id = convId,
                                title = parsed.title,
                                createTs = baseTs,
                                updateTs = baseTs,
                                msgCount = parsed.messages.size,
                                charCount = chars,
                                model = null,
                                category = Classifier.classify(parsed.title),
                                starred = keepStar,
                                attachmentCount = 0,
                                alias = keepAlias
                            )
                        )
                        parsed.messages.forEachIndexed { ord, m ->
                            val msg = Message(
                                id = "$convId-$ord",
                                convId = convId,
                                role = m.role,
                                text = m.text,
                                reasoning = m.reasoning,
                                ts = baseTs + ord,
                                order = ord,
                                contentType = "text",
                                model = null
                            )
                            store.putMessage(db, msg)
                            store.putCodeBlocksForMessage(db, msg)
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    msgTotal += parsed.messages.size
                    if (existing == null) added++ else updated++
                } catch (t: Throwable) {
                    skipped++
                }
            }

            ImportSummary(
                added = added,
                updated = updated,
                skipped = skipped,
                messages = msgTotal,
                attachments = 0,
                missing = 0,
                orphanFiles = 0,
                files = uris.size,
                mdFiles = added + updated
            )
        }

    private fun readUriText(uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        return if (bad > 0 && bad * 20 > utf8.length) {
            runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
        } else utf8
    }

    private fun readFileText(file: File, limit: Int): String {
        if (!file.exists() || file.length() == 0L) return ""
        return runCatching {
            file.bufferedReader().use { r ->
                val buf = CharArray(limit)
                var n = 0
                while (n < limit) {
                    val read = r.read(buf, n, limit - n)
                    if (read <= 0) break
                    n += read
                }
                String(buf, 0, n)
            }
        }.getOrDefault("")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ================= 解析辅助 =================

    private fun parseContent(content: JSONObject?): ParsedContent {
        val sb = StringBuilder()
        val pointers = LinkedHashSet<String>()
        val ctype = content?.optString("content_type")?.ifBlank { "text" } ?: "text"
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
                            pointers.add(ptr.removePrefix("file-service://"))
                        }
                    }
                }
            }
        }
        return ParsedContent(sb.toString(), pointers.toList(), ctype)
    }

    /** 沿 current_node 向上回溯，得到真正展示的对话路径 */
    private fun buildPath(mapping: JSONObject, current: String): List<String> {
        if (current.isBlank()) return emptyList()
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
