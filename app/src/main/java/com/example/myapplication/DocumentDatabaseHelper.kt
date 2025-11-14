package com.example.myapplication

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * document.sqlite 数据库
 * 负责创建你定义的 8 个表 + FTS5 虚拟表
 *
 * 设计目标：
 * - 不使用任何 FTS5 附加参数（无 tokenize=、content=、content_rowid=、UNINDEXED 等）
 * - 只用列定义 + rowid + 触发器，最大限度兼容旧 SQLite/旧 FTS5 实现
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
                path TEXT PRIMARY KEY,                -- 创建的索引文件夹（唯一）
                all_file_count INTEGER,              -- 总文件数
                success_file_count INTEGER,          -- 成功文件数
                error_file_count INTEGER,            -- 失败文件数
                index_size INTEGER,                  -- 索引大小
                create_time INTEGER,                 -- 创建索引时间
                update_time INTEGER,                 -- 更新索引时间
                status INTEGER                       -- 索引状态
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
                id TEXT,                            -- 文件完整路径
                file_name TEXT,                     -- 文件名称
                content TEXT,                       -- 文件内容
                size INTEGER,                       -- 文件大小
                ext TEXT,                           -- 文件后缀名
                modify_time INTEGER,                -- 文件修改时间
                md5 TEXT,                           -- 预留
                duplicate INTEGER,                  -- 预留
                content_status INTEGER,             -- 预留
                tags TEXT,                          -- 预留
                create_time INTEGER,                -- 文件创建时间
                status INTEGER,                     -- 文件状态
                dirpath TEXT,                       -- 文件所在目录
                frequency INTEGER                   -- 查询次数统计
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
        // 注意：这里加回了 hack 字段，以匹配 insertDocument 中的插入语句
        private const val SQL_CREATE_T_CONTENT = """
            CREATE TABLE IF NOT EXISTS t_content (
                id TEXT,                            -- 文件完整路径
                content TEXT,                       -- 文件内容
                file_name TEXT,                     -- 文件名称
                ext TEXT,                           -- 文件后缀名
                hack TEXT,                          -- 预留
                rid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, -- 链接虚拟表的 rowid
                dirpath TEXT                        -- 文件所在目录
            );
        """

        // t_content_idx：FTS5 虚拟表
        // ★ 关键：只写列名，不写任何 FTS5 参数，最大程度兼容旧 SQLite/FTS5
        private const val SQL_CREATE_T_CONTENT_IDX = """
            CREATE VIRTUAL TABLE IF NOT EXISTS t_content_idx USING fts4(
                content,                            -- 文件内容
                file_name,                          -- 文件名称
                ext                                 -- 后缀
            );
        """

        // 让 t_content 和 t_content_idx 自动保持同步的触发器
        // 这里通过 rowid = t_content.rid 建立关联，不使用 content= / content_rowid= 参数
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

        // 更新前：先删掉旧索引
            private const val SQL_CREATE_TRIGGER_CONTENT_BEFORE_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS t_content_bu
        BEFORE UPDATE ON t_content
        BEGIN
            DELETE FROM t_content_idx WHERE rowid = old.rid;
        END;
    """

            // 更新后：插入新索引
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

        // 触发器，保持 t_content 和 t_content_idx 同步
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_INSERT)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_DELETE)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_BEFORE_UPDATE)
        db.execSQL(SQL_CREATE_TRIGGER_CONTENT_AFTER_UPDATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
//        // 简单粗暴：版本变化时全部重建（后面你需要真正升级逻辑再改）
//        db.execSQL("DROP TRIGGER IF EXISTS t_content_ai")
//        db.execSQL("DROP TRIGGER IF EXISTS t_content_ad")
//        db.execSQL("DROP TRIGGER IF EXISTS t_content_au")
//        db.execSQL("DROP TRIGGER IF EXISTS t_content_bu")
//        db.execSQL("DROP TRIGGER IF EXISTS t_content_au")
//        db.execSQL("DROP TABLE IF EXISTS t_content_idx")
//        db.execSQL("DROP TABLE IF EXISTS t_content")
//        db.execSQL("DROP TABLE IF EXISTS t_error")
//        db.execSQL("DROP TABLE IF EXISTS t_file")
//        db.execSQL("DROP TABLE IF EXISTS t_all")
//        db.execSQL("DROP TABLE IF EXISTS t_store")
//        db.execSQL("DROP TABLE IF EXISTS t_config")
//        db.execSQL("DROP TABLE IF EXISTS t_index")
//        onCreate(db)
    }

    // --------- 一些核心操作例子（t_content & t_content_idx） ---------

    /**
     * 往 t_content 中插入一个文件记录（触发器会自动更新 t_content_idx）
     */
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
            bindString(1, path)      // id = 文件完整路径
            bindString(2, content)   // content = 文件内容
            bindString(3, fileName)  // file_name = 文件名
            bindString(4, ext)       // ext = 后缀
            bindString(5, dirpath)   // dirpath = 目录
            executeInsert()
        }
        // t_content_ai 触发器会自动把数据插入 t_content_idx
    }


    /**
     * 使用 FTS5 在 t_content_idx 上做全文检索
     * @param matchQuery 完整的 FTS5 MATCH 语句，例如：
     *  - "content:错误"
     *  - "file_name:报告"
     *  - "content:日志 AND file_name:接口"
     */
    fun searchDocuments(matchQuery: String): List<SearchResult> {
        val db = readableDatabase
        val sql = """
        SELECT c.id, c.file_name, c.dirpath, c.ext
        FROM t_content_idx
        JOIN t_content AS c ON c.rid = t_content_idx.rowid
        WHERE t_content_idx MATCH ?
    """.trimIndent()

//        // 只在 content 列里搜，把查询改成 "content:关键字"
//        val ftsQuery = "content:$query"

        val cursor = db.rawQuery(sql, arrayOf(matchQuery))
        val result = mutableListOf<SearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val fileName = it.getString(1)
                val dirpath = it.getString(2)
                val ext = it.getString(3)
                result.add(SearchResult(id, fileName, dirpath, ext))
            }
        }
        return result
    }
}

/**
 * 检索结果简单封装
 */
data class SearchResult(
    val id: String,        // 文件完整路径
    val fileName: String,  // 文件名
    val dirpath: String,   // 目录
    val ext: String        // 后缀
)
