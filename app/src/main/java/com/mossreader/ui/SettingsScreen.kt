package com.mossreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossreader.ReaderApp
import com.mossreader.tts.DownloadProgress
import com.mossreader.util.AppLog
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onVoices: () -> Unit) {
    val app = ReaderApp.instance
    val c = app.controller
    val scope = rememberCoroutineScope()
    val prefs by app.settings.flow.collectAsState()
    val modelVer by c.modelVersion.collectAsState()
    val busy by c.busyMessage.collectAsState()
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf(AppLog.dump()) }
    val clipboard = LocalClipboardManager.current
    val coreReady = remember(modelVer, downloading) { app.models.isCoreReady() }
    val encReady = remember(modelVer, downloading) { app.models.isEncoderReady() }
    val sizeMb = remember(modelVer, downloading) { app.models.sizeOnDisk() / 1024 / 1024 }

    fun startDownload(withEncoder: Boolean) {
        if (downloading) return
        downloading = true; error = null
        scope.launch {
            try {
                app.models.download(prefs.hfBase, withEncoder) { progress = it }
                c.modelVersion.update { it + 1 }
            } catch (e: Exception) {
                AppLog.e("下载失败", e)
                error = "下载失败：${e.message}\n可以换一个下载源（镜像）后重试，已下载的部分会自动续传。"
            } finally { downloading = false }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            downloading = true; error = null
            scope.launch {
                try {
                    val n = app.models.importFromTree(uri) { progress = it }
                    if (n == 0) error = "所选文件夹里没有找到模型文件。请选择包含 moss_tts_prefill.onnx 等文件的文件夹（或它的上级文件夹）。"
                    c.modelVersion.update { it + 1 }
                } catch (e: Exception) { error = "导入失败：${e.message}" } finally { downloading = false }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            SectionCard("语音模型（MOSS-TTS-Nano 100M · ONNX）") {
                Text(if (coreReady) "✓ 语音模型已就绪（占用 $sizeMb MB）" else "✗ 语音模型未下载")
                Text(if (encReady) "✓ 克隆编码器已就绪" else "· 克隆编码器未下载（只在克隆音色时需要）", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("下载源", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = prefs.hfBase == "https://huggingface.co", onClick = { app.settings.update { it.copy(hfBase = "https://huggingface.co") } }, label = { Text("官方") })
                    FilterChip(selected = prefs.hfBase == "https://hf-mirror.com", onClick = { app.settings.update { it.copy(hfBase = "https://hf-mirror.com") } }, label = { Text("hf-mirror 镜像") })
                }
                OutlinedTextField(
                    value = prefs.hfBase, onValueChange = { v -> app.settings.update { it.copy(hfBase = v) } },
                    label = { Text("自定义源地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                progress?.let { p ->
                    if (downloading) {
                        Text(p.message, style = MaterialTheme.typography.bodySmall)
                        if (p.totalBytes > 0) {
                            LinearProgressIndicator(progress = { (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${p.doneBytes / 1024 / 1024} / ${p.totalBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startDownload(false) }, enabled = !downloading) { Text(if (coreReady) "检查/补全" else "下载模型") }
                    OutlinedButton(onClick = { startDownload(true) }, enabled = !downloading && coreReady) { Text(if (encReady) "已含克隆编码器" else "下载克隆编码器") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { treePicker.launch(null) }, enabled = !downloading) { Text("从文件夹导入") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !downloading && sizeMb > 0) { Text("删除模型") }
                }
                Text("下载不了时：在电脑上下好两个仓库 OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX 和 OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX，拷到手机后用「从文件夹导入」。", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("声音") {
                Text("默认旁白：" + (c.allVoices().firstOrNull { it.id == c.defaultVoiceId() }?.name ?: "未选择"))
                Button(onClick = onVoices) { Text("选择音色 / 克隆我的声音") }
            }

            SectionCard("朗读") {
                LabeledSlider("推理线程数：${prefs.threads}", prefs.threads.toFloat(), 1f..8f, 6) { v -> app.settings.update { it.copy(threads = v.toInt()) } }
                Text("线程越多越快，但越耗电发热；修改后下次播放时生效。", style = MaterialTheme.typography.bodySmall)
                LabeledSlider("每段最大 token：${prefs.maxTokens}", prefs.maxTokens.toFloat(), 30f..110f, 7) { v -> app.settings.update { it.copy(maxTokens = v.toInt()) } }
                Text("越大越连贯，越小越快出声。", style = MaterialTheme.typography.bodySmall)
                LabeledSlider("停顿强度：${"%.1f".format(prefs.pauseScale)}x", prefs.pauseScale, 0.5f..2f, 5) { v -> app.settings.update { it.copy(pauseScale = (v * 10).toInt() / 10f) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("对话使用另一个声音")
                        Text("在阅读页「声音」里选择具体音色。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = prefs.splitDialogue, onCheckedChange = { v -> app.settings.update { it.copy(splitDialogue = v) } })
                }
            }

            SectionCard("诊断") {
                Button(onClick = { c.previewVoice(c.defaultVoiceId()) }, enabled = coreReady) { Text("试听测试（顺便测速）") }
                if (busy.isNotEmpty()) Text(busy, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { logText = AppLog.dump() }) { Text("刷新日志") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(AppLog.dump())) }) { Text("复制日志") }
                }
                SelectionContainer {
                    Text(
                        logText.ifEmpty { "（暂无日志）" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }

            SectionCard("关于") {
                Text("语音模型：OpenMOSS MOSS-TTS-Nano（Apache-2.0），通过 ONNX Runtime 在本机推理，朗读全程不联网。", style = MaterialTheme.typography.bodySmall)
                Text("克隆音色请只使用你本人或已获授权的声音。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    error?.let { AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("好") } }, title = { Text("提示") }, text = { Text(it) }) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除模型文件？") }, text = { Text("删除后需要重新下载才能朗读。已导入的书和克隆的音色不受影响。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; c.stopPlayback(); c.releaseEngine(); scope.launch { kotlinx.coroutines.delay(500); app.models.deleteAll(); c.modelVersion.update { it + 1 } } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}
