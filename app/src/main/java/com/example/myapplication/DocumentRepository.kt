package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 仓库层：对外只暴露“业务用得上的方法”，内部调用 SQLite 帮助类
 */
class DocumentRepository(
    private val db: DocumentDatabaseHelper
) {

    /** 全文检索 */
    suspend fun search(matchQuery: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            db.searchDocuments(matchQuery)
        }

    /** 写入一条文档记录（未来做真正的索引时会用到） */
    suspend fun insertDocument(
        path: String,
        fileName: String,
        content: String,
        ext: String,
        dirpath: String
    ) = withContext(Dispatchers.IO) {
        db.insertDocument(path, fileName, content, ext, dirpath)
    }
}
