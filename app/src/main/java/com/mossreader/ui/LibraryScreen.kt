package com.mossreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mossreader.ReaderApp
import com.mossreader.data.BookMeta
import com.mossreader.playback.PlayStatus
import com.mossreader.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onResume: () -> Unit, onSettings: () -> Unit) {
    val app = ReaderApp.instance
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(app.books.list()) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<BookMeta?>(null) }
    val modelVer by app.controller.modelVersion.collectAsState()
    val ready = remember(modelVer) { app.models.isCoreReady() }
    val st by app.controller.state.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importing = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { app.books.import(uri) } }
                importing = false
                r.onFailure { error = it.message ?: "导入失败"; AppLog.e("导入失败", it) }
                books = app.books.list()
            }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("小说朗读") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { launcher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入小说") },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!ready) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有语音模型", style = MaterialTheme.typography.titleMedium)
                            Text("首次使用需要下载 MOSS-TTS-Nano 模型文件（几百 MB，建议连 Wi-Fi）。", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onSettings) { Text("去下载") }
                        }
                    }
                }
                if (st.bookId != null && st.status != PlayStatus.IDLE) item {
                    Card(
                        onClick = onResume,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("正在朗读", style = MaterialTheme.typography.labelMedium)
                            Text(st.bookTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(st.chapters.getOrNull(st.chapterIndex)?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (books.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("书架是空的", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("点右下角导入 TXT 或 EPUB 小说，会自动识别编码并分章。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(books, key = { it.id }) { b ->
                    val (ci, _) = remember(b.id, st.chapterIndex) { app.books.progress(b.id) }
                    Card(onClick = { onOpen(b.id) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${b.chapters.size} 章 · " + "%.1f".format(b.totalChars / 10000.0) + " 万字", style = MaterialTheme.typography.bodySmall)
                                Text("读到：${b.chapters.getOrNull(ci)?.title ?: "—"}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { if (b.chapters.isEmpty()) 0f else (ci + 1f) / b.chapters.size }, modifier = Modifier.fillMaxWidth())
                            }
                            IconButton(onClick = { toDelete = b }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
                        }
                    }
                }
            }
            if (importing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(32.dp))
                        Spacer(Modifier.padding(8.dp))
                        Text("正在导入并分章…")
                    } }
                }
            }
        }
    }

    error?.let {
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("好") } }, title = { Text("导入失败") }, text = { Text(it) })
    }
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除《${b.title}》？") },
            text = { Text("书和阅读进度都会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    if (st.bookId == b.id) app.controller.closeBook()
                    app.books.delete(b.id); books = app.books.list(); toDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } },
        )
    }
}
