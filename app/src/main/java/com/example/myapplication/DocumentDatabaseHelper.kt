package com.example.myapplication

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * document.sqlite 数据库
 * 负责创建 8 张表 + FTS4 虚拟表
 */
class DocumentDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "document.sqlite"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: DocumentDatabaseHelper? = null

        fun getInstance(context: Context): DocumentDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocumentDatabaseHelper(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        // ---------- 各表建表 SQL ----------

        // t_index：索引信息
        private const val SQL_CREATE_T_INDEX = """
            CREATE TABLE IF NOT EXISTS t_index (
                path TEXT PRIMARY KEY,                -- 创建的索引文件夹（唯一，可以是 treeUri 字符串）
                all_file_count INTEGER,              -- 总文件数
                success_file_count INTEGER,          -- 成功文件数
                error_file_count INTEGER,            -- 失败文件数
                index_size INTEGER,                  -- 索引大小（成功文件大小之和）
                create_time INTEGER,                 -- 创建索引时间（毫秒时间戳）
                update_time INTEGER,                 -- 更新索引时间
                status INTEGER                       -- 索引状态（0=进行中, 1=完成）
            );
        """

        // t_config：配置信息
        private const val SQL_CREATE_T_CONFIG = """
            CREATE TABLE IF NOT EXISTS t_config (
                id INTEGER PRIMARY KEY NOT NULL,     -- 序号
                config_name TEXT,                    -- 配置名称
                config_value TEXT                    -- 配置值
            );
        """

        // t_store：收藏夹
        private const val SQL_CREATE_T_STORE = """
            CREATE TABLE IF NOT EXISTS t_store (
                id TEXT UNIQUE,                      -- 文件完整路径
                content TEXT,                        -- 文件内容
                file_name TEXT,                      -- 文件名称
                ext TEXT,                            -- 文件后缀名
                hack TEXT,                           -- 预留
                rid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, -- 预留
                dirpath TEXT                         -- 文件所在目录
            );
        """

        // t_all：所有文件（记录扫描到的所有文件）
        private const val SQL_CREATE_T_ALL = """
            CREATE TABLE IF NOT EXISTS t_all (
                file_name TEXT,                      -- 文件名称
                dirpath TEXT                         -- 文件所在目录
            );
        """

        // t_file：成功文件
        private const val SQL_CREATE_T_FILE = """
            CREATE TABLE IF NOT EXISTS t_file (
                internal_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, -- 内部自增 ID
                id TEXT,                            -- 文件完整路径（uri.toString）
                file_name TEXT,                     -- 文件名称
                content TEXT,                       -- 文件内容（可选，方便调试）
                size INTEGER,                       -- 文件大小（字节）
                ext TEXT,                           -- 文件后缀名
                modify_time INTEGER,                -- 文件修改时间
                md5 TEXT,                           -- 预留
                duplicate INTEGER,                  -- 预留
                content_status INTEGER,             -- 预留
                tags TEXT,                          -- 预留
                create_time INTEGER,                -- 文件创建时间
                status INTEGER,                     -- 文件状态（1=索引成功，其他预留）
                dirpath TEXT,                       -- 文件所在目录
                frequency INTEGER                   -- 查询次数统计（初始为 0）
            );
        """

        // t_error：错误文件
        private const val SQL_CREATE_T_ERROR = """
            CREATE TABLE IF NOT EXISTS t_error (
                dirpath TEXT,                       -- 文件所在目录
                file_name TEXT,                     -- 文件名称
                err_message TEXT,                   -- 错误信息
                err_explain TEXT                    -- 错误归类
            );
        """

        // t_content：内容表
        private const val SQL_CREATE_T_CONTENT = """
            CREATE TABLE IF NOT EXISTS t_content (
                id TEXT,                            -- 文件完整路径（uri.toString）
                content TEXT,                       -- 文件内容
                file_name TEXT,                     -- 文件名称
                ext TEXT,                           -- 文件后缀名
                hack TEXT,                          -- 预留
                rid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, -- 链接虚拟表的 rowid
                dirpath TEXT                        -- 文件所在目录
            );
        """

        // t_content_idx：FTS4 虚拟表（只写列名，不写任何 FTS 参数，最大兼容）
        private const val SQL_CREATE_T_CONTENT_IDX = """
            CREATE VIRTUAL TABLE IF NOT EXISTS t_content_idx USING fts4(
                content,                            -- 文件内容
                file_name,                          -- 文件名称
                ext                                 -- 后缀
            );
        """

        // 触发器：保持 t_content 和 t_content_idx 同步
        private const val SQL_CREATE_TRIGGER_CONTENT_INSERT = """
            CREATE TRIGGER IF NOT EXISTS t_content_ai
            AFTER INSERT ON t_content
            BEGIN
                INSERT INTO t_content_idx(rowid, content, file_name, ext)
                VALUES (new.rid, new.content, new.file_name, new.ext);
            END;
        """

        private const val SQL_CREATE_TRIGGER_CONTENT_DELETE = """
            CREATE TRIGGER IF NOT EXISTS t_content_ad
            AFTER DELETE ON t_content
            BEGIN
                DELETE FROM t_content_idx WHERE rowid = old.rid;
            END;
        """

        private const val SQL_CREATE_TRIGGER_CONTENT_BEFORE_UPDATE = """
            CREATE TRIGGER IF NOT EXISTS t_content_bu
            BEFORE UPDATE ON t_content
            BEGIN
                DELETE FROM t_content_idx WHERE rowid = old.rid;
            END;
        """

        private const val SQL_CREATE_TRIGGER_CONTENT_AFTER_UPDATE = """
            CREATE TRIGGER IF NOT EXISTS t_content_au
            AFTER UPDATE ON t_content
            BEGIN
                INSERT INTO t_content_idx(rowid, content, file_name, ext)
                VALUES (new.rid, new.content, new.file_name, new.ext);
            END;
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_T_INDEX)
        db.execSQL(SQL_CREATE_T_CONFIG)
        db.execSQL(SQL_CREATE_T_STORE)
        db.execSQL(SQL_CREATE_T_ALL)
        db.execSQL(SQL_CREATE_T_FILE)
        db.execSQL(SQL_CREATE_T_ERROR)
        db.execSQL(SQL_CREATE_T_CONTENT)
        db.execSQL(SQL_CREATE_T_CONTENT_IDX)

        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_INSERT)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_DELETE)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_BEFORE_UPDATE)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_AFTER_UPDATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前不做版本升级，后续如有需要再实现
    }

    // --------- t_content & t_content_idx：核心内容表 ---------

    /** 往 t_content 中插入一个文件记录（触发器会自动更新 t_content_idx） */
    fun insertDocument(
        path: String,
        fileName: String,
        content: String,
        ext: String,
        dirpath: String
    ) {
        val db = writableDatabase
        val sql = """
            INSERT INTO t_content (id, content, file_name, ext, dirpath)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        db.compileStatement(sql).apply {
            bindString(1, path)
            bindString(2, content)
            bindString(3, fileName)
            bindString(4, ext)
            bindString(5, dirpath)
            executeInsert()
        }
    }

    /**
     * 使用 FTS4 + 普通 LIKE 做全文检索（支持“内容 + 文件名”联合搜索，并兼容中文）
     */
    fun searchDocuments(matchQuery: String): List<SearchResult> {
        val db = readableDatabase
        val result = mutableListOf<SearchResult>()

        // 从 matchQuery 中解析出 content / file_name 的关键字
        var contentKeyword: String? = null
        var fileNameKeyword: String? = null

        if (matchQuery.isNotBlank()) {
            val parts = matchQuery
                .split("AND")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (part in parts) {
                when {
                    part.startsWith("content:") -> {
                        var v = part.removePrefix("content:").trim()
                        v = v.trim('"')
                        contentKeyword = v
                    }

                    part.startsWith("file_name:") -> {
                        var v = part.removePrefix("file_name:").trim()
                        v = v.trim('"')
                        if (v.endsWith("*")) {
                            v = v.dropLast(1)
                        }
                        fileNameKeyword = v
                    }
                }
            }
        }

        return if (matchQuery.containsCJK()) {
            // ---------- 中文：用 LIKE 在 t_content 上查 ----------
            if (contentKeyword.isNullOrEmpty() && fileNameKeyword.isNullOrEmpty()) {
                return emptyList()
            }

            val where = StringBuilder("1=1")
            val args = mutableListOf<String>()

            contentKeyword?.takeIf { it.isNotBlank() }?.let { kw ->
                where.append(" AND content LIKE ?")
                args += "%$kw%"
            }
            fileNameKeyword?.takeIf { it.isNotBlank() }?.let { kw ->
                where.append(" AND file_name LIKE ?")
                args += "%$kw%"
            }

            val sql = """
                SELECT id, file_name, dirpath, ext
                FROM t_content
                WHERE $where
            """.trimIndent()

            val cursor = db.rawQuery(sql, args.toTypedArray())
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val fileName = it.getString(1)
                    val dirpath = it.getString(2)
                    val ext = it.getString(3)
                    result.add(SearchResult(id, fileName, dirpath, ext))
                }
            }
            result
        } else {
            // ---------- 非中文：走原来的 FTS4 MATCH ----------
            val sql = """
                SELECT c.id, c.file_name, c.dirpath, c.ext
                FROM t_content_idx
                JOIN t_content AS c ON c.rid = t_content_idx.rowid
                WHERE t_content_idx MATCH ?
            """.trimIndent()

            val cursor = db.rawQuery(sql, arrayOf(matchQuery))
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val fileName = it.getString(1)
                    val dirpath = it.getString(2)
                    val ext = it.getString(3)
                    result.add(SearchResult(id, fileName, dirpath, ext))
                }
            }
            result
        }
    }

    /** 根据 id（即插入时的 path / uri.toString()）获取该文档的内容 */
    fun getDocumentContentById(id: String): String? {
        val db = readableDatabase
        val sql = "SELECT content FROM t_content WHERE id = ?"
        val cursor = db.rawQuery(sql, arrayOf(id))
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** 简单判断字符串中是否包含 CJK（中文）字符 */
    private fun String.containsCJK(): Boolean {
        for (ch in this) {
            val code = ch.code
            if (
                code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0x20000..0x2A6DF ||
                code in 0x2A700..0x2B73F ||
                code in 0x2B740..0x2B81F ||
                code in 0x2B820..0x2CEAF
            ) {
                return true
            }
        }
        return false
    }

    // --------- t_all / t_file / t_error / t_index 辅助方法（索引过程用） ---------

    /** t_all：记录扫描到的所有文件（文件名 + 目录） */
    fun insertAllFileRecord(fileName: String, dirpath: String) {
        val db = writableDatabase
        val sql = "INSERT INTO t_all (file_name, dirpath) VALUES (?, ?)"
        db.compileStatement(sql).apply {
            bindString(1, fileName)
            bindString(2, dirpath)
            executeInsert()
        }
    }

    /** t_file：记录索引成功的文件元信息 */
    fun insertFileRecord(
        path: String,
        fileName: String,
        content: String,
        size: Long,
        ext: String,
        modifyTime: Long,
        createTime: Long,
        dirpath: String
    ) {
        val db = writableDatabase
        val sql = """
            INSERT INTO t_file (
                id, file_name, content, size, ext,
                modify_time, md5, duplicate, content_status, tags,
                create_time, status, dirpath, frequency
            )
            VALUES (?, ?, ?, ?, ?, ?, NULL, 0, 0, NULL, ?, 1, ?, 0)
        """.trimIndent()
        db.compileStatement(sql).apply {
            bindString(1, path)
            bindString(2, fileName)
            bindString(3, content)
            bindLong(4, size)
            bindString(5, ext)
            bindLong(6, modifyTime)
            bindLong(7, createTime)
            bindString(8, dirpath)
            executeInsert()
        }
    }

    /** t_error：记录索引过程中出现错误的文件 */
    fun insertErrorRecord(
        dirpath: String,
        fileName: String,
        errMessage: String,
        errExplain: String
    ) {
        val db = writableDatabase
        val sql = """
            INSERT INTO t_error (dirpath, file_name, err_message, err_explain)
            VALUES (?, ?, ?, ?)
        """.trimIndent()
        db.compileStatement(sql).apply {
            bindString(1, dirpath)
            bindString(2, fileName)
            bindString(3, errMessage)
            bindString(4, errExplain)
            executeInsert()
        }
    }

    /**
     * t_index：插入/覆盖一条索引信息（用于索引开始和索引结束时更新）
     * path 一般可以直接用 treeUri.toString()
     */
    fun upsertIndex(
        path: String,
        allFileCount: Int,
        successFileCount: Int,
        errorFileCount: Int,
        indexSize: Long,
        createTime: Long,
        updateTime: Long,
        status: Int
    ) {
        val db = writableDatabase
        val sql = """
            INSERT OR REPLACE INTO t_index (
                path, all_file_count, success_file_count, error_file_count,
                index_size, create_time, update_time, status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        db.compileStatement(sql).apply {
            bindString(1, path)
            bindLong(2, allFileCount.toLong())
            bindLong(3, successFileCount.toLong())
            bindLong(4, errorFileCount.toLong())
            bindLong(5, indexSize)
            bindLong(6, createTime)
            bindLong(7, updateTime)
            bindLong(8, status.toLong())
            executeInsert()
        }
    }

    /** 查询所有索引记录，给“索引管理”板块展示用 */
    fun getAllIndexSummaries(): List<IndexSummary> {
        val db = readableDatabase
        val sql = """
            SELECT path, all_file_count, success_file_count, error_file_count,
                   index_size, create_time, update_time, status
            FROM t_index
            ORDER BY update_time DESC
        """.trimIndent()
        val list = mutableListOf<IndexSummary>()
        val cursor = db.rawQuery(sql, emptyArray())
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    IndexSummary(
                        path = it.getString(0),
                        allFileCount = it.getInt(1),
                        successFileCount = it.getInt(2),
                        errorFileCount = it.getInt(3),
                        indexSize = it.getLong(4),
                        createTime = it.getLong(5),
                        updateTime = it.getLong(6),
                        status = it.getInt(7)
                    )
                )
            }
        }
        return list
    }

    /** 查询所有错误文件，给“索引管理”板块展示用 */
    fun getAllErrorFiles(): List<ErrorFileInfo> {
        val db = readableDatabase
        val sql = """
            SELECT dirpath, file_name, err_message, err_explain
            FROM t_error
            ORDER BY rowid DESC
        """.trimIndent()
        val list = mutableListOf<ErrorFileInfo>()
        val cursor = db.rawQuery(sql, emptyArray())
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ErrorFileInfo(
                        dirpath = it.getString(0),
                        fileName = it.getString(1),
                        errMessage = it.getString(2),
                        errExplain = it.getString(3)
                    )
                )
            }
        }
        return list
    }

    /** 用于计算索引总体大小，用于索引管理板块的展示 */
    fun getDatabaseSizeBytes(): Long {
        val db = readableDatabase
        try {
            var pageCount = 0L
            var pageSize = 0L
            db.rawQuery("PRAGMA page_count;", null).use { c ->
                if (c.moveToFirst()) pageCount = c.getLong(0)
            }
            db.rawQuery("PRAGMA page_size;", null).use { c ->
                if (c.moveToFirst()) pageSize = c.getLong(0)
            }
            if (pageCount > 0 && pageSize > 0) {
                return pageCount * pageSize
            }
            // 回退到文件大小
            return java.io.File(db.path).length()
        } finally {
            db.close()
        }
    }
}

/** 搜索结果 */
data class SearchResult(
    val id: String,        // 文件完整路径（uri.toString）
    val fileName: String,  // 文件名
    val dirpath: String,   // 目录
    val ext: String        // 后缀
)

/** 索引信息（对应 t_index 一行） */
data class IndexSummary(
    val path: String,
    val allFileCount: Int,
    val successFileCount: Int,
    val errorFileCount: Int,
    val indexSize: Long,
    val createTime: Long,
    val updateTime: Long,
    val status: Int
)

/** 错误文件信息（对应 t_error 一行） */
data class ErrorFileInfo(
    val dirpath: String,
    val fileName: String,
    val errMessage: String,
    val errExplain: String
)
