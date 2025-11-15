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
    val selectedDirectory: String = "未选择",           // 展示给用户看的名称
    val selectedDirectoryUri: String? = null,          // SAF 的 Uri 字符串
    val selectedExtensions: List<String> = listOf("txt"), // 先只做 txt，方便扩展
    val isIndexing: Boolean = false,
    val indexProgress: Float = 0f
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
            // 文件名前缀匹配
            parts += "file_name:${fileName}*"
        }
        return parts.joinToString(" AND ")
    }

    /* ---------- 系统设置 / 索引板块 ---------- */

    /** 用户选择了目录之后调用 */
    fun setSelectedDirectory(uri: Uri, displayName: String) {
        _uiState.update {
            it.copy(
                selectedDirectory = displayName,
                selectedDirectoryUri = uri.toString()
            )
        }
    }

    fun setSelectedExtensions(exts: List<String>) {
        _uiState.update { it.copy(selectedExtensions = exts) }
    }

    /**
     * 开始构建索引：扫描 selectedDirectoryUri 下的所有 txt 文件并写入数据库，
     * 同时根据处理进度更新进度条。
     */
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

            // 索引结束
            _uiState.update { it.copy(isIndexing = false) }
        }
    }
}

/**
 * 简单的 ViewModelFactory，用来把 Context → DB → Repository 注入到 ViewModel
 */
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
