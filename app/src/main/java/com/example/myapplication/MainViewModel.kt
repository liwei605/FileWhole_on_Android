package com.example.myapplication

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val availableExtensions: List<String> = listOf("txt", "pdf", "docx","doc","md","log","json"),
    val selectedExtensions: List<String> = listOf("txt"),
    val isIndexing: Boolean = false,
    val indexProgress: Float = 0f,

    // 文件类型选择弹窗
    val isExtensionDialogVisible: Boolean = false,

    // 预览弹窗
    val isPreviewDialogVisible: Boolean = false,
    val previewFileName: String = "",
    val previewContent: String = "",
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,

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

            repository.indexDirectory(
                treeUri = uri,
                exts = exts
            ) { processed, total ->
                val progress = if (total == 0) 0f else processed.toFloat() / total.toFloat()
                _uiState.update { it.copy(indexProgress = progress) }
            }

            _uiState.update { it.copy(isIndexing = false) }
        }
    }

    /* ---------- 预览弹窗 ---------- */

    fun showPreview(result: SearchResult) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPreviewDialogVisible = true,
                    isPreviewLoading = true,
                    previewFileName = result.fileName,
                    previewContent = "",
                    previewError = null
                )
            }

            try {
                val content = repository.getDocumentContentById(result.id) ?: ""
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        previewContent = content.ifBlank { "（内容为空或提取失败）" },
                        previewError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        previewContent = "",
                        previewError = e.message ?: "读取内容失败"
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        _uiState.update {
            it.copy(
                isPreviewDialogVisible = false,
                isPreviewLoading = false,
                previewContent = "",
                previewError = null
            )
        }
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
