package com.mossreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossreader.ReaderApp
import com.mossreader.data.Prefs
import com.mossreader.playback.PlayStatus
import com.mossreader.playback.ReaderController
import com.mossreader.playback.ReaderState
import com.mossreader.text.Paragraph
import com.mossreader.text.Role
import com.mossreader.text.Segment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, onSettings: () -> Unit, onVoices: () -> Unit) {
    val app = ReaderApp.instance
    val c = app.controller
    val st by c.state.collectAsState()
    val prefs by app.settings.flow.collectAsState()
    val modelVer by c.modelVersion.collectAsState()
    val ready = remember(modelVer, st.engineReady) { app.models.isCoreReady() }
    var showChapters by remember { mutableStateOf(false) }
    var showVoices by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val plan = st.plan
    val activeSeg = plan?.segments?.getOrNull(st.segmentIndex)
    val currentPara = activeSeg?.paragraph ?: 0

    LaunchedEffect(plan) { if (plan != null) listState.scrollToItem(currentPara.coerceAtLeast(0)) }
    LaunchedEffect(currentPara, st.status) {
        if (plan != null && (st.status == PlayStatus.PLAYING || st.status == PlayStatus.BUFFERING)) {
            listState.animateScrollToItem(currentPara.coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(st.chapters.getOrNull(st.chapterIndex)?.title ?: st.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text("${st.bookTitle} · ${st.chapterIndex + 1}/${st.chapters.size}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.Default.Menu, contentDescription = "章节") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                },
            )
        },
        bottomBar = { PlayerBar(st, prefs, c, onVoices = { showVoices = true }) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (plan == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(st.message.ifEmpty { "正在载入…" })
                }
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp)) {
                    if (!ready) item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.padding(bottom = 12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("还没有下载语音模型，暂时只能阅读。")
                                TextButton(onClick = onSettings) { Text("去设置下载") }
                            }
                        }
                    }
                    itemsIndexed(plan.paragraphs) { _, p ->
                        val isTitle = plan.segments.firstOrNull { it.paragraph == p.index }?.role == Role.TITLE
                        ParagraphText(
                            text = st.chapterText, p = p, active = activeSeg, isTitle = isTitle,
                            onTap = { off -> c.seekSegment(plan.segmentAt(off)) },
                        )
                    }
                }
            }
        }
    }

    if (showChapters) ChapterSheet(st, onPick = { i -> showChapters = false; c.openChapter(i, st.status != PlayStatus.IDLE) }, onDismiss = { showChapters = false })
    if (showVoices) VoiceSheet(st, prefs, c, onManage = { showVoices = false; onVoices() }, onDismiss = { showVoices = false })
}

@Composable
private fun ParagraphText(text: String, p: Paragraph, active: Segment?, isTitle: Boolean, onTap: (Int) -> Unit) {
    val hl = MaterialTheme.colorScheme.primaryContainer
    val hlText = MaterialTheme.colorScheme.onPrimaryContainer
    val annotated = remember(text, p, active?.start, active?.end, hl) {
        buildAnnotatedString {
            append(text.substring(p.start, p.end))
            if (active != null && active.paragraph == p.index) {
                val a = (active.start - p.start).coerceIn(0, p.end - p.start)
                val b = (active.end - p.start).coerceIn(a, p.end - p.start)
                addStyle(SpanStyle(background = hl, color = hlText), a, b)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = if (isTitle) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        else MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 31.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isTitle) 12.dp else 6.dp)
            .pointerInput(p) {
                detectTapGestures { pos -> layout?.let { onTap(p.start + it.getOffsetForPosition(pos)) } }
            },
        onTextLayout = { layout = it },
    )
}

