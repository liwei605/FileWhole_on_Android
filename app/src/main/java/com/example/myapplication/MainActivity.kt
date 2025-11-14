package com.example.myapplication

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * 主界面：左侧导航 + 右侧内容区域（Compose UI）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModelFactory(this@MainActivity)
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DocumentSearchApp(viewModel = vm)
                }
            }
        }
    }
}

/** 三个大板块 */
enum class MainSection {
    SEARCH,     // 搜索板块
    SETTINGS,   // 系统设置板块
    INDEX       // 索引管理板块
}

@Composable
fun DocumentSearchApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxSize()
    ) {
        // ---------- 左侧导航栏 ----------
        NavigationRail(
            modifier = Modifier.fillMaxHeight()
        ) {
            Text(
                text = "内容检索",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp)
            )

            NavigationRailItem(
                selected = uiState.currentSection == MainSection.SEARCH,
                onClick = { viewModel.switchSection(MainSection.SEARCH) },
                icon = {},
                label = { Text("搜索") }
            )

            NavigationRailItem(
                selected = uiState.currentSection == MainSection.SETTINGS,
                onClick = { viewModel.switchSection(MainSection.SETTINGS) },
                icon = {},
                label = { Text("系统设置") }
            )

            NavigationRailItem(
                selected = uiState.currentSection == MainSection.INDEX,
                onClick = { viewModel.switchSection(MainSection.INDEX) },
                icon = {},
                label = { Text("索引管理") }
            )
        }

        //yh代码
        // ---------- 右侧内容 ----------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when (uiState.currentSection) {
                MainSection.SEARCH -> {
                    SearchSection(
                        fileNameQuery = uiState.fileNameQuery,
                        onFileNameQueryChange = viewModel::updateFileNameQuery,
                        contentQuery = uiState.contentQuery,
                        onContentQueryChange = viewModel::updateContentQuery,
                        results = uiState.searchResults,
                        isSearching = uiState.isSearching,
                        onSearchClick = { viewModel.search() },
                        onClearClick = { viewModel.clearSearch() },
                        onPreviewClick = { result ->
                            Toast.makeText(
                                context,
                                "预览：${result.fileName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenClick = { result ->
                            Toast.makeText(
                                context,
                                "打开文件：${result.id}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onOpenDirClick = { result ->
                            Toast.makeText(
                                context,
                                "打开目录：${result.dirpath}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                MainSection.SETTINGS -> {
                    SettingsSection(
                        selectedDirectory = uiState.selectedDirectory,
                        selectedExtensions = uiState.selectedExtensions,
                        isIndexing = uiState.isIndexing,
                        indexProgress = uiState.indexProgress,
                        onChooseDirectoryClick = {
                            // 这里先用占位：真实项目用 SAF 让用户选目录
                            Toast.makeText(
                                context,
                                "这里实现目录选择（暂用占位）",
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.setSelectedDirectory("/storage/emulated/0/Documents")
                        },
                        onChooseExtensionsClick = {
                            Toast.makeText(
                                context,
                                "这里实现文件类型选择（暂用占位）",
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.setSelectedExtensions(listOf("pdf", "docx", "txt"))
                        },
                        onStartIndexClick = {
                            if (uiState.selectedDirectory == "未选择") {
                                Toast.makeText(
                                    context,
                                    "请先选择要建立索引的目录",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                viewModel.startIndexing()
                            }
                        }
                    )
                }

                MainSection.INDEX -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("索引管理板块（待实现）")
                    }
                }
            }
        }
    }
}

/* ---------------- 搜索板块 UI ---------------- */

@Composable
fun SearchSection(
    fileNameQuery: String,
    onFileNameQueryChange: (String) -> Unit,
    contentQuery: String,
    onContentQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    isSearching: Boolean,
    onSearchClick: () -> Unit,
    onClearClick: () -> Unit,
    onPreviewClick: (SearchResult) -> Unit,
    onOpenClick: (SearchResult) -> Unit,
    onOpenDirClick: (SearchResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "搜索",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = fileNameQuery,
            onValueChange = onFileNameQueryChange,
            label = { Text("按文件名称搜索") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = contentQuery,
            onValueChange = onContentQueryChange,
            label = { Text("按内容搜索") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSearchClick, enabled = !isSearching) {
                Text(if (isSearching) "搜索中..." else "搜索")
            }
            OutlinedButton(onClick = onClearClick, enabled = !isSearching) {
                Text("清空")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (results.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("文档名称", modifier = Modifier.weight(2f))
                Text("完整路径", modifier = Modifier.weight(3f))
                Text("操作", modifier = Modifier.weight(2f))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (results.isEmpty() && !isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无搜索结果")
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(results) { item ->
                        SearchResultRow(
                            result = item,
                            onPreviewClick = { onPreviewClick(item) },
                            onOpenClick = { onOpenClick(item) },
                            onOpenDirClick = { onOpenDirClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultRow(
    result: SearchResult,
    onPreviewClick: () -> Unit,
    onOpenClick: () -> Unit,
    onOpenDirClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = result.fileName,
                modifier = Modifier.weight(2f)
            )
            Text(
                text = result.id,
                modifier = Modifier.weight(3f)
            )
            Row(
                modifier = Modifier.weight(2f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onPreviewClick) { Text("预览") }
                TextButton(onClick = onOpenClick) { Text("打开") }
                TextButton(onClick = onOpenDirClick) { Text("打开目录") }
            }
        }
    }
}

/* ---------------- 系统设置板块 UI ---------------- */

@Composable
fun SettingsSection(
    selectedDirectory: String,
    selectedExtensions: List<String>,
    isIndexing: Boolean,
    indexProgress: Float,
    onChooseDirectoryClick: () -> Unit,
    onChooseExtensionsClick: () -> Unit,
    onStartIndexClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "系统设置 / 索引配置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("索引目录：")
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = selectedDirectory, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onChooseDirectoryClick) {
            Text("选择索引目录")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("索引的文件类型：")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (selectedExtensions.isEmpty()) "未选择"
            else selectedExtensions.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onChooseExtensionsClick) {
            Text("选择文件类型")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("索引进度：")
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { indexProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                isIndexing -> "正在建立索引... ${(indexProgress * 100).toInt()}%"
                indexProgress == 0f -> "尚未开始索引"
                else -> "索引已完成"
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStartIndexClick,
            enabled = !isIndexing
        ) {
            Text(if (isIndexing) "索引中..." else "开始建立索引")
        }
    }
}
