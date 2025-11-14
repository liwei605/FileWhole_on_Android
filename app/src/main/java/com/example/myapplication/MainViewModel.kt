package com.example.myapplication

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val selectedExtensions: List<String> = listOf("pdf", "docx", "txt"),
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

    fun setSelectedDirectory(path: String) {
        _uiState.update { it.copy(selectedDirectory = path) }
    }

    fun setSelectedExtensions(exts: List<String>) {
        _uiState.update { it.copy(selectedExtensions = exts) }
    }

    /** 索引进度模拟（以后可以换成真实目录扫描逻辑） */
    fun startIndexing() {
        val state = _uiState.value
        if (state.isIndexing) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isIndexing = true, indexProgress = 0f) }

            repeat(20) { step ->
                delay(150)
                val progress = (step + 1) / 20f.toFloat()
                _uiState.update { it.copy(indexProgress = progress) }
            }

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
            val db = DocumentDatabaseHelper.getInstance(context.applicationContext)
            val repo = DocumentRepository(db)
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