@Composable
private fun PlayerBar(st: ReaderState, prefs: Prefs, c: ReaderController, onVoices: () -> Unit) {
    val total = st.plan?.segments?.size ?: 0
    var drag by remember { mutableStateOf<Float?>(null) }
    val playing = st.status == PlayStatus.PLAYING || st.status == PlayStatus.BUFFERING || st.status == PlayStatus.LOADING
    val speeds = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sleeps = listOf(0, 15, 30, 60)
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (total > 1) {
                Slider(
                    value = drag ?: st.segmentIndex.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { c.seekSegment(it.toInt()) }; drag = null },
                    valueRange = 0f..(total - 1).toFloat(),
                )
            }
            val statusText = when {
                st.message.isNotEmpty() -> st.message
                st.status == PlayStatus.LOADING -> "正在准备…"
                st.status == PlayStatus.BUFFERING -> "生成语音中…（实时率 ${"%.2f".format(st.rtf)}）"
                st.rtf > 0 -> "第 ${st.segmentIndex + 1}/$total 段 · 实时率 ${"%.2f".format(st.rtf)}" + if (st.rtf > 1f) "（偏慢）" else ""
                else -> "第 ${st.segmentIndex + 1}/$total 段"
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { c.prevChapter() }) { Text("上一章") }
                IconButton(onClick = { c.prevSegment() }) { Icon(AppIcons.SkipPrevious, contentDescription = "上一段") }
                FilledIconButton(onClick = { c.toggle() }, modifier = Modifier.size(64.dp)) {
                    if (st.status == PlayStatus.LOADING) CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                    else Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (playing) "暂停" else "播放", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { c.nextSegment() }) { Icon(AppIcons.SkipNext, contentDescription = "下一段") }
                TextButton(onClick = { c.nextChapter() }) { Text("下一章") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AssistChip(onClick = {
                    val i = speeds.indexOfFirst { kotlin.math.abs(it - prefs.speed) < 0.01f }
                    ReaderApp.instance.settings.update { it.copy(speed = speeds[(i + 1).mod(speeds.size)]) }
                }, label = { Text("语速 ${prefs.speed}x") })
                AssistChip(onClick = onVoices, label = { Text("声音") })
                AssistChip(onClick = {
                    val cur = if (st.sleepLeftMin == 0) 0 else sleeps.firstOrNull { it >= st.sleepLeftMin } ?: 60
                    val next = sleeps[(sleeps.indexOf(cur) + 1).mod(sleeps.size)]
                    c.setSleepTimer(next)
                }, label = { Text(if (st.sleepLeftMin > 0) "定时 ${st.sleepLeftMin} 分" else "定时关闭") })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(st: ReaderState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (st.chapterIndex - 3).coerceAtLeast(0))
        Text("章节（共 ${st.chapters.size} 章）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(state = state, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(st.chapters) { i, ch ->
                val sel = i == st.chapterIndex
                ListItem(
                    headlineContent = { Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                    colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    modifier = Modifier.clickable { onPick(i) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(st: ReaderState, prefs: Prefs, c: ReaderController, onManage: () -> Unit, onDismiss: () -> Unit) {
    val app = ReaderApp.instance
    val meta = remember { st.bookId?.let { app.books.get(it) } }
    val voices = remember { c.allVoices() }
    var narrator by remember { mutableStateOf(meta?.narratorVoice?.ifEmpty { null } ?: c.defaultVoiceId()) }
    var dialogue by remember { mutableStateOf(meta?.dialogueVoice?.ifEmpty { null } ?: prefs.dialogueVoice.ifEmpty { narrator }) }
    var split by remember { mutableStateOf(prefs.splitDialogue) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text("这本书的声音", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (voices.isEmpty()) Text("还没有可用的音色，请先下载语音模型。")
            Text("旁白", style = MaterialTheme.typography.titleSmall)
            voices.forEach { v ->
                Row(Modifier.fillMaxWidth().clickable { narrator = v.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = narrator == v.id, onClick = { narrator = v.id })
                    Text(v.name + if (v.group.isNotEmpty()) "  ·  ${v.group}" else "")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("对话使用另一个声音", style = MaterialTheme.typography.titleSmall)
                    Text("引号里的台词用下面选的声音朗读，会把句子按引号拆得更细。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = split, onCheckedChange = { split = it })
            }
            if (split) voices.forEach { v ->
                Row(Modifier.fillMaxWidth().clickable { dialogue = v.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = dialogue == v.id, onClick = { dialogue = v.id })
                    Text(v.name + if (v.group.isNotEmpty()) "  ·  ${v.group}" else "")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    c.saveBookVoices(narrator, if (split) dialogue else "")
                    app.settings.update { it.copy(splitDialogue = split) }
                    if (st.status != PlayStatus.IDLE) c.seekSegment(st.segmentIndex)
                    onDismiss()
                }) { Text("保存并应用") }
                TextButton(onClick = onManage) { Text("管理 / 克隆音色") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
