package com.example.myapplication

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.provider.DocumentsContract
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = MainViewModelFactory(this)
        val vm = ViewModelProvider(this, factory)[MainViewModel::class.java]

        setContent {
            MyApplicationTheme {
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

    // 系统目录选择器（OpenDocumentTree）
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }

            val doc = DocumentFile.fromTreeUri(context, uri)
            val name = doc?.name ?: uri.toString()
            viewModel.setSelectedDirectory(uri, name)

            Toast.makeText(context, "已选择目录：$name", Toast.LENGTH_SHORT).show()
        }
    }

    Row(
        modifier = modifier.fillMaxSize()
    ) {
        // ---------- 左侧导航栏 ----------
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = Color(0xFFE3F2FD)   // 浅蓝色背景
        ) {
            Text(
                text = "FW V1.0",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp)
            )

            NavigationRailItem(
                selected = uiState.currentSection == MainSection.SEARCH,
                onClick = { viewModel.switchSection(MainSection.SEARCH) },
                icon = {},
                label = { Text("文件搜索") }
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

            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )
        }

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
                            viewModel.showPreview(result)
                        },
                        onOpenClick = { result ->
                            val uri = try {
                                Uri.parse(result.id)
                            } catch (e: Exception) {
                                null
                            }

                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "无效的文件路径", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenDirClick = { result ->
                            val fileUri = try {
                                Uri.parse(result.id)
                            } catch (e: Exception) {
                                null
                            }

                            if (fileUri != null) {
                                val docFile = DocumentFile.fromSingleUri(context, fileUri)
                                val parent = docFile?.parentFile
                                val dirUri = parent?.uri ?: docFile?.uri

                                if (dirUri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            dirUri,
                                            DocumentsContract.Document.MIME_TYPE_DIR
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开目录", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                } else {
                                    Toast.makeText(context, "无法获取目录", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "无效的文件路径", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // 预览弹窗
                    if (uiState.isPreviewDialogVisible) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissPreview() },
                            title = { Text(text = uiState.previewFileName) },
                            text = {
                                if (uiState.isPreviewLoading) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    val bodyText = uiState.previewError
                                        ?: uiState.previewContent

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 400.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(text = bodyText)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.dismissPreview() }) {
                                    Text("关闭")
                                }
                            }
                        )
                    }
                }

                MainSection.SETTINGS -> {
                    SettingsSection(
                        selectedDirectory = uiState.selectedDirectory,
                        allExtensions = uiState.availableExtensions,
                        selectedExtensions = uiState.selectedExtensions,
                        isIndexing = uiState.isIndexing,
                        indexProgress = uiState.indexProgress,
                        onChooseDirectoryClick = {
                            directoryLauncher.launch(null)
                        },
                        onOpenExtensionPicker = {
                            viewModel.openExtensionDialog()
                        },
                        onStartIndexClick = {
                            if (uiState.selectedDirectoryUri == null) {
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

                    if (uiState.isExtensionDialogVisible) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissExtensionDialog() },
                            title = { Text("选择要建立索引的文件类型") },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp, max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    uiState.availableExtensions.forEach { ext ->
                                        val checked = uiState.selectedExtensions.contains(ext)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = {
                                                    viewModel.toggleExtension(ext)
                                                }
                                            )
                                            Text(text = ext)
                                        }
                                    }

                                    if (uiState.availableExtensions.isEmpty()) {
                                        Text(
                                            text = "当前没有可用的文件类型选项。",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.dismissExtensionDialog() }) {
                                    Text("完成")
                                }
                            }
                        )
                    }
                }

                MainSection.INDEX -> {
                    IndexSection(
                        indexes = uiState.indexList,
                        errors = uiState.errorList,
                        isLoading = uiState.isIndexInfoLoading,
                        errorMessage = uiState.indexInfoError
                    )
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
            text = "文件搜索",
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
            label = { Text("按文件内容搜索") },
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
                Text("完整路径", modifier = Modifier.weight(2f))
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
    val shortPath = shortenPath(result.id, maxLen = 25)   // ★ 只保留前 8 个字符

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = result.fileName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "路径: $shortPath",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onPreviewClick) { Text("预览") }
                TextButton(onClick = onOpenClick) { Text("打开") }
                TextButton(onClick = onOpenDirClick) { Text("打开目录") }
            }
        }
    }
}

