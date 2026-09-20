package com.mossreader.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mossreader.ReaderApp
import com.mossreader.audio.AudioDecoder
import com.mossreader.audio.VoiceRecorder
import com.mossreader.tts.VoiceEntry
import com.mossreader.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onBack: () -> Unit, onSettings: () -> Unit) {
    val app = ReaderApp.instance
    val c = app.controller
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by app.settings.flow.collectAsState()
    val voiceVer by c.voiceVersion.collectAsState()
    val modelVer by c.modelVersion.collectAsState()
    val busy by c.busyMessage.collectAsState()
    val voices = remember(voiceVer, modelVer) { c.allVoices() }
    val encoderReady = remember(modelVer) { app.models.isEncoderReady() }
    val coreReady = remember(modelVer) { app.models.isCoreReady() }

    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<AudioDecoder.Pcm?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VoiceEntry?>(null) }
    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0f) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(ctx, uri, 40) } }
                working = false
                r.onSuccess { pending = it; nameInput = "我的声音 ${voices.count { v -> !v.builtin } + 1}" }
                    .onFailure { info = "无法读取这个音频文件：${it.message}"; AppLog.e("解码参考音频失败", it) }
            }
        }
    }

    fun beginRecording() {
        try {
            recorder.start(20) { }
            recording = true; recSeconds = 0f
        } catch (e: Exception) { info = "无法开始录音：${e.message}" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording() else info = "没有录音权限，无法录音克隆。"
    }
    fun finishRecording() {
        val samples = recorder.stop()
        recording = false
        if (samples.size < recorder.sampleRate) { info = "录音太短了，至少说 1 秒以上（建议 5–15 秒）。"; return }
        pending = AudioDecoder.Pcm(arrayOf(samples), recorder.sampleRate)
        nameInput = "我的声音 ${voices.count { v -> !v.builtin } + 1}"
    }
    LaunchedEffect(recording) {
        while (recording) {
            recSeconds = recorder.seconds
            if (!recorder.isRunning) { finishRecording(); break }
            delay(100)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("声音管理") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("克隆我的声音", style = MaterialTheme.typography.titleMedium)
                        Text("给一段 5–15 秒、安静环境下的清晰人声（只有一个人说话，没有背景音乐），就能用这个声音朗读。只克隆你本人或已获授权的声音。", style = MaterialTheme.typography.bodySmall)
                        if (!coreReady) Text("需要先下载语音模型。", color = MaterialTheme.colorScheme.error)
                        else if (!encoderReady) {
                            Text("需要先下载「克隆编码器」。", color = MaterialTheme.colorScheme.error)
                            Button(onClick = onSettings) { Text("去设置下载") }
                        } else if (recording) {
                            Text("正在录音… ${"%.1f".format(recSeconds)} 秒（最长 20 秒）")
                            LinearProgressIndicator(progress = { (recSeconds / 20f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { finishRecording() }) { Icon(AppIcons.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.padding(4.dp)); Text("停止并使用") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pick.launch(arrayOf("audio/*", "video/*")) }, enabled = !working) { Text("选择音频文件") }
                                OutlinedButton(onClick = {
                                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                                }, enabled = !working) { Icon(AppIcons.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.padding(4.dp)); Text("录音") }
                            }
                        }
                        if (working) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (busy.isNotEmpty()) Text(busy, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val nName = voices.firstOrNull { it.id == c.defaultVoiceId() }?.name ?: "—"
                val dName = voices.firstOrNull { it.id == prefs.dialogueVoice }?.name ?: "（同旁白）"
                Text("默认旁白：$nName　·　对话：$dName", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            val groups = voices.groupBy { it.group.ifEmpty { "其他" } }
            val order = groups.keys.sortedBy { if (it == "我的克隆音色") "" else it }
            for (g in order) {
                item(key = "h_$g") { Text(g, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                for (v in groups.getValue(g)) {
                    item(key = "v_${v.id}") {
                        ListItem(
                            headlineContent = { Text(v.name) },
                            supportingContent = {
                                val tags = buildList {
                                    if (v.id == c.defaultVoiceId()) add("默认旁白")
                                    if (v.id == prefs.dialogueVoice && prefs.dialogueVoice.isNotEmpty()) add("对话")
                                }
                                if (tags.isNotEmpty()) Text(tags.joinToString(" · "))
                            },
                            modifier = Modifier.clickable { selected = v },
                        )
                        HorizontalDivider()
                    }
                }
            }
            if (voices.isEmpty()) item { Text("还没有可用音色。请先在设置里下载语音模型。") }
        }
    }

    pending?.let { pcm ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("给这个音色起个名字") },
            text = {
                Column {
                    Text("参考音频 ${"%.1f".format(pcm.channels[0].size / pcm.sampleRate.toFloat())} 秒（超过 15 秒只取前 15 秒）", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameInput.ifBlank { "我的声音" }
                    pending = null
                    working = true
                    scope.launch {
                        try { c.cloneVoice(name, pcm) }
                        catch (e: Throwable) { AppLog.e("克隆失败", e); info = "克隆失败：${e.message}" }
                        finally { working = false }
                    }
                }) { Text("开始克隆") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }

    selected?.let { v ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(v.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { c.previewVoice(v.id); selected = null }, enabled = coreReady) { Text("试听") }
                    TextButton(onClick = { app.settings.update { it.copy(narratorVoice = v.id) }; selected = null }) { Text("设为默认旁白") }
                    TextButton(onClick = { app.settings.update { it.copy(dialogueVoice = v.id, splitDialogue = true) }; selected = null }) { Text("设为对话声音（并开启对话独立声音）") }
                    if (!v.builtin) TextButton(onClick = { c.deleteVoice(v.id); selected = null }) { Text("删除这个克隆音色", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
        )
    }
    info?.let { AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton(onClick = { info = null }) { Text("好") } }, title = { Text("提示") }, text = { Text(it) }) }
}
