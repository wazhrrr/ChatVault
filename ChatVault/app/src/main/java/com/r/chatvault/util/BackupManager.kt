package com.r.chatvault.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.r.chatvault.BuildConfig
import com.r.chatvault.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 = chatvault.db + files/blobs 下的全部附件，打成一个 zip。
 * 恢复前校验完整性，替换失败会自动回滚到原数据。
 */
class BackupManager(private val ctx: Context, private val store: Store) {

    data class Progress(val phase: String, val done: Int, val total: Int)

    data class RestoreResult(val conversations: Int, val messages: Int, val attachments: Int)

    suspend fun backup(uri: Uri, onProgress: (Progress) -> Unit): Long = withContext(Dispatchers.IO) {
        val dbFile = ctx.getDatabasePath("chatvault.db")
        if (!dbFile.exists() || dbFile.length() == 0L) {
            throw IllegalStateException("本地数据库还不存在，先导入数据再备份")
        }
        runCatching {
            store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        val blobs = File(ctx.filesDir, "blobs")
        val files = blobs.listFiles()?.filter { it.isFile } ?: emptyList()
        val out = ctx.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入备份文件")
        var written = 0L
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            val meta = JSONObject().apply {
                put("app", "ChatVault")
                put("version", BuildConfig.VERSION_NAME)
                put("dbVersion", Store.DB_VERSION)
                put("createdAt", System.currentTimeMillis() / 1000)
                put("dbBytes", dbFile.length())
                put("blobCount", files.size)
            }
            zos.putNextEntry(ZipEntry("meta.json"))
            zos.write(meta.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            onProgress(Progress("备份数据库", 0, files.size + 1))
            zos.putNextEntry(ZipEntry("chatvault.db"))
            dbFile.inputStream().use { input -> written += input.copyTo(zos) }
            zos.closeEntry()

            files.forEachIndexed { index, f ->
                onProgress(Progress("备份附件", index + 1, files.size + 1))
                zos.putNextEntry(ZipEntry("blobs/${f.name}"))
                f.inputStream().use { input -> written += input.copyTo(zos) }
                zos.closeEntry()
            }
        }
        written
    }

    suspend fun restore(uri: Uri, onProgress: (Progress) -> Unit): RestoreResult = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            onProgress(Progress("解压备份", 0, 0))
            unzip(uri, tmp)

            val newDb = File(tmp, "chatvault.db")
            if (!newDb.exists() || newDb.length() == 0L) {
                throw IllegalStateException("这个 zip 里没有 chatvault.db，可能不是 ChatVault 备份")
            }
            val meta = File(tmp, "meta.json").takeIf { it.exists() }?.let {
                runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull()
            }
            val backupVersion = meta?.optInt("dbVersion", 1) ?: 1
            if (backupVersion > Store.DB_VERSION) {
                throw IllegalStateException("备份来自更高版本（DB v$backupVersion），请先升级应用")
            }
            validateDb(newDb)

            val newBlobs = File(tmp, "blobs")
            val dbFile = ctx.getDatabasePath("chatvault.db")
            val dbDir = dbFile.parentFile ?: throw IllegalStateException("找不到数据库目录")
            dbDir.mkdirs()
            val dbBak = File(dbDir, "chatvault.db.bak")
            val blobsDir = File(ctx.filesDir, "blobs")
            val blobsBak = File(ctx.filesDir, "blobs.bak")

            store.close()
            clearSideFiles(dbFile)
            dbBak.delete()
            blobsBak.deleteRecursively()

            onProgress(Progress("替换数据", 0, 2))
            if (dbFile.exists() && !dbFile.renameTo(dbBak)) {
                dbFile.copyTo(dbBak, overwrite = true)
                dbFile.delete()
            }
            if (blobsDir.exists() && !blobsDir.renameTo(blobsBak)) {
                blobsDir.copyRecursively(blobsBak, overwrite = true)
                blobsDir.deleteRecursively()
            }

            var ok = false
            try {
                newDb.copyTo(dbFile, overwrite = true)
                if (newBlobs.exists()) {
                    newBlobs.copyRecursively(blobsDir, overwrite = true)
                } else {
                    blobsDir.mkdirs()
                }
                validateDb(dbFile)
                onProgress(Progress("修复附件路径", 1, 2))
                store.repairAttachmentPaths()
                ok = true
            } finally {
                if (ok) {
                    dbBak.delete()
                    blobsBak.deleteRecursively()
                } else {
                    // 回滚前先关掉可能已被 repairAttachmentPaths 打开的连接
                    runCatching { store.close() }
                    dbFile.delete()
                    clearSideFiles(dbFile)
                    if (dbBak.exists()) dbBak.renameTo(dbFile)
                    blobsDir.deleteRecursively()
                    if (blobsBak.exists()) blobsBak.renameTo(blobsDir)
                }
            }
            if (!ok) throw IllegalStateException("恢复失败，已回滚到原数据")

            val counts = store.counts()
            RestoreResult(counts.first, counts.second, counts.third)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun unzip(uri: Uri, dir: File) {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开备份文件")
        val base = dir.canonicalFile
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            val buffer = ByteArray(64 * 1024)
            while (entry != null && count < 4000) {
                if (!entry.isDirectory) {
                    val raw = entry.name.replace('\\', '/').trimStart('/')
                    val target = File(dir, raw).canonicalFile
                    if (target.path.startsWith(base.path + File.separator)) {
                        target.parentFile?.mkdirs()
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

    private fun validateDb(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (db.version > Store.DB_VERSION) {
                throw IllegalStateException("备份数据库版本过高（v${db.version}）")
            }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) {
                    val result = c.getString(0) ?: ""
                    if (!result.equals("ok", ignoreCase = true)) {
                        throw IllegalStateException("备份数据库校验失败：$result")
                    }
                }
            }
        }
    }

    private fun clearSideFiles(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }
}
