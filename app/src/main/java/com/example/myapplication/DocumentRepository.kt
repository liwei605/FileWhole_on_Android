package com.example.myapplication

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.apache.poi.xwpf.usermodel.XWPFDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileNotFoundException
import android.provider.DocumentsContract
import kotlin.toString

/**
 * 仓库层：对外只暴露“业务用得上的方法”，内部调用 SQLite 帮助类
 */
class DocumentRepository(
    private val db: DocumentDatabaseHelper,
    private val context: Context
) {

    init {
        // 初始化 PDFBox（防止第一次用时报未初始化）
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** 全文检索 */
    suspend fun search(matchQuery: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            db.searchDocuments(matchQuery)
        }

    /** 根据 id 取文档内容，供前端预览使用 */
    suspend fun getDocumentContentById(id: String): String? =
        withContext(Dispatchers.IO) {
            db.getDocumentContentById(id)
        }

    /** 供需要时手动写入单条（目前索引过程内部用的是下方 indexDirectory） */
    suspend fun insertDocument(
        path: String,
        fileName: String,
        content: String,
        ext: String,
        dirpath: String
    ) = withContext(Dispatchers.IO) {
        db.insertDocument(path, fileName, content, ext, dirpath)
    }

    /** “索引管理”板块需要的索引列表 */
    suspend fun loadIndexSummaries(): List<IndexSummary> =
        withContext(Dispatchers.IO) {
            db.getAllIndexSummaries()
        }

    /** “索引管理”板块需要的错误文件列表 */
    suspend fun loadErrorFiles(): List<ErrorFileInfo> =
        withContext(Dispatchers.IO) {
            db.getAllErrorFiles()
        }

    /** “索引管理”板块需要的错误文件列表 */
    suspend fun geiIndexSize(): Long =
        withContext(Dispatchers.IO) {
            db.getDatabaseSizeBytes()
        }

    /**
     * 扫描指定目录（SAF treeUri），只处理给定扩展名的文件。
     * 对每个文件使用对应的内容读取器，将文本写入 t_content / t_content_idx，
     * 同时：
     *   - 所有扫描到的文件写入 t_all
     *   - 成功文件写入 t_file
     *   - 失败文件写入 t_error
     *   - 索引整体信息写入 t_index
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

        // 1. 收集所有目标文件
        val files = mutableListOf<DocumentFile>()

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
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

        // 替换原来的 val indexPath = treeUri.toString()
        val indexPath: String = try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Documents/Folder"
            // 去掉 primary: 前缀并清理前导分隔符，得到相对路径 "Documents/Folder"
            val relative = if (rootId.startsWith("primary:")) rootId.removePrefix("primary:") else rootId
            relative.trimStart('/', ':')
        } catch (e: Exception) {
            // 回退到较短的表示法（不会是完整绝对 URI）
            treeUri.path ?: treeUri.toString()
        }

        val startTime = System.currentTimeMillis()

        // 索引刚开始：写一条“进行中”的 t_index 记录
        db.upsertIndex(
            path = indexPath,
            allFileCount = total,
            successFileCount = 0,
            errorFileCount = 0,
            indexSize = 0L,
            createTime = startTime,
            updateTime = startTime,
            status = 0
        )

        var successCount = 0
        var errorCount = 0
        var totalSize = 0L

        // 2. 逐个读取内容并插入数据库
        for (file in files) {
            val uri = file.uri
            val name = file.name ?: "unknown"
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val parentDirName = file.parentFile?.name ?: root.name ?: "root"
            val dirpath = parentDirName

            // 不管成功/失败，先记录到 t_all（所有扫描到的文件）
            db.insertAllFileRecord(name, dirpath)

            try {
                // 根据不同后缀选择不同的内容读取实现
                val reader = getReaderForExt(ext)
                val content = reader.read(uri) ?: ""

                // 写入 t_content & FTS
                db.insertDocument(
                    path = uri.toString(),
                    fileName = name,
                    content = content,
                    ext = ext,
                    dirpath = dirpath
                )

                // 写入 t_file（成功文件元数据）
                val size = file.length()
                val modifyTime = file.lastModified()
                val createTime = System.currentTimeMillis()

                db.insertFileRecord(
                    path = uri.toString(),
                    fileName = name,
                    content = content,
                    size = size,
                    ext = ext,
                    modifyTime = modifyTime,
                    createTime = createTime,
                    dirpath = dirpath
                )

                successCount++
            } catch (e: Exception) {
                errorCount++
                val errMsg = e.message ?: e.toString()
                val errExplain = classifyError(e)

                // 写入 t_error
                db.insertErrorRecord(
                    dirpath = dirpath,
                    fileName = name,
                    errMessage = errMsg,
                    errExplain = errExplain
                )
            } finally {
                processed++
                onProgress(processed, total)
            }
        }

        // 3. 索引完成后，更新 t_index 总结信息
        val endTime = System.currentTimeMillis()
        totalSize = geiIndexSize()
        db.upsertIndex(
            path = indexPath,
            allFileCount = total,
            successFileCount = successCount,
            errorFileCount = errorCount,
            indexSize = totalSize,
            createTime = startTime,
            updateTime = endTime,
            status = 1
        )
    }

    /** 根据异常类型做简单错误归类，写入 t_error.err_explain */
    private fun classifyError(e: Exception): String {
        return when (e) {
            is FileNotFoundException -> "文件未找到"
            is SecurityException -> "权限不足"
            else -> "解析失败或未知错误"
        }
    }

    /* ======================== 不同类型文件内容读取接口 ======================== */

    /** 根据扩展名返回对应的内容读取器 */
    private fun getReaderForExt(ext: String): FileContentReader {
        val lower = ext.lowercase()
        return when (lower) {
            "txt", "md", "log", "csv", "json", "xml" ->
                TextFileContentReader(context)

            "pdf" ->
                PdfFileContentReader(context)

            "docx", "doc" ->
                DocxFileContentReader(context)

            else ->
                TextFileContentReader(context)   // 默认按文本尝试读取
        }
    }

    /** 针对不同后缀的文件内容读取接口 */
    private interface FileContentReader {
        suspend fun read(uri: Uri): String?
    }

    /** 纯文本读取器：txt / md / log / csv 等 */
    private inner class TextFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromDocument(uri)
        }
    }

    /** PDF 文本提取 */
    private inner class PdfFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromPDF(uri)
        }
    }

    /** DOCX 文本提取 */
    private inner class DocxFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromDocx(uri)
        }
    }

    /** 从 DocumentFile uri 读取纯文本内容（用于 txt/md 等） */
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

    private fun readTextFromPDF(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun readTextFromDocx(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                XWPFDocument(input).use { doc ->
                    val sb = StringBuilder()

                    // 段落
                    for (para in doc.paragraphs) {
                        val text = para.text
                        if (!text.isNullOrBlank()) {
                            sb.appendLine(text)
                        }
                    }

                    // 表格中的文字（可选）
                    for (table in doc.tables) {
                        for (row in table.rows) {
                            for (cell in row.tableCells) {
                                val cellText = cell.text
                                if (!cellText.isNullOrBlank()) {
                                    sb.appendLine(cellText)
                                }
                            }
                        }
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


}