/** 把长路径截断为前 maxLen 个字符，多余部分用 "..." 表示 */
private fun shortenPath(path: String, maxLen: Int = 8): String {
    return if (path.length <= maxLen) path else path.substring(0, maxLen) + "..."
}

/* ---------------- 系统设置板块 UI（文件类型 = 按钮 + 弹窗 + 使用说明） ---------------- */

@Composable
fun SettingsSection(
    selectedDirectory: String,
    allExtensions: List<String>,
    selectedExtensions: List<String>,
    isIndexing: Boolean,
    indexProgress: Float,
    onChooseDirectoryClick: () -> Unit,
    onOpenExtensionPicker: () -> Unit,
    onStartIndexClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "系统设置",
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

        Text("文件类型：")
        Spacer(modifier = Modifier.height(4.dp))

        val selectedSummary = if (selectedExtensions.isEmpty()) {
            "（当前未选择任何类型，将不会索引文件）"
        } else {
            selectedExtensions.joinToString(", ")
        }

        Text(
            text = selectedSummary,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenExtensionPicker) {
            Text("选择文件类型")
        }

        Spacer(modifier = Modifier.height(16.dp))

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

        // 使用说明框
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "FileWhole使用说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = """
1. 首次使用时：
   - 点击上方“选择索引目录”，在系统弹出的目录选择界面中选中你需要检索的文件夹（如 Download 中的某个子目录）；
   - 选中后，应用会记住该目录，并在后续索引和搜索中使用。

2. 选择需要索引的文件类型：
   - 点击“选择文件类型”按钮，在弹出的列表中勾选需要建立索引的类型，例如 txt、pdf、docx；
   - 目前仅对勾选的类型文件进行扫描和内容提取，未勾选的类型会被忽略。

3. 建立索引：
   - 确认索引目录和文件类型后，点击“开始建立索引”按钮；
   - 应用会在后台扫描该目录下符合条件的文件，并将文件内容写入内部的 SQLite/FTS 索引库；
   - 上方的“索引进度”会显示当前处理的进度百分比，你可以随时查看，但不用一直停留在本页面。

4. 开始搜索：
   - 索引完成后，切换左侧导航到“文件搜索”板块；
   - 可以按“文件名称”搜索，也可以按“内容”搜索，或同时输入实现联合搜索
     （例如：文件名包含“日志”，内容中包含“错误”）。

5. 其他说明：
   - 卸载应用会同时删除内部数据库和索引数据；
   - 以后如果增加新的文件类型（如 ppt、xlsx 等），只需要在系统设置中勾选对应类型并重新建立索引即可。
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/* ---------------- 索引管理板块 UI ---------------- */

@Composable
fun IndexSection(
    indexes: List<IndexSummary>,
    errors: List<ErrorFileInfo>,
    isLoading: Boolean,
    errorMessage: String?
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "索引管理",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (errorMessage != null) {
            Text(
                text = "加载失败：$errorMessage",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "索引概览",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (indexes.isEmpty()) {
            Text("暂无索引记录")
        } else {
            indexes.forEach { idx ->
                IndexSummaryCard(idx)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "错误文件",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (errors.isEmpty()) {
            Text("暂无错误记录")
        } else {
            errors.forEach { err ->
                ErrorFileRow(err)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
//工具函数，用来做索引展示的大小格式化
private fun formatIndexSize(bytes: Long): String {
    val KB = 1024.0
    val MB = KB * 1024
    val GB = MB * 1024

    return when {
        bytes < MB -> String.format("%.1fKB", bytes / KB)
        bytes < GB -> String.format("%.1fMB", bytes / MB)
        else -> String.format("%.1fGB", bytes / GB)
    }
}

@Composable
private fun IndexSummaryCard(summary: IndexSummary) {
    val shortPath = shortenPath(summary.path, maxLen = 25)
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "目录：$shortPath",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("总文件数：${summary.allFileCount}")
            Text("已索引：${summary.successFileCount}")
            Text("错误数：${summary.errorFileCount}")
            Text("索引大小：${formatIndexSize(summary.indexSize)}")
            Text("状态：${if (summary.status == 1) "完成" else "进行中"}")
        }
    }
}

@Composable
private fun ErrorFileRow(info: ErrorFileInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "文件：${info.fileName}",
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text("目录：${info.dirpath}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text("错误：${info.errExplain}", style = MaterialTheme.typography.bodySmall)
            if (info.errMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "详情：${info.errMessage}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
