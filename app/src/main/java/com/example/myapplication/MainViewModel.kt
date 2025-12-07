package com.example.myapplication

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * UI 所有状态的统一数据类
 */
data class MainUiState(
    val currentSection: MainSection = MainSection.SETTINGS,   // 当前板块

    // 搜索板块
    val fileNameQuery: String = "",
    val contentQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,

    // 系统设置 / 索引配置
    val selectedDirectory: String = "未选择",
    val selectedDirectoryUri: String? = null,
    val availableExtensions: List<String> = listOf("txt", "pdf", "docx", "doc", "md", "log", "json","jpg","jpeg","png","webp"),
    val selectedExtensions: List<String> = listOf("txt"),
    val isIndexing: Boolean = false,
    val indexProgress: Float = 0f,

    // 文件类型选择弹窗
    val isExtensionDialogVisible: Boolean = false,

    // 预览弹窗（带分页 & 高亮）
    val isPreviewDialogVisible: Boolean = false,
    val previewFileName: String = "",
    val previewContent: String = "",        // 当前页文本
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    val previewPageIndex: Int = 0,          // 当前页（0-based）
    val previewTotalPages: Int = 0,         // 总页数
    val previewHighlightKeyword: String = "",

    // 索引管理板块
    val indexList: List<IndexSummary> = emptyList(),
    val errorList: List<ErrorFileInfo> = emptyList(),
    val isIndexInfoLoading: Boolean = false,
    val indexInfoError: String? = null
)

/**
 * ViewModel：持有状态 + 调用仓库
 */
