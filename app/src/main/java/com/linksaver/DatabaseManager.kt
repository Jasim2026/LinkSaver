package com.linksaver

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseManager {
    private var database: SQLiteDatabase? = null

    fun initDb(dirPath: String): Boolean {
        try {
            if (database != null && database!!.isOpen) {
                return true
            }

            val dir = File(dirPath)
            if (!dir.exists()) dir.mkdirs()
            val dbFile = File(dir, "linksaver.db")
            database = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            database?.disableWriteAheadLogging()

            createTables()
            upgradeTables()
            setupFts()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun createTables() {
        database?.apply {
            execSQL("""
                CREATE TABLE IF NOT EXISTS youtube_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, datetime TEXT, url TEXT,
                    channel_name TEXT, upload_date TEXT, views TEXT, subtitle TEXT,
                    file_reference_id TEXT, tag TEXT, english_translation TEXT
                )
            """.trimIndent())

            execSQL("""
                CREATE TABLE IF NOT EXISTS web_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, datetime TEXT, url TEXT,
                    site_name TEXT, data TEXT, file_reference_id TEXT, tag TEXT, english_translation TEXT
                )
            """.trimIndent())

            execSQL("""
                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, datetime TEXT, data BLOB
                )
            """.trimIndent())

            execSQL("""
                CREATE TABLE IF NOT EXISTS app_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, datetime TEXT, status TEXT, message TEXT
                )
            """.trimIndent())

            execSQL("""
                CREATE TABLE IF NOT EXISTS script_schedules (
                    script_name TEXT PRIMARY KEY,
                    times_json TEXT,
                    run_as_server INTEGER,
                    trigger_script TEXT
                )
            """.trimIndent())
        }
    }

    private fun upgradeTables() {
        val db = database ?: return
        val tables = listOf("youtube_links", "web_links")
        tables.forEach { table ->
            val cursor = db.rawQuery("PRAGMA table_info($table)", null)
            var hasTag = false
            var hasTrans = false
            while (cursor.moveToNext()) {
                val colName = cursor.getString(1)
                if (colName == "tag") hasTag = true
                if (colName == "english_translation") hasTrans = true
            }
            cursor.close()

            if (!hasTag) db.execSQL("ALTER TABLE $table ADD COLUMN tag TEXT DEFAULT ''")
            if (!hasTrans) db.execSQL("ALTER TABLE $table ADD COLUMN english_translation TEXT DEFAULT ''")
        }
    }

    private fun setupFts() {
        val db = database ?: return
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS links_fts USING fts5(
                url, tag, type, row_id UNINDEXED
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS youtube_links_after_insert AFTER INSERT ON youtube_links
            BEGIN
                INSERT INTO links_fts(row_id, url, tag, type) VALUES (new.id, new.url, new.tag, 'youtube');
            END;
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS youtube_links_after_delete AFTER DELETE ON youtube_links
            BEGIN
                DELETE FROM links_fts WHERE row_id = old.id AND type = 'youtube';
            END;
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS youtube_links_after_update AFTER UPDATE ON youtube_links
            BEGIN
                DELETE FROM links_fts WHERE row_id = old.id AND type = 'youtube';
                INSERT INTO links_fts(row_id, url, tag, type) VALUES (new.id, new.url, new.tag, 'youtube');
            END;
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS web_links_after_insert AFTER INSERT ON web_links
            BEGIN
                INSERT INTO links_fts(row_id, url, tag, type) VALUES (new.id, new.url, new.tag, 'web');
            END;
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS web_links_after_delete AFTER DELETE ON web_links
            BEGIN
                DELETE FROM links_fts WHERE row_id = old.id AND type = 'web';
            END;
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS web_links_after_update AFTER UPDATE ON web_links
            BEGIN
                DELETE FROM links_fts WHERE row_id = old.id AND type = 'web';
                INSERT INTO links_fts(row_id, url, tag, type) VALUES (new.id, new.url, new.tag, 'web');
            END;
        """.trimIndent())
    }

    fun saveLink(url: String, tag: String, isYoutube: Boolean) {
        val db = database ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val table = if (isYoutube) "youtube_links" else "web_links"

        val cursor = db.rawQuery("SELECT id FROM $table WHERE url = ?", arrayOf(url))
        if (cursor.moveToFirst()) {
            val id = cursor.getInt(0)
            db.execSQL("UPDATE $table SET datetime = ?, tag = ? WHERE id = ?", arrayOf(time, tag, id.toLong()))
        } else {
            if (isYoutube) {
                db.execSQL(
                    "INSERT INTO youtube_links (datetime, url, channel_name, upload_date, views, subtitle, file_reference_id, tag, english_translation) VALUES (?, ?, '', '', '', '', '', ?, '')",
                    arrayOf(time, url, tag)
                )
            } else {
                db.execSQL(
                    "INSERT INTO web_links (datetime, url, site_name, data, file_reference_id, tag, english_translation) VALUES (?, ?, '', '', '', ?, '')",
                    arrayOf(time, url, tag)
                )
            }
        }
        cursor.close()
    }

    fun saveSchedule(scriptName: String, timesJson: String, runAsServer: Boolean, triggerScript: String) {
        val db = database ?: return
        db.execSQL(
            "INSERT OR REPLACE INTO script_schedules (script_name, times_json, run_as_server, trigger_script) VALUES (?, ?, ?, ?)",
            arrayOf(scriptName, timesJson, if (runAsServer) 1 else 0, triggerScript)
        )
    }

    fun getSchedule(scriptName: String): ScheduleConfig? {
        database?.rawQuery("SELECT times_json, run_as_server, trigger_script FROM script_schedules WHERE script_name = ?", arrayOf(scriptName))?.use {
            if (it.moveToFirst()) {
                return ScheduleConfig(scriptName, it.getString(0), it.getInt(1) == 1, it.getString(2))
            }
        }
        return null
    }

    fun getAllSchedules(): List<ScheduleConfig> {
        val list = mutableListOf<ScheduleConfig>()
        database?.rawQuery("SELECT script_name, times_json, run_as_server, trigger_script FROM script_schedules", null)?.use {
            while (it.moveToNext()) {
                list.add(ScheduleConfig(it.getString(0), it.getString(1), it.getInt(2) == 1, it.getString(3)))
            }
        }
        return list
    }

    fun addLog(status: String, message: String) {
        val db = database ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val stmt = db.compileStatement("INSERT INTO app_logs (datetime, status, message) VALUES (?, ?, ?)")
        stmt.bindString(1, time)
        stmt.bindString(2, status)
        stmt.bindString(3, message)
        stmt.executeInsert()
    }

    fun getLogs(status: String): List<AppLog> {
        val logs = mutableListOf<AppLog>()
        val cursor = database?.rawQuery("SELECT id, datetime, status, message FROM app_logs WHERE status = ? ORDER BY id DESC", arrayOf(status))
        cursor?.use {
            while (it.moveToNext()) {
                logs.add(AppLog(it.getInt(0), it.getString(1), it.getString(2), it.getString(3)))
            }
        }
        return logs
    }

    fun clearLogs(status: String) {
        database?.execSQL("DELETE FROM app_logs WHERE status = ?", arrayOf(status))
    }

    fun getLogStats(): Pair<Int, Int> {
        var success = 0
        var fail = 0
        database?.rawQuery("SELECT status, COUNT(*) FROM app_logs GROUP BY status", null)?.use {
            while (it.moveToNext()) {
                if (it.getString(0) == "SUCCESS") success = it.getInt(1)
                if (it.getString(0) == "FAILURE") fail = it.getInt(1)
            }
        }
        return Pair(success, fail)
    }

    fun searchLinks(query: String): List<LinkItem> {
        val list = mutableListOf<LinkItem>()
        val db = database ?: return list
        
        val sql = if (query.isBlank()) {
            """
            SELECT id, 'youtube' as type, url, tag, datetime FROM youtube_links
            UNION ALL
            SELECT id, 'web' as type, url, tag, datetime FROM web_links
            ORDER BY datetime DESC LIMIT 100
            """
        } else {
            """
            SELECT row_id as id, type, url, tag, (SELECT datetime FROM youtube_links WHERE id = row_id) as datetime 
            FROM links_fts WHERE links_fts MATCH ? AND type = 'youtube'
            UNION ALL
            SELECT row_id as id, type, url, tag, (SELECT datetime FROM web_links WHERE id = row_id) as datetime 
            FROM links_fts WHERE links_fts MATCH ? AND type = 'web'
            """
        }

        val args = if (query.isBlank()) null else arrayOf("$query*", "$query*")
        db.rawQuery(sql, args).use {
            while (it.moveToNext()) {
                list.add(LinkItem(it.getInt(0), it.getString(1) == "youtube", it.getString(2), it.getString(3), it.getString(4) ?: ""))
            }
        }
        return list
    }

    fun getFullLinkDetails(id: Int, isYoutube: Boolean): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val table = if (isYoutube) "youtube_links" else "web_links"
        database?.rawQuery("SELECT * FROM $table WHERE id = ?", arrayOf(id.toString()))?.use { cursor ->
            if (cursor.moveToFirst()) {
                for (i in 0 until cursor.columnCount) {
                    map[cursor.getColumnName(i)] = cursor.getString(i) ?: ""
                }
            }
        }
        return map
    }

    fun updateLink(id: Int, isYoutube: Boolean, newTag: String, newTranslation: String) {
        val table = if (isYoutube) "youtube_links" else "web_links"
        database?.execSQL("UPDATE $table SET tag = ?, english_translation = ? WHERE id = ?", arrayOf(newTag, newTranslation, id.toLong()))
    }

    fun deleteLink(id: Int, isYoutube: Boolean) {
        val table = if (isYoutube) "youtube_links" else "web_links"
        database?.execSQL("DELETE FROM $table WHERE id = ?", arrayOf(id.toLong()))
    }

    fun exportData(dirPath: String, format: String): String {
        val db = database ?: return "Database not initialized"
        val exportDir = File(dirPath, "exports").apply { mkdirs() }
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(exportDir, "linksaver_export_$time.$format")

        try {
            if (format == "json") {
                val root = JSONObject()
                root.put("youtube_links", getTableAsJson("youtube_links"))
                root.put("web_links", getTableAsJson("web_links"))
                FileWriter(file).use { it.write(root.toString(4)) }
            } else {
                FileWriter(file).use { writer ->
                    writer.append("--- YouTube Links ---\n")
                    writer.append(getTableAsCsv("youtube_links"))
                    writer.append("\n--- Web Links ---\n")
                    writer.append(getTableAsCsv("web_links"))
                }
            }
            return "Exported successfully to: ${file.absolutePath}"
        } catch (e: Exception) {
            return "Export failed: ${e.message}"
        }
    }

    private fun getTableAsJson(table: String): JSONArray {
        val array = JSONArray()
        database?.rawQuery("SELECT * FROM $table", null)?.use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                for (i in columns.indices) obj.put(columns[i], cursor.getString(i) ?: "")
                array.put(obj)
            }
        }
        return array
    }

    private fun getTableAsCsv(table: String): String {
        val sb = StringBuilder()
        database?.rawQuery("SELECT * FROM $table", null)?.use { cursor ->
            val columns = cursor.columnNames
            sb.append(columns.joinToString(",")).append("\n")
            while (cursor.moveToNext()) {
                val row = (columns.indices).map { i ->
                    val value = cursor.getString(i) ?: ""
                    "\"${value.replace("\"", "\"\"")}\""
                }
                sb.append(row.joinToString(",")).append("\n")
            }
        }
        return sb.toString()
    }

    fun getYoutubeLinksCount(): Int = getCount("youtube_links")
    fun getWebLinksCount(): Int = getCount("web_links")

    private fun getCount(table: String): Int {
        database?.rawQuery("SELECT COUNT(*) FROM $table", null)?.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }
}

data class AppLog(val id: Int, val datetime: String, val status: String, val message: String)
data class LinkItem(val id: Int, val isYoutube: Boolean, val url: String, val tag: String, val datetime: String)
data class ScheduleConfig(val scriptName: String, val timesJson: String, val runAsServer: Boolean, val triggerScript: String)