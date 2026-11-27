package com.r.chatvault.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.r.chatvault.util.CodeFences
import java.io.File

enum class SortMode { NEWEST, OLDEST, MESSAGES, CHARS, TITLE }

class Store(context: Context) : SQLiteOpenHelper(context, "chatvault.db", null, DB_VERSION) {

    private val appContext = context.applicationContext

    init {
        // 结构升级前留一份 DB 快照，万一迁移出问题还能回退
        runCatching { backupBeforeUpgrade() }
    }

    // ---------- 建表 / 迁移 ----------

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                create_ts INTEGER NOT NULL DEFAULT 0,
                update_ts INTEGER NOT NULL DEFAULT 0,
                msg_count INTEGER NOT NULL DEFAULT 0,
                char_count INTEGER NOT NULL DEFAULT 0,
                model TEXT,
                category TEXT NOT NULL DEFAULT '其他',
                starred INTEGER NOT NULL DEFAULT 0,
                attachment_count INTEGER NOT NULL DEFAULT 0,
                alias TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages(
                id TEXT PRIMARY KEY,
                conv_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL DEFAULT '',
                reasoning TEXT,
                ts INTEGER NOT NULL DEFAULT 0,
                ord INTEGER NOT NULL DEFAULT 0,
                content_type TEXT,
                model TEXT,
                parent_id TEXT,
                child_ids TEXT,
                active INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE attachments(
                id TEXT PRIMARY KEY,
                conv_id TEXT,
                msg_id TEXT,
                name TEXT NOT NULL,
                mime TEXT,
                size INTEGER NOT NULL DEFAULT 0,
                path TEXT,
                kind TEXT NOT NULL DEFAULT 'other',
                exists_flag INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conv_id, ord)")
        db.execSQL("CREATE INDEX idx_msg_ts ON messages(ts)")
        db.execSQL("CREATE INDEX idx_att_conv ON attachments(conv_id)")
        db.execSQL("CREATE INDEX idx_conv_create ON conversations(create_ts)")
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 只加不删：老数据继续可用
            runCatching { db.execSQL("ALTER TABLE conversations ADD COLUMN alias TEXT") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN parent_id TEXT") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN child_ids TEXT") }
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN active INTEGER NOT NULL DEFAULT 1") }
            createV2Tables(db)
        }
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE clips ADD COLUMN prev_note TEXT") }
            runCatching { db.execSQL("ALTER TABLE clips ADD COLUMN prev_tags TEXT") }
        }
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clips(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_id TEXT,
                msg_id TEXT,
                text TEXT NOT NULL DEFAULT '',
                note TEXT,
                tags TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT 'clip',
                created_ts INTEGER NOT NULL DEFAULT 0,
                prev_note TEXT,
                prev_tags TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS code_blocks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv_id TEXT NOT NULL,
                msg_id TEXT NOT NULL,
                lang TEXT NOT NULL DEFAULT 'text',
                code TEXT NOT NULL,
                ts INTEGER NOT NULL DEFAULT 0,
                ord INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tags(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conv_tags(
                conv_id TEXT NOT NULL,
                tag_id INTEGER NOT NULL,
                PRIMARY KEY(conv_id, tag_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachment_text(
                att_id TEXT PRIMARY KEY,
                conv_id TEXT,
                name TEXT,
                text TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_active ON messages(active, conv_id, ord)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_parent ON messages(parent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_code_conv ON code_blocks(conv_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_code_lang ON code_blocks(lang)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clip_ts ON clips(created_ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_convtags_tag ON conv_tags(tag_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_atttext_conv ON attachment_text(conv_id)")
    }

    private fun backupBeforeUpgrade() {
        val dbFile = appContext.getDatabasePath("chatvault.db")
        if (!dbFile.exists() || dbFile.length() == 0L) return
        val version = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (t: Throwable) {
            return
        }
        if (version >= DB_VERSION) return
        val dir = File(appContext.filesDir, "backup").apply { mkdirs() }
        val target = File(dir, "chatvault-v$version-${System.currentTimeMillis()}.db")
        dbFile.copyTo(target, overwrite = true)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    // ---------- 写入 ----------

    fun existingUpdateTs(convId: String): Long? {
        readableDatabase.rawQuery("SELECT update_ts FROM conversations WHERE id = ?", arrayOf(convId)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun starOf(convId: String): Boolean {
        readableDatabase.rawQuery("SELECT starred FROM conversations WHERE id = ?", arrayOf(convId)).use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    fun aliasOf(convId: String): String? {
        readableDatabase.rawQuery("SELECT alias FROM conversations WHERE id = ?", arrayOf(convId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** 旧版本导入的会话没有 child_ids；下次导入时据此自动重解析，补齐分支 */
    fun hasBranchInfo(convId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT child_ids FROM messages WHERE conv_id = ? LIMIT 1", arrayOf(convId)
        ).use { c ->
            return c.moveToFirst() && c.getString(0) != null
        }
    }

    /** 会话声明有附件、但一条附件记录都没有：说明上次导入在附件阶段被中断 */
    fun needsAttachmentRepair(convId: String): Boolean {
        val expected = readableDatabase.rawQuery(
            "SELECT attachment_count FROM conversations WHERE id = ?", arrayOf(convId)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (expected <= 0) return false
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM attachments WHERE conv_id = ?", arrayOf(convId)
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) == 0
        }
    }

    fun putConversation(db: SQLiteDatabase, c: Conversation) {
        val cv = ContentValues().apply {
            put("id", c.id)
            put("title", c.title)
            put("create_ts", c.createTs)
            put("update_ts", c.updateTs)
            put("msg_count", c.msgCount)
            put("char_count", c.charCount)
            put("model", c.model)
            put("category", c.category)
            put("starred", if (c.starred) 1 else 0)
            put("attachment_count", c.attachmentCount)
            put("alias", c.alias)
        }
        db.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearConvContent(db: SQLiteDatabase, convId: String) {
        db.delete("attachment_text", "conv_id = ?", arrayOf(convId))
        db.delete("code_blocks", "conv_id = ?", arrayOf(convId))
        db.delete("messages", "conv_id = ?", arrayOf(convId))
        db.delete("attachments", "conv_id = ?", arrayOf(convId))
    }

    fun putMessage(db: SQLiteDatabase, m: Message) {
        val cv = ContentValues().apply {
            put("id", m.id)
            put("conv_id", m.convId)
            put("role", m.role)
            put("text", m.text)
            put("reasoning", m.reasoning)
            put("ts", m.ts)
            put("ord", m.order)
            put("content_type", m.contentType)
            put("model", m.model)
            put("parent_id", m.parentId)
            put("child_ids", m.childIds)
            put("active", if (m.active) 1 else 0)
        }
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putAttachment(db: SQLiteDatabase, a: Attachment) {
        val cv = ContentValues().apply {
            put("id", a.id)
            put("conv_id", a.convId)
            put("msg_id", a.msgId)
            put("name", a.name)
            put("mime", a.mime)
            put("size", a.size)
            put("path", a.path)
            put("kind", a.kind)
            put("exists_flag", if (a.exists) 1 else 0)
        }
        db.insertWithOnConflict("attachments", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putCodeBlocksForMessage(db: SQLiteDatabase, m: Message) {
        if (!m.active || !m.text.contains("```")) return
        CodeFences.extract(m.text).forEach { block ->
            val cv = ContentValues().apply {
                put("conv_id", m.convId)
                put("msg_id", m.id)
                put("lang", block.lang)
                put("code", block.code)
                put("ts", m.ts)
                put("ord", m.order)
            }
            db.insert("code_blocks", null, cv)
        }
    }

    fun putAttachmentText(db: SQLiteDatabase, attId: String, convId: String?, name: String, text: String) {
        if (text.isBlank()) return
        val cv = ContentValues().apply {
            put("att_id", attId)
            put("conv_id", convId)
            put("name", name)
            put("text", text)
        }
        db.insertWithOnConflict("attachment_text", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setStar(id: String, starred: Boolean) {
        writableDatabase.execSQL(
            "UPDATE conversations SET starred = ? WHERE id = ?",
            arrayOf(if (starred) 1 else 0, id)
        )
    }

    fun setAlias(id: String, alias: String?) {
        writableDatabase.execSQL(
            "UPDATE conversations SET alias = ? WHERE id = ?",
            arrayOf(alias?.trim()?.takeIf { it.isNotBlank() }, id)
        )
    }

    fun setTags(convId: String, names: List<String>) {
        val db = writableDatabase
        val clean = names.map { it.trim().take(30) }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }.take(20)
        db.beginTransaction()
        try {
            db.delete("conv_tags", "conv_id = ?", arrayOf(convId))
            for (name in clean) {
                val cv = ContentValues().apply { put("name", name) }
                db.insertWithOnConflict("tags", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                var tagId = -1L
                db.rawQuery("SELECT id FROM tags WHERE name = ? COLLATE NOCASE", arrayOf(name)).use { c ->
                    if (c.moveToFirst()) tagId = c.getLong(0)
                }
                if (tagId > 0) {
                    val link = ContentValues().apply {
                        put("conv_id", convId)
                        put("tag_id", tagId)
                    }
                    db.insertWithOnConflict("conv_tags", null, link, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM conv_tags)")
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("clips", null, null)
            db.delete("code_blocks", null, null)
            db.delete("conv_tags", null, null)
            db.delete("tags", null, null)
            db.delete("attachment_text", null, null)
            db.delete("messages", null, null)
            db.delete("attachments", null, null)
            db.delete("conversations", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删除一个会话及其消息、附件实体、摘录、代码块、标签关联 */
    fun deleteConversation(id: String) {
        val paths = ArrayList<String>()
        val ids = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT id, path FROM attachments WHERE conv_id = ?", arrayOf(id)
        ).use { c ->
            while (c.moveToNext()) {
                ids.add(c.getString(0))
                c.getString(1)?.let { paths.add(it) }
            }
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("attachment_text", "conv_id = ?", arrayOf(id))
            db.delete("code_blocks", "conv_id = ?", arrayOf(id))
            db.delete("clips", "conv_id = ?", arrayOf(id))
            db.delete("conv_tags", "conv_id = ?", arrayOf(id))
            db.delete("messages", "conv_id = ?", arrayOf(id))
            db.delete("attachments", "conv_id = ?", arrayOf(id))
            db.delete("conversations", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM conv_tags)")

        for (path in paths) runCatching { File(path).delete() }
        val blobs = File(appContext.filesDir, "blobs")
        for (attId in ids) {
            val still = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM attachments WHERE id = ?", arrayOf(attId)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (still == 0) {
                blobs.listFiles()?.filter { it.isFile && it.name.startsWith("$attId.") }
                    ?.forEach { runCatching { it.delete() } }
            }
        }
    }

    // ---------- 查询 ----------

    fun listConversations(
        starredOnly: Boolean,
        year: Int?,
        sort: SortMode,
        category: String? = null,
        tag: String? = null,
        model: String? = null,
        fromTs: Long? = null,
        toTs: Long? = null
    ): List<Conversation> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (starredOnly) where.append(" AND c.starred = 1")
        if (year != null) {
            where.append(" AND strftime('%Y', c.create_ts, 'unixepoch', 'localtime') = ?")
            args.add(year.toString())
        }
        if (category != null) {
            where.append(" AND c.category = ?")
            args.add(category)
        }
        if (!model.isNullOrBlank()) {
            if (model == MODEL_UNKNOWN) {
                where.append(
                    " AND EXISTS (SELECT 1 FROM messages mm WHERE mm.conv_id = c.id AND mm.active = 1 " +
                        "AND " + unknownModelSql("mm.model") + ")"
                )
            } else {
                where.append(
                    " AND EXISTS (SELECT 1 FROM messages mm WHERE mm.conv_id = c.id AND mm.active = 1 AND mm.model = ?)"
                )
                args.add(model)
            }
        }
        if (fromTs != null) {
            where.append(" AND c.create_ts >= ?")
            args.add(fromTs.toString())
        }
        if (toTs != null) {
            where.append(" AND c.create_ts <= ?")
            args.add(toTs.toString())
        }
        if (!tag.isNullOrBlank()) {
            where.append(
                " AND EXISTS (SELECT 1 FROM conv_tags ct JOIN tags t ON t.id = ct.tag_id " +
                    "WHERE ct.conv_id = c.id AND t.name = ? COLLATE NOCASE)"
            )
            args.add(tag)
        }
        val order = when (sort) {
            SortMode.NEWEST -> "c.create_ts DESC"
            SortMode.OLDEST -> "c.create_ts ASC"
            SortMode.MESSAGES -> "c.msg_count DESC"
            SortMode.CHARS -> "c.char_count DESC"
            SortMode.TITLE -> "COALESCE(c.alias, c.title) COLLATE NOCASE ASC"
        }
        val sql = """
            SELECT c.*,
                   (SELECT GROUP_CONCAT(t.name, ',') FROM conv_tags ct JOIN tags t ON t.id = ct.tag_id
                    WHERE ct.conv_id = c.id) AS tags_csv
            FROM conversations c WHERE $where ORDER BY $order
        """.trimIndent()
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(readConv(c))
        }
        return out
    }

    fun conversation(id: String): Conversation? {
        val sql = """
            SELECT c.*,
                   (SELECT GROUP_CONCAT(t.name, ',') FROM conv_tags ct JOIN tags t ON t.id = ct.tag_id
                    WHERE ct.conv_id = c.id) AS tags_csv
            FROM conversations c WHERE c.id = ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(id)).use { c ->
            return if (c.moveToFirst()) readConv(c) else null
        }
    }

    private fun readConv(c: Cursor) = Conversation(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        createTs = c.getLong(c.getColumnIndexOrThrow("create_ts")),
        updateTs = c.getLong(c.getColumnIndexOrThrow("update_ts")),
        msgCount = c.getInt(c.getColumnIndexOrThrow("msg_count")),
        charCount = c.getInt(c.getColumnIndexOrThrow("char_count")),
        model = c.getString(c.getColumnIndexOrThrow("model")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
        attachmentCount = c.getInt(c.getColumnIndexOrThrow("attachment_count")),
        alias = c.getStringOrNull("alias"),
        tagsCsv = c.getStringOrNull("tags_csv") ?: ""
    )

    private fun Cursor.getStringOrNull(name: String): String? {
        val idx = getColumnIndex(name)
        return if (idx >= 0) getString(idx) else null
    }

    fun messages(convId: String): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conv_id = ? AND active = 1 ORDER BY ord ASC", arrayOf(convId)
        ).use { c ->
            while (c.moveToNext()) out.add(readMessage(c))
        }
        return out
    }

    private fun readMessage(c: Cursor) = Message(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        convId = c.getString(c.getColumnIndexOrThrow("conv_id")),
        role = c.getString(c.getColumnIndexOrThrow("role")),
        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
        reasoning = c.getString(c.getColumnIndexOrThrow("reasoning")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        order = c.getInt(c.getColumnIndexOrThrow("ord")),
        contentType = c.getString(c.getColumnIndexOrThrow("content_type")) ?: "text",
        model = c.getString(c.getColumnIndexOrThrow("model")),
        parentId = c.getStringOrNull("parent_id"),
        childIds = c.getStringOrNull("child_ids") ?: "",
        active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
    )

    fun messageById(id: String): Message? {
        readableDatabase.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) readMessage(c) else null
        }
    }

    fun childrenOf(msgId: String): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE parent_id = ? ORDER BY active DESC, ts ASC", arrayOf(msgId)
        ).use { c -> while (c.moveToNext()) out.add(readMessage(c)) }
        return out
    }

    /** 沿某条分支一直往下走到叶子，用于只读预览「另一个版本」 */
    fun branchChain(startId: String): List<Message> {
        val out = ArrayList<Message>()
        val seen = HashSet<String>()
        var cur: String? = startId
        while (cur != null && seen.add(cur) && out.size < 300) {
            val m = messageById(cur) ?: break
            out.add(m)
            cur = childrenOf(cur).maxByOrNull { it.ts }?.id
        }
        return out
    }

    fun attachmentsOfConv(convId: String): List<Attachment> {
        val out = ArrayList<Attachment>()
        readableDatabase.rawQuery(
            "SELECT * FROM attachments WHERE conv_id = ? ORDER BY name ASC", arrayOf(convId)
        ).use { c ->
            while (c.moveToNext()) out.add(readAttachment(c))
        }
        return out
    }

    fun allAttachments(onlyImages: Boolean = false): List<Attachment> {
        val sql = if (onlyImages) {
            "SELECT * FROM attachments WHERE kind = 'image' ORDER BY conv_id DESC, name ASC"
        } else {
            "SELECT * FROM attachments ORDER BY (conv_id IS NULL), conv_id DESC, name ASC"
        }
        val out = ArrayList<Attachment>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out.add(readAttachment(c))
        }
        return out
    }

    fun missingAttachments(): List<Attachment> {
        val out = ArrayList<Attachment>()
        readableDatabase.rawQuery(
            "SELECT * FROM attachments WHERE exists_flag = 0 ORDER BY (conv_id IS NULL), conv_id DESC, name ASC", null
        ).use { c -> while (c.moveToNext()) out.add(readAttachment(c)) }
        return out
    }

    fun orphanAttachments(): List<Attachment> {
        val out = ArrayList<Attachment>()
        readableDatabase.rawQuery(
            "SELECT * FROM attachments WHERE conv_id IS NULL ORDER BY name ASC", null
        ).use { c -> while (c.moveToNext()) out.add(readAttachment(c)) }
        return out
    }

    /** 库内附件总览：实体数 / 缺失数 / 未引用数 */
    fun attachmentTotals(): Triple<Int, Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(CASE WHEN exists_flag = 1 THEN 1 ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN exists_flag = 0 THEN 1 ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN conv_id IS NULL THEN 1 ELSE 0 END),0) FROM attachments", null
        ).use { c ->
            return if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2))
            else Triple(0, 0, 0)
        }
    }

    /** 已被任一会话引用的附件 id，重复导入时用来避免把附件误降级成孤儿 */
    fun referencedAttachmentIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT id FROM attachments WHERE conv_id IS NOT NULL", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    private fun readAttachment(c: Cursor) = Attachment(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        convId = c.getString(c.getColumnIndexOrThrow("conv_id")),
        msgId = c.getString(c.getColumnIndexOrThrow("msg_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        mime = c.getString(c.getColumnIndexOrThrow("mime")) ?: "",
        size = c.getLong(c.getColumnIndexOrThrow("size")),
        path = c.getString(c.getColumnIndexOrThrow("path")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")) ?: "other",
        exists = c.getInt(c.getColumnIndexOrThrow("exists_flag")) == 1
    )

    fun counts(): Triple<Int, Int, Int> {
        val db = readableDatabase
        var convs = 0
        var msgs = 0
        var atts = 0
        db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { if (it.moveToFirst()) convs = it.getInt(0) }
        db.rawQuery("SELECT COUNT(*) FROM messages WHERE active = 1", null).use { if (it.moveToFirst()) msgs = it.getInt(0) }
        db.rawQuery("SELECT COUNT(*) FROM attachments WHERE exists_flag = 1", null).use { if (it.moveToFirst()) atts = it.getInt(0) }
        return Triple(convs, msgs, atts)
    }

    fun clipCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM clips", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------- 标签 ----------

    fun tagsOf(convId: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT t.name FROM tags t JOIN conv_tags ct ON ct.tag_id = t.id WHERE ct.conv_id = ? ORDER BY t.name",
            arrayOf(convId)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun allTags(): List<TagCount> {
        val out = ArrayList<TagCount>()
        readableDatabase.rawQuery(
            "SELECT t.name, COUNT(ct.conv_id) FROM tags t JOIN conv_tags ct ON ct.tag_id = t.id " +
                "GROUP BY t.id ORDER BY 2 DESC, t.name LIMIT 40", null
        ).use { c -> while (c.moveToNext()) out.add(TagCount(c.getString(0), c.getInt(1))) }
        return out
    }

    fun categoryNames(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM conversations WHERE category IS NOT NULL AND category != '' " +
                "ORDER BY category", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 消息没有记录模型时的 SQL 判定（含历史脏值 "null"） */
    private fun unknownModelSql(col: String) =
        "($col IS NULL OR $col = '' OR $col = 'null')"

    fun modelNames(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT model FROM messages WHERE model IS NOT NULL AND model != '' " +
                "AND model != 'null' ORDER BY model LIMIT 30", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        // 「未记录」固定放最后，和统计图的 COALESCE(NULLIF(model, ''), '未记录') 对齐
        if (out.size < 31) {
            val unknown = scalar(
                "SELECT COUNT(*) FROM messages WHERE active = 1 AND " + unknownModelSql("model")
            )
            if (unknown > 0) out.add(MODEL_UNKNOWN)
        }
        return out
    }

    fun yearList(): List<Int> {
        val out = ArrayList<Int>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT CAST(strftime('%Y', create_ts, 'unixepoch', 'localtime') AS INTEGER) y " +
                "FROM conversations WHERE create_ts > 0 ORDER BY y DESC", null
        ).use { c -> while (c.moveToNext()) out.add(c.getInt(0)) }
        return out
    }

    // ---------- 摘录 / 代码 ----------

    fun clips(query: String?, tag: String?): List<ClipItem> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!tag.isNullOrBlank()) {
            where.append(" AND (',' || cl.tags || ',') LIKE ?")
            args.add("%,$tag,%")
        }
        val tokens = query?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.take(6) ?: emptyList()
        if (tokens.isNotEmpty()) {
            where.append(" AND (")
            where.append(tokenWhere(tokens, listOf("cl.text", "cl.note", "cl.tags"), args))
            where.append(")")
        }
        val sql = """
            SELECT cl.id, cl.conv_id, cl.msg_id, cl.text, cl.note, cl.tags, cl.kind, cl.created_ts,
                   COALESCE(c.alias, c.title),
                   CASE WHEN cl.prev_note IS NOT NULL OR cl.prev_tags IS NOT NULL THEN 1 ELSE 0 END
            FROM clips cl LEFT JOIN conversations c ON c.id = cl.conv_id
            WHERE $where ORDER BY cl.created_ts DESC LIMIT 500
        """.trimIndent()
        val out = ArrayList<ClipItem>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ClipItem(
                        id = c.getLong(0),
                        convId = c.getString(1),
                        msgId = c.getString(2),
                        text = c.getString(3) ?: "",
                        note = c.getString(4),
                        tags = c.getString(5) ?: "",
                        kind = c.getString(6) ?: "clip",
                        createdTs = c.getLong(7),
                        convTitle = c.getString(8),
                        canRevert = c.getInt(9) == 1
                    )
                )
            }
        }
        return out
    }

    fun addClip(convId: String?, msgId: String?, text: String, note: String?, tags: String, kind: String): Long {
        val cv = ContentValues().apply {
            put("conv_id", convId)
            put("msg_id", msgId)
            put("text", text)
            put("note", note)
            put("tags", tags)
            put("kind", kind)
            put("created_ts", System.currentTimeMillis() / 1000)
        }
        return writableDatabase.insert("clips", null, cv)
    }

    fun updateClip(id: Long, note: String?, tags: String) {
        val cv = ContentValues().apply {
            put("note", note)
            put("tags", tags)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 只保留备注/标签的一级历史，供「还原上次修改」使用
            db.execSQL(
                "UPDATE clips SET prev_note = note, prev_tags = tags WHERE id = ?",
                arrayOf(id.toString())
            )
            db.update("clips", cv, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun revertClip(id: Long) {
        writableDatabase.execSQL(
            "UPDATE clips SET note = prev_note, tags = prev_tags, " +
                "prev_note = NULL, prev_tags = NULL " +
                "WHERE id = ? AND (prev_note IS NOT NULL OR prev_tags IS NOT NULL)",
            arrayOf(id.toString())
        )
    }

    fun deleteClip(id: Long) {
        writableDatabase.delete("clips", "id = ?", arrayOf(id.toString()))
    }

    fun codeBlocks(lang: String?, query: String?): List<CodeItem> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!lang.isNullOrBlank()) {
            where.append(" AND cb.lang = ?")
            args.add(lang)
        }
        val tokens = query?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.take(6) ?: emptyList()
        if (tokens.isNotEmpty()) {
            where.append(" AND (")
            where.append(tokenWhere(tokens, listOf("cb.code", "cb.lang"), args))
            where.append(")")
        }
        val sql = """
            SELECT cb.id, cb.conv_id, cb.msg_id, cb.lang, cb.code, cb.ts,
                   COALESCE(c.alias, c.title), cb.ord
            FROM code_blocks cb LEFT JOIN conversations c ON c.id = cb.conv_id
            WHERE $where ORDER BY cb.ts DESC, cb.ord DESC, cb.id ASC LIMIT 5000
        """.trimIndent()
        val out = ArrayList<CodeItem>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CodeItem(
                        id = c.getLong(0),
                        convId = c.getString(1),
                        msgId = c.getString(2),
                        lang = c.getString(3) ?: "text",
                        code = c.getString(4) ?: "",
                        ts = c.getLong(5),
                        convTitle = c.getString(6),
                        ord = c.getInt(7)
                    )
                )
            }
        }
        return out
    }

    fun codeLanguages(): List<NamedCount> {
        val out = ArrayList<NamedCount>()
        readableDatabase.rawQuery(
            "SELECT lang, COUNT(*) FROM code_blocks GROUP BY lang ORDER BY 2 DESC LIMIT 24", null
        ).use { c -> while (c.moveToNext()) out.add(NamedCount(c.getString(0), c.getInt(1))) }
        return out
    }

    /** 老库升级后补齐派生数据：代码片段与附件文本索引 */
    fun ensureDerivedData() {
        val db = writableDatabase
        val codeCount = scalar("SELECT COUNT(*) FROM code_blocks")
        if (codeCount == 0L) {
            val rows = ArrayList<Message>()
            readableDatabase.rawQuery(
                "SELECT * FROM messages WHERE active = 1 AND text LIKE '%```%'", null
            ).use { c -> while (c.moveToNext()) rows.add(readMessage(c)) }
            if (rows.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (m in rows) putCodeBlocksForMessage(db, m)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        val textCount = scalar("SELECT COUNT(*) FROM attachment_text")
        if (textCount == 0L) {
            val files = ArrayList<Triple<String, String?, String>>()
            readableDatabase.rawQuery(
                "SELECT id, conv_id, name FROM attachments " +
                    "WHERE kind = 'text' AND exists_flag = 1 AND path IS NOT NULL", null
            ).use { c ->
                while (c.moveToNext()) {
                    files.add(Triple(c.getString(0), c.getString(1), c.getString(2) ?: ""))
                }
            }
            if (files.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for ((id, convId, name) in files) {
                        val path = readableDatabase.rawQuery(
                            "SELECT path FROM attachments WHERE id = ?", arrayOf(id)
                        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        val text = readTextFile(path, 200_000)
                        if (text.isNotBlank()) putAttachmentText(db, id, convId, name, text)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun scalar(sql: String): Long =
        readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun readTextFile(path: String?, limit: Int): String {
        if (path == null) return ""
        return runCatching {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) return ""
            f.bufferedReader().use { r ->
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

    // ---------- 搜索 ----------

    fun search(query: String, limit: Int = 300): List<SearchHit> =
        searchAll(query, setOf("message"), limit)

    fun searchInConv(convId: String, query: String): List<SearchHit> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return emptyList()
        val args = ArrayList<String>()
        val where = tokenWhere(tokens, listOf("m.text", "m.reasoning"), args)
        val sql = """
            SELECT m.id, m.conv_id, COALESCE(c.alias, c.title), m.role, m.ts, m.text, m.reasoning, m.ord
            FROM messages m JOIN conversations c ON c.id = m.conv_id
            WHERE m.conv_id = ? AND m.active = 1 AND ($where)
            ORDER BY m.ord ASC
        """.trimIndent()
        val out = ArrayList<SearchHit>()
        readableDatabase.rawQuery(sql, arrayOf(convId, *args.toTypedArray())).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(5) ?: ""
                val reasoning = c.getString(6) ?: ""
                val base = if (containsAny(text, tokens)) text else reasoning.ifBlank { text }
                out.add(
                    SearchHit(
                        convId = c.getString(1),
                        convTitle = c.getString(2),
                        msgId = c.getString(0),
                        role = c.getString(3),
                        ts = c.getLong(4),
                        snippet = snippet(base, tokens),
                        order = c.getInt(7)
                    )
                )
            }
        }
        return out
    }

    fun searchAll(query: String, types: Set<String>, limit: Int = 300): List<SearchHit> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return emptyList()
        val all = ArrayList<SearchHit>()
        val wanted = if (types.isEmpty()) setOf("title", "message", "code", "clip", "file") else types

        if ("title" in wanted) {
            val args = ArrayList<String>()
            val where = tokenWhere(tokens, listOf("COALESCE(c.alias, c.title)"), args)
            val sql = """
                SELECT c.id, COALESCE(c.alias, c.title), c.create_ts, c.msg_count, c.category
                FROM conversations c WHERE ($where)
                ORDER BY c.create_ts DESC LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    all.add(
                        SearchHit(
                            convId = c.getString(0),
                            convTitle = c.getString(1) ?: "",
                            msgId = "",
                            role = "title",
                            ts = c.getLong(2),
                            snippet = "${c.getInt(3)} 条消息 · ${c.getString(4) ?: ""}",
                            order = 0,
                            type = "title"
                        )
                    )
                }
            }
        }

        if ("message" in wanted) {
            val args = ArrayList<String>()
            val where = tokenWhere(tokens, listOf("m.text", "m.reasoning"), args)
            val sql = """
                SELECT m.id, m.conv_id, COALESCE(c.alias, c.title), m.role, m.ts, m.text, m.reasoning, m.ord
                FROM messages m JOIN conversations c ON c.id = m.conv_id
                WHERE m.active = 1 AND ($where)
                ORDER BY m.ts DESC LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(5) ?: ""
                    val reasoning = c.getString(6) ?: ""
                    val base = if (containsAny(text, tokens)) text else reasoning.ifBlank { text }
                    all.add(
                        SearchHit(
                            convId = c.getString(1),
                            convTitle = c.getString(2) ?: "",
                            msgId = c.getString(0),
                            role = c.getString(3),
                            ts = c.getLong(4),
                            snippet = snippet(base, tokens),
                            order = c.getInt(7),
                            type = "message"
                        )
                    )
                }
            }
        }

        if ("code" in wanted) {
            val args = ArrayList<String>()
            val where = tokenWhere(tokens, listOf("cb.code", "cb.lang"), args)
            val sql = """
                SELECT cb.id, cb.conv_id, COALESCE(c.alias, c.title), cb.msg_id, cb.ts, cb.code, cb.lang, cb.ord
                FROM code_blocks cb LEFT JOIN conversations c ON c.id = cb.conv_id
                WHERE ($where) ORDER BY cb.ts DESC LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    all.add(
                        SearchHit(
                            convId = c.getString(1),
                            convTitle = c.getString(2) ?: "",
                            msgId = c.getString(3),
                            role = "code",
                            ts = c.getLong(4),
                            snippet = snippet(c.getString(5) ?: "", tokens),
                            order = c.getInt(7),
                            type = "code",
                            lang = c.getString(6)
                        )
                    )
                }
            }
        }

        if ("clip" in wanted) {
            val args = ArrayList<String>()
            val where = tokenWhere(tokens, listOf("cl.text", "cl.note", "cl.tags"), args)
            val sql = """
                SELECT cl.id, cl.conv_id, COALESCE(c.alias, c.title), cl.msg_id, cl.created_ts,
                       cl.text, cl.note, cl.tags
                FROM clips cl LEFT JOIN conversations c ON c.id = cl.conv_id
                WHERE ($where) ORDER BY cl.created_ts DESC LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(5) ?: ""
                    val note = c.getString(6) ?: ""
                    val tags = c.getString(7) ?: ""
                    val base = when {
                        containsAny(text, tokens) -> text
                        containsAny(note, tokens) -> note
                        else -> tags.ifBlank { text }
                    }
                    all.add(
                        SearchHit(
                            convId = c.getString(1) ?: "",
                            convTitle = c.getString(2) ?: "",
                            msgId = c.getString(3) ?: "",
                            role = "clip",
                            ts = c.getLong(4),
                            snippet = snippet(base, tokens),
                            order = 0,
                            type = "clip",
                            clipId = c.getLong(0)
                        )
                    )
                }
            }
        }

        if ("file" in wanted) {
            val args = ArrayList<String>()
            val where = tokenWhere(tokens, listOf("at.text", "a.name"), args)
            val sql = """
                SELECT a.id, a.conv_id, COALESCE(c.alias, c.title), a.msg_id, COALESCE(m.ts, 0),
                       at.text, a.name, a.mime, a.size, a.path, a.kind, a.exists_flag
                FROM attachment_text at
                JOIN attachments a ON a.id = at.att_id
                LEFT JOIN messages m ON m.id = a.msg_id
                LEFT JOIN conversations c ON c.id = a.conv_id
                WHERE ($where) ORDER BY COALESCE(m.ts, 0) DESC LIMIT 200
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val att = Attachment(
                        id = c.getString(0),
                        convId = c.getString(1),
                        msgId = c.getString(3),
                        name = c.getString(6) ?: "",
                        mime = c.getString(7) ?: "",
                        size = c.getLong(8),
                        path = c.getString(9),
                        kind = c.getString(10) ?: "other",
                        exists = c.getInt(11) == 1
                    )
                    all.add(
                        SearchHit(
                            convId = c.getString(1) ?: "",
                            convTitle = c.getString(2) ?: "",
                            msgId = c.getString(3) ?: "",
                            role = "file",
                            ts = c.getLong(4),
                            snippet = snippet(c.getString(5) ?: "", tokens),
                            order = 0,
                            type = "file",
                            attachment = att
                        )
                    )
                }
            }
        }

        return all.sortedByDescending { it.ts }.take(limit)
    }

    private fun tokenWhere(tokens: List<String>, columns: List<String>, args: MutableList<String>): String =
        tokens.joinToString(" AND ") {
            val pattern = "%${escapeLike(it)}%"
            "(" + columns.joinToString(" OR ") { col ->
                args.add(pattern)
                "$col LIKE ? ESCAPE '\\'"
            } + ")"
        }

    private fun containsAny(text: String, tokens: List<String>): Boolean =
        tokens.any { text.contains(it, ignoreCase = true) }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun snippet(text: String, tokens: List<String>, radius: Int = 48): String {
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ")
        var idx = -1
        for (t in tokens) {
            val i = flat.indexOf(t, ignoreCase = true)
            if (i >= 0 && (idx < 0 || i < idx)) idx = i
        }
        if (idx < 0) return flat.take(radius * 2)
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + radius).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }

    // ---------- 统计 ----------

    fun stats(
        year: Int?,
        category: String? = null,
        model: String? = null,
        fromTs: Long? = null,
        toTs: Long? = null
    ): StatsBundle {
        val db = readableDatabase

        // 会话维度：年份/主题/模型/创建时间范围
        val convWhere = StringBuilder("1=1")
        val convArgs = ArrayList<String>()
        if (year != null) {
            convWhere.append(" AND strftime('%Y', c.create_ts, 'unixepoch', 'localtime') = ?")
            convArgs.add(year.toString())
        }
        if (!category.isNullOrBlank()) {
            convWhere.append(" AND c.category = ?")
            convArgs.add(category)
        }
        if (!model.isNullOrBlank()) {
            if (model == MODEL_UNKNOWN) {
                convWhere.append(
                    " AND EXISTS (SELECT 1 FROM messages mm WHERE mm.conv_id = c.id AND mm.active = 1 " +
                        "AND " + unknownModelSql("mm.model") + ")"
                )
            } else {
                convWhere.append(
                    " AND EXISTS (SELECT 1 FROM messages mm WHERE mm.conv_id = c.id AND mm.active = 1 AND mm.model = ?)"
                )
                convArgs.add(model)
            }
        }
        if (fromTs != null) {
            convWhere.append(" AND c.create_ts >= ?")
            convArgs.add(fromTs.toString())
        }
        if (toTs != null) {
            convWhere.append(" AND c.create_ts <= ?")
            convArgs.add(toTs.toString())
        }

        // 消息维度：主题在会话上过滤；年份/时间范围按消息时间；模型按消息模型
        val msgConvWhere = StringBuilder("1=1")
        val msgArgs = ArrayList<String>()
        if (!category.isNullOrBlank()) {
            msgConvWhere.append(" AND c.category = ?")
            msgArgs.add(category)
        }
        if (year != null) {
            msgConvWhere.append(" AND strftime('%Y', c.create_ts, 'unixepoch', 'localtime') = ?")
            msgArgs.add(year.toString())
        }
        if (fromTs != null) {
            msgConvWhere.append(" AND c.create_ts >= ?")
            msgArgs.add(fromTs.toString())
        }
        if (toTs != null) {
            msgConvWhere.append(" AND c.create_ts <= ?")
            msgArgs.add(toTs.toString())
        }
        val msgWhere = StringBuilder(
            "m.active = 1 AND m.conv_id IN (SELECT c.id FROM conversations c WHERE $msgConvWhere)"
        )
        if (year != null) {
            msgWhere.append(" AND strftime('%Y', m.ts, 'unixepoch', 'localtime') = ?")
            msgArgs.add(year.toString())
        }
        if (fromTs != null) {
            msgWhere.append(" AND m.ts >= ?")
            msgArgs.add(fromTs.toString())
        }
        if (toTs != null) {
            msgWhere.append(" AND m.ts <= ?")
            msgArgs.add(toTs.toString())
        }
        if (!model.isNullOrBlank()) {
            if (model == MODEL_UNKNOWN) {
                msgWhere.append(" AND " + unknownModelSql("m.model"))
            } else {
                msgWhere.append(" AND m.model = ?")
                msgArgs.add(model)
            }
        }

        val monthly = LinkedHashMap<String, Triple<Int, Int, Int>>()
        db.rawQuery(
            "SELECT strftime('%Y-%m', c.create_ts, 'unixepoch', 'localtime') k, COUNT(*), " +
                "SUM(c.msg_count), SUM(c.char_count) FROM conversations c " +
                "WHERE $convWhere GROUP BY k ORDER BY k", convArgs.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) monthly[c.getString(0) ?: "-"] = Triple(c.getInt(1), c.getInt(2), c.getInt(3))
        }
        db.rawQuery(
            "SELECT strftime('%Y-%m', m.ts, 'unixepoch', 'localtime') k, COUNT(*) " +
                "FROM messages m WHERE $msgWhere GROUP BY k ORDER BY k", msgArgs.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: "-"
                val prev = monthly[k]
                monthly[k] = if (prev == null) Triple(0, c.getInt(1), 0)
                else Triple(prev.first, c.getInt(1), prev.third)
            }
        }

        val categories = ArrayList<NamedCount>()
        db.rawQuery(
            "SELECT c.category, COUNT(*) FROM conversations c WHERE $convWhere " +
                "GROUP BY c.category ORDER BY 2 DESC", convArgs.toTypedArray()
        ).use { c -> while (c.moveToNext()) categories.add(NamedCount(c.getString(0), c.getInt(1))) }

        val hourMap = HashMap<Int, Int>()
        db.rawQuery(
            "SELECT CAST(strftime('%H', m.ts, 'unixepoch', 'localtime') AS INTEGER) h, COUNT(*) " +
                "FROM messages m WHERE $msgWhere GROUP BY h", msgArgs.toTypedArray()
        ).use { c -> while (c.moveToNext()) hourMap[c.getInt(0)] = c.getInt(1) }
        val hours = (0..23).map { NamedCount(it.toString(), hourMap[it] ?: 0) }

        val models = ArrayList<NamedCount>()
        db.rawQuery(
            "SELECT COALESCE(NULLIF(NULLIF(m.model, ''), 'null'), '未记录') m, COUNT(*) FROM messages m " +
                "WHERE $msgWhere AND m.role = 'assistant' GROUP BY m ORDER BY 2 DESC LIMIT 10",
            msgArgs.toTypedArray()
        ).use { c -> while (c.moveToNext()) models.add(NamedCount(c.getString(0), c.getInt(1))) }

        val years = ArrayList<Int>()
        db.rawQuery(
            "SELECT DISTINCT CAST(strftime('%Y', create_ts, 'unixepoch', 'localtime') AS INTEGER) y " +
                "FROM conversations WHERE create_ts > 0 ORDER BY y DESC", null
        ).use { c -> while (c.moveToNext()) years.add(c.getInt(0)) }

        var totalConvs = 0
        var totalMsgs = 0
        var totalChars = 0
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(c.msg_count),0), COALESCE(SUM(c.char_count),0) " +
                "FROM conversations c WHERE $convWhere", convArgs.toTypedArray()
        ).use { c ->
            if (c.moveToFirst()) {
                totalConvs = c.getInt(0)
                totalMsgs = c.getInt(1)
                totalChars = c.getInt(2)
            }
        }

        var atts = 0
        var missing = 0
        db.rawQuery(
            "SELECT SUM(CASE WHEN exists_flag = 1 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN exists_flag = 0 THEN 1 ELSE 0 END) FROM attachments", null
        ).use { c -> if (c.moveToFirst()) { atts = c.getInt(0); missing = c.getInt(1) } }

        val monthlyList = monthly.entries.map { (k, v) -> MonthlyStat(k, v.first, v.second, v.third) }
        return StatsBundle(
            monthly = monthlyList,
            categories = categories,
            hours = hours,
            models = models,
            years = years,
            totalConvs = totalConvs,
            totalMsgs = totalMsgs,
            totalChars = totalChars,
            totalAttachments = atts,
            missingAttachments = missing
        )
    }

    // ---------- 备份辅助 ----------

    /** 恢复备份后，绝对路径可能变化，按 blobs/<id>.<ext> 重新绑定 */
    fun repairAttachmentPaths() {
        runCatching {
            val blobs = File(appContext.filesDir, "blobs")
            if (!blobs.exists()) return
            val rows = ArrayList<Pair<String, String?>>()
            readableDatabase.rawQuery("SELECT id, path FROM attachments", null).use { c ->
                while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
            }
            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((id, path) in rows) {
                    if (path != null && File(path).exists()) continue
                    val f = blobs.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$id.") } ?: continue
                    val cv = ContentValues().apply {
                        put("path", f.absolutePath)
                        put("exists_flag", 1)
                        put("size", f.length())
                    }
                    db.update("attachments", cv, "id = ?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    companion object {
        const val DB_VERSION = 3

        /** 消息没有记录模型时，筛选面板里的显示名 */
        const val MODEL_UNKNOWN = "未记录"
    }
}
