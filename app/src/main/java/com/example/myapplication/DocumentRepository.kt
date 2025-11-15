package com.example.myapplication

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 仓库层：对外只暴露“业务用得上的方法”，内部调用 SQLite 帮助类
 */
class DocumentRepository(
    private val db: DocumentDatabaseHelper,
    private val context: Context
) {

    /** 全文检索 */
    suspend fun search(matchQuery: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            db.searchDocuments(matchQuery)
        }

    /** 单条写入（目前索引过程内部会用到） */
    suspend fun insertDocument(
        path: String,
        fileName: String,
        content: String,
        ext: String,
        dirpath: String
    ) = withContext(Dispatchers.IO) {
        db.insertDocument(path, fileName, content, ext, dirpath)
    }

    /**
     * 扫描指定目录（SAF treeUri），只处理给定扩展名的文件，目前主要是 txt。
     * 对每个文件读取文本内容并写入 t_content / t_content_idx。
     *
     * @param treeUri 通过系统目录选择器获得的 Uri
     * @param exts  要索引的扩展名列表，例如 ["txt", "md"]
     * @param onProgress 进度回调，参数为 (已处理数量, 总数量)
     */
    suspend fun indexDirectory(
        treeUri: Uri,
        exts: List<String>,
        onProgress: (processed: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext

        val normalizedExts = exts
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .toSet()

        // 1. 先收集所有目标文件，便于计算总数
        val files = mutableListOf<DocumentFile>()

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                    // TODO: 未来这里可以扩展为 pdf/docx 等类型的解析
                    if (normalizedExts.isEmpty() || normalizedExts.contains(ext)) {
                        files += child
                    }
                }
            }
        }

        walk(root)

        val total = files.size
        var processed = 0
        onProgress(processed, total)

        // 2. 逐个读取内容并插入数据库
        for (file in files) {
            try {
                val uri = file.uri
                val name = file.name ?: "unknown"
                val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

                // 这里为了展示方便，用根目录名存 dirpath（也可以存 uri）
                val dirpath = root.name ?: "root"

                val content = readTextFromDocument(uri) ?: ""

                // 使用 uri.toString 作为唯一标识(id)，以后打开文件可以用这个 uri
                db.insertDocument(
                    path = uri.toString(),
                    fileName = name,
                    content = content,
                    ext = ext,
                    dirpath = dirpath
                )

                processed++
                onProgress(processed, total)
            } catch (e: Exception) {
                // TODO: 这里可以往 t_error 表写一条错误数据
                processed++
                onProgress(processed, total)
            }
        }
    }

    /** 从 DocumentFile uri 读取纯文本内容 */
    private fun readTextFromDocument(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        sb.appendLine(line)
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
