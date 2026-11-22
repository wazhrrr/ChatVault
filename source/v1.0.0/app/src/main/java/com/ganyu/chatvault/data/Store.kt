package com.ganyu.chatvault.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class SortMode { NEWEST, OLDEST, MESSAGES, CHARS, TITLE }

class Store(context: Context) : SQLiteOpenHelper(context, "chatvault.db", null, 1) {

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
                attachment_count INTEGER NOT NULL DEFAULT 0
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
                model TEXT
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS attachments")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        onCreate(db)
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
        }
        db.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearConvContent(db: SQLiteDatabase, convId: String) {
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

    fun setStar(id: String, starred: Boolean) {
        writableDatabase.execSQL(
            "UPDATE conversations SET starred = ? WHERE id = ?",
            arrayOf(if (starred) 1 else 0, id)
        )
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("attachments", null, null)
            db.delete("conversations", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 查询 ----------

    fun listConversations(starredOnly: Boolean, year: Int?, sort: SortMode, category: String? = null): List<Conversation> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (starredOnly) where.append(" AND starred = 1")
        if (year != null) {
            where.append(" AND strftime('%Y', create_ts, 'unixepoch', 'localtime') = ?")
            args.add(year.toString())
        }
        if (category != null) {
            where.append(" AND category = ?")
            args.add(category)
        }
        val order = when (sort) {
            SortMode.NEWEST -> "create_ts DESC"
            SortMode.OLDEST -> "create_ts ASC"
            SortMode.MESSAGES -> "msg_count DESC"
            SortMode.CHARS -> "char_count DESC"
            SortMode.TITLE -> "title COLLATE NOCASE ASC"
        }
        val sql = "SELECT * FROM conversations WHERE $where ORDER BY $order"
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(readConv(c))
        }
        return out
    }

    fun conversation(id: String): Conversation? {
        readableDatabase.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) readConv(c) else null
        }
    }

    private fun readConv(c: android.database.Cursor) = Conversation(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        createTs = c.getLong(c.getColumnIndexOrThrow("create_ts")),
        updateTs = c.getLong(c.getColumnIndexOrThrow("update_ts")),
        msgCount = c.getInt(c.getColumnIndexOrThrow("msg_count")),
        charCount = c.getInt(c.getColumnIndexOrThrow("char_count")),
        model = c.getString(c.getColumnIndexOrThrow("model")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
        attachmentCount = c.getInt(c.getColumnIndexOrThrow("attachment_count"))
    )

    fun messages(convId: String): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conv_id = ? ORDER BY ord ASC", arrayOf(convId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Message(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        convId = c.getString(c.getColumnIndexOrThrow("conv_id")),
                        role = c.getString(c.getColumnIndexOrThrow("role")),
                        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
                        reasoning = c.getString(c.getColumnIndexOrThrow("reasoning")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                        order = c.getInt(c.getColumnIndexOrThrow("ord")),
                        contentType = c.getString(c.getColumnIndexOrThrow("content_type")) ?: "text",
                        model = c.getString(c.getColumnIndexOrThrow("model"))
                    )
                )
            }
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

    private fun readAttachment(c: android.database.Cursor) = Attachment(
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
        db.rawQuery("SELECT COUNT(*) FROM messages", null).use { if (it.moveToFirst()) msgs = it.getInt(0) }
        db.rawQuery("SELECT COUNT(*) FROM attachments WHERE exists_flag = 1", null).use { if (it.moveToFirst()) atts = it.getInt(0) }
        return Triple(convs, msgs, atts)
    }

    // ---------- 搜索 ----------

    fun search(query: String, limit: Int = 300): List<SearchHit> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return emptyList()
        val likeClause = tokens.joinToString(" AND ") { "m.text LIKE ? ESCAPE '\\'" }
        val args = tokens.map { "%${escapeLike(it)}%" }.toTypedArray()
        val sql = """
            SELECT m.id, m.conv_id, c.title, m.role, m.ts, m.text, m.ord
            FROM messages m JOIN conversations c ON c.id = m.conv_id
            WHERE $likeClause
            ORDER BY m.ts DESC LIMIT $limit
        """.trimIndent()
        return runSearch(sql, args, tokens)
    }

    fun searchInConv(convId: String, query: String): List<SearchHit> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return emptyList()
        val likeClause = tokens.joinToString(" AND ") { "m.text LIKE ? ESCAPE '\\'" }
        val args = (listOf(convId) + tokens.map { "%${escapeLike(it)}%"}).toTypedArray()
        val sql = """
            SELECT m.id, m.conv_id, c.title, m.role, m.ts, m.text, m.ord
            FROM messages m JOIN conversations c ON c.id = m.conv_id
            WHERE m.conv_id = ? AND $likeClause
            ORDER BY m.ord ASC
        """.trimIndent()
        return runSearch(sql, args, tokens)
    }

    private fun runSearch(sql: String, args: Array<String>, tokens: List<String>): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(5) ?: ""
                out.add(
                    SearchHit(
                        convId = c.getString(1),
                        convTitle = c.getString(2),
                        msgId = c.getString(0),
                        role = c.getString(3),
                        ts = c.getLong(4),
                        snippet = snippet(text, tokens),
                        order = c.getInt(6)
                    )
                )
            }
        }
        return out
    }

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

    fun stats(year: Int?): StatsBundle {
        val db = readableDatabase
        val y = year?.toString()
        val convWhere = if (y != null) "WHERE strftime('%Y', create_ts, 'unixepoch', 'localtime') = '$y'" else ""
        val msgWhere = if (y != null) "WHERE strftime('%Y', ts, 'unixepoch', 'localtime') = '$y'" else ""

        val monthly = LinkedHashMap<String, Triple<Int, Int, Int>>()
        db.rawQuery(
            "SELECT strftime('%Y-%m', create_ts, 'unixepoch', 'localtime') k, COUNT(*), SUM(msg_count), SUM(char_count) " +
                "FROM conversations $convWhere GROUP BY k ORDER BY k", null
        ).use { c ->
            while (c.moveToNext()) monthly[c.getString(0) ?: "-"] = Triple(c.getInt(1), c.getInt(2), c.getInt(3))
        }
        db.rawQuery(
            "SELECT strftime('%Y-%m', ts, 'unixepoch', 'localtime') k, COUNT(*) FROM messages $msgWhere GROUP BY k ORDER BY k",
            null
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
            "SELECT category, COUNT(*) FROM conversations $convWhere GROUP BY category ORDER BY 2 DESC", null
        ).use { c -> while (c.moveToNext()) categories.add(NamedCount(c.getString(0), c.getInt(1))) }

        val hourMap = HashMap<Int, Int>()
        db.rawQuery(
            "SELECT CAST(strftime('%H', ts, 'unixepoch', 'localtime') AS INTEGER) h, COUNT(*) FROM messages $msgWhere GROUP BY h",
            null
        ).use { c -> while (c.moveToNext()) hourMap[c.getInt(0)] = c.getInt(1) }
        val hours = (0..23).map { NamedCount(it.toString(), hourMap[it] ?: 0) }

        val models = ArrayList<NamedCount>()
        val modelWhere = if (y != null) "WHERE role = 'assistant' AND strftime('%Y', ts, 'unixepoch', 'localtime') = '$y'"
        else "WHERE role = 'assistant'"
        db.rawQuery(
            "SELECT COALESCE(NULLIF(model, ''), '未记录') m, COUNT(*) FROM messages $modelWhere GROUP BY m ORDER BY 2 DESC LIMIT 10",
            null
        ).use { c -> while (c.moveToNext()) models.add(NamedCount(c.getString(0), c.getInt(1))) }

        val years = ArrayList<Int>()
        db.rawQuery(
            "SELECT DISTINCT CAST(strftime('%Y', create_ts, 'unixepoch', 'localtime') AS INTEGER) y " +
                "FROM conversations WHERE create_ts > 0 ORDER BY y DESC", null
        ).use { c -> while (c.moveToNext()) years.add(c.getInt(0)) }

        var totalConvs = 0
        var totalMsgs = 0
        var totalChars = 0
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(msg_count),0), COALESCE(SUM(char_count),0) FROM conversations $convWhere", null)
            .use { c -> if (c.moveToFirst()) { totalConvs = c.getInt(0); totalMsgs = c.getInt(1); totalChars = c.getInt(2) } }

        var atts = 0
        var missing = 0
        db.rawQuery("SELECT SUM(CASE WHEN exists_flag = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN exists_flag = 0 THEN 1 ELSE 0 END) FROM attachments", null)
            .use { c -> if (c.moveToFirst()) { atts = c.getInt(0); missing = c.getInt(1) } }

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
}