class MainViewModel(
    private val repository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // 整个文件的完整内容，只保存在 ViewModel 内部，不放到 uiState 里，避免 Compose 一次性渲染超长文本
    private var previewFullContent: String = ""

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    private suspend fun sendToast(msg: String) {
        _toastEvent.emit(msg)
    }

    /* ---------- 导航 ---------- */

    fun switchSection(section: MainSection) {
        _uiState.update { it.copy(currentSection = section) }

        if (section == MainSection.INDEX) {
            loadIndexManagement()
        }
    }

    fun openExtensionDialog() {
        _uiState.update { it.copy(isExtensionDialogVisible = true) }
    }

    fun dismissExtensionDialog() {
        _uiState.update { it.copy(isExtensionDialogVisible = false) }
    }

    /* ---------- 搜索板块 ---------- */

    fun updateFileNameQuery(text: String) {
        _uiState.update { it.copy(fileNameQuery = text) }
    }

    fun updateContentQuery(text: String) {
        _uiState.update { it.copy(contentQuery = text) }
    }

    fun clearSearch() {
        _uiState.update {
            it.copy(
                fileNameQuery = "",
                contentQuery = "",
                searchResults = emptyList()
            )
        }
    }

    fun search() {
        val fileName = _uiState.value.fileNameQuery
        val content = _uiState.value.contentQuery
        val matchQuery = buildFtsQuery(fileName, content)

        if (matchQuery.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val result = repository.search(matchQuery)
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = result
                )
            }
        }
    }

    /** 根据“文件名/内容关键词”拼 FTS MATCH 语句 */
    private fun buildFtsQuery(fileName: String, content: String): String {
        val parts = mutableListOf<String>()
        if (content.isNotBlank()) {
            parts += "content:$content"
        }
        if (fileName.isNotBlank()) {
            parts += "file_name:${fileName}*"
        }
        return parts.joinToString(" AND ")
    }

    /* ---------- 系统设置 / 索引 ---------- */

    fun setSelectedDirectory(uri: Uri, displayName: String) {
        _uiState.update {
            it.copy(
                selectedDirectory = displayName,
                selectedDirectoryUri = uri.toString()
            )
        }
    }

    fun toggleExtension(ext: String) {
        _uiState.update { state ->
            val current = state.selectedExtensions.toMutableSet()
            if (current.contains(ext)) {
                current.remove(ext)
            } else {
                current.add(ext)
            }
            state.copy(selectedExtensions = current.toList())
        }
    }

    /** 开始构建索引 */
    fun startIndexing() {
        val state = _uiState.value
        if (state.isIndexing) return

        val uriString = state.selectedDirectoryUri ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, indexProgress = 0f) }

            val uri = Uri.parse(uriString)
            val exts = state.selectedExtensions

            try {
                repository.indexDirectory(
                    treeUri = uri,
                    exts = exts
                ) { processed, total ->
                    val progress = if (total == 0) 0f else processed.toFloat() / total.toFloat()
                    _uiState.update { it.copy(indexProgress = progress) }
                }

            } catch (e: IllegalStateException) {
                // 重复索引的友好提示
                _uiState.update { it.copy(isIndexing = false) }
                sendToast("该目录已建立索引，无需重复建立")
                return@launch

            } catch (e: Exception) {
                _uiState.update { it.copy(isIndexing = false) }
                sendToast("索引失败：" + (e.message ?: e.toString()))
                return@launch
            }

            _uiState.update { it.copy(isIndexing = false) }
        }
    }


    /* ---------- 预览弹窗：分页 + 高亮 ---------- */

    fun showPreview(result: SearchResult) {
        // 当前搜索的内容关键词，用来做高亮
        val keyword = _uiState.value.contentQuery.trim()

        viewModelScope.launch {
            // 先让弹窗显示，并进入“加载中”状态
            _uiState.update {
                it.copy(
                    isPreviewDialogVisible = true,
                    isPreviewLoading = true,
                    previewFileName = result.fileName,
                    previewContent = "",
                    previewError = null,
                    previewPageIndex = 0,
                    previewTotalPages = 0,
                    previewHighlightKeyword = keyword
                )
            }

            try {
                val content = repository.getDocumentContentById(result.id) ?: ""
                previewFullContent = content

                val totalPages = calcPreviewPageCount(content)
                val firstPageText = if (totalPages == 0) {
                    "（内容为空或提取失败）"
                } else {
                    getPreviewPageText(0)
                }

                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        previewContent = firstPageText,
                        previewError = null,
                        previewPageIndex = if (totalPages == 0) 0 else 0,
                        previewTotalPages = totalPages
                    )
                }
            } catch (e: Exception) {
                previewFullContent = ""
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        previewContent = "",
                        previewError = e.message ?: "读取内容失败",
                        previewPageIndex = 0,
                        previewTotalPages = 0
                    )
                }
            }
        }
    }

    /** 下一页 */
    fun nextPreviewPage() {
        val content = previewFullContent
        if (content.isEmpty()) return

        val state = _uiState.value
        val current = state.previewPageIndex
        val total = state.previewTotalPages
        if (current >= total - 1) return

        val newIndex = current + 1
        val newText = getPreviewPageText(newIndex)
        _uiState.update {
            it.copy(
                previewPageIndex = newIndex,
                previewContent = newText
            )
        }
    }

    /** 上一页 */
    fun prevPreviewPage() {
        val content = previewFullContent
        if (content.isEmpty()) return

        val state = _uiState.value
        val current = state.previewPageIndex
        if (current <= 0) return

        val newIndex = current - 1
        val newText = getPreviewPageText(newIndex)
        _uiState.update {
            it.copy(
                previewPageIndex = newIndex,
                previewContent = newText
            )
        }
    }

    fun dismissPreview() {
        previewFullContent = ""
        _uiState.update {
            it.copy(
                isPreviewDialogVisible = false,
                isPreviewLoading = false,
                previewContent = "",
                previewError = null,
                previewPageIndex = 0,
                previewTotalPages = 0,
                previewHighlightKeyword = ""
            )
        }
    }

    /** 计算总页数 */
    private fun calcPreviewPageCount(content: String): Int {
        if (content.isEmpty()) return 0
        return (content.length + PREVIEW_PAGE_CHARS - 1) / PREVIEW_PAGE_CHARS
    }

    /** 取指定页的文本（基于字符数分页） */
    private fun getPreviewPageText(pageIndex: Int): String {
        val content = previewFullContent
        if (content.isEmpty()) return ""
        val pageSize = PREVIEW_PAGE_CHARS
        val start = pageIndex * pageSize
        if (start >= content.length) return ""
        val end = min(start + pageSize, content.length)
        return content.substring(start, end)
    }

    /* ---------- 索引管理板块：加载 t_index & t_error ---------- */

    private fun loadIndexManagement() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isIndexInfoLoading = true,
                    indexInfoError = null
                )
            }
            try {
                val indexes = repository.loadIndexSummaries()
                val errors = repository.loadErrorFiles()
                _uiState.update {
                    it.copy(
                        isIndexInfoLoading = false,
                        indexList = indexes,
                        errorList = errors,
                        indexInfoError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isIndexInfoLoading = false,
                        indexInfoError = e.message ?: "加载索引信息失败"
                    )
                }
            }
        }
    }

    companion object {
        // 每页最多多少个字符，你可以根据体验再调（越小越安全）
        private const val PREVIEW_PAGE_CHARS = 2000
    }
}

/** 简单的 ViewModelFactory，用来把 Context → DB → Repository 注入到 ViewModel */
class MainViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val appContext = context.applicationContext
            val db = DocumentDatabaseHelper.getInstance(appContext)
            val repo = DocumentRepository(db, appContext)
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
