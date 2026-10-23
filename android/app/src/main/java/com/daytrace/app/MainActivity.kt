package com.daytrace.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val store get() = (application as DayTraceApp).store
    private val app get() = application as DayTraceApp
    private val serverPreferences get() = app.serverPreferences
    private var feedback by mutableStateOf<String?>(null)
    private var playing by mutableStateOf<String?>(null)
    private var ioBusy by mutableStateOf(false)
    private var player: MediaPlayer? = null
    private var playFile: File? = null
    private var pendingExport: String? = null
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCapture() else feedback = "需要允许麦克风权限才能录音。请在系统应用设置中开启后重试。"
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) feedback = "通知未获允许，建议开启，以便从通知栏暂停或停止录音。"
        requestAudio()
    }
    private val wavExport = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) export(uri, File(store.directory, name), false)
    }
    private val opusExport = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/ogg")) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) export(uri, File(store.directory, name), false)
    }
    private val csvExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) export(uri, store.diagnostics, true)
    }
    private val bundleExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportBundle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("export")
        enableEdgeToEdge()
        setContent {
            val colors = lightColorScheme(primary = Color(0xFF316C58), onPrimary = Color.White,
                background = Color(0xFFF6F5EF), surface = Color(0xFFFFFEFA),
                secondaryContainer = Color(0xFFE3EDE3), onSecondaryContainer = Color(0xFF264638))
            MaterialTheme(colorScheme = colors) { Screen() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export", pendingExport)
        super.onSaveInstanceState(outState)
    }

    private fun requestAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCapture()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestStart() {
        stopPlayback()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestAudio()
    }

    private fun startCapture() {
        try { startForegroundService(Intent(this, RecordingService::class.java).setAction(RecordingService.START)) }
        catch (e: Exception) { feedback = "无法启动录音：${e.message}" }
    }

    private fun command(action: String) {
        if (store.state.value.phase == Phase.PAUSED && action == RecordingService.STOP) {
            store.update({ it.copy(phase = Phase.IDLE, message = "录音已停止并保存。") }, true)
            store.event("IDLE")
        } else startService(Intent(this, RecordingService::class.java).setAction(action))
    }

    private fun export(uri: Uri, file: File, csv: Boolean) {
        ioBusy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        if (csv) file.inputStream().use { it.copyTo(out) }
                        else EncryptedAudio.export(file, store.key, out)
                    }
                }
            }
            ioBusy = false
            feedback = result.fold({ "已导出${if (csv) "诊断日志" else "标准音频文件"}。" }, { "导出失败：${it.message}" })
        }
    }

    private fun exportBundle(uri: Uri) {
        val files = store.segments.value.map { it.file }
        ioBusy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (diagnostics, journal) = store.exportLogs()
                    contentResolver.openOutputStream(uri, "wt")!!.use {
                        AnalysisBundle.write(it, files, store.key, diagnostics, journal)
                    }
                }.onFailure {
                    // This document was just created by this export; remove an unusable partial archive.
                    runCatching { contentResolver.delete(uri, null, null) }
                }
            }
            ioBusy = false
            feedback = result.fold({ "已导出全部已保存录音、原始时间线与采集日志。远端可据此对齐转写时间；当前尚未生成文本。" },
                { "分析包导出失败：${it.message}" })
        }
    }

    private fun stopPlayback() {
        player?.release(); player = null
        playFile?.delete(); playFile = null
        playing = null
    }

    private fun play(segment: Segment) {
        if (playing == segment.file.name) { stopPlayback(); return }
        stopPlayback(); ioBusy = true
        lifecycleScope.launch {
            val temp = File(cacheDir, "play-${segment.file.name}.${if (segment.info.header.codec == "opus") "opus" else "wav"}")
            playFile = temp
            val result = withContext(Dispatchers.IO) {
                runCatching { temp.outputStream().use { EncryptedAudio.export(segment.file, store.key, it) } }
            }
            ioBusy = false
            result.onSuccess {
                runCatching {
                    player = MediaPlayer().apply {
                        setDataSource(temp.absolutePath)
                        setOnCompletionListener { stopPlayback() }
                        setOnErrorListener { _, _, _ -> feedback = "播放失败"; stopPlayback(); true }
                        prepare(); start()
                    }
                    playing = segment.file.name
                }.onFailure { feedback = "播放失败：${it.message}"; stopPlayback() }
            }.onFailure { feedback = "无法解密音频：${it.message}"; stopPlayback() }
        }
    }

    @Composable private fun Screen() {
        val state by store.state.collectAsStateWithLifecycle()
        val segments by store.segments.collectAsStateWithLifecycle()
        val settings by store.settings.collectAsStateWithLifecycle()
        val serverAddress by serverPreferences.address.collectAsStateWithLifecycle()
        val upload by app.uploads.progress.collectAsStateWithLifecycle()
        val receipts by app.uploads.receipts.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(0) }
        var logsRefresh by remember { mutableIntStateOf(0) }
        var recentLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
        LaunchedEffect(tab, logsRefresh) {
            if (tab == 3) while (true) {
                recentLogs = withContext(Dispatchers.IO) {
                    runCatching { app.logs.recent() }.getOrElse {
                        listOf(LogEntry("read-error", System.currentTimeMillis(), "ERROR", "日志", "日志读取失败：${it.message}"))
                    }
                }
                delay(3000)
            }
        }
        var consent by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<Segment?>(null) }
        var deleteAll by remember { mutableStateOf(false) }
        val active = state.phase in setOf(Phase.STARTING, Phase.RECORDING, Phase.STOPPING)
        val totalBytes = segments.sumOf { it.file.length() }
        val totalMs = segments.sumOf { it.info.durationMs }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("录音", "记录", "设置", "日志").forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("◉", "≡", "⚙", "☷")[index], fontSize = 24.sp) }, label = { Text(name) })
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 28.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("DayTrace", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("把声音留在时间里", color = Color(0xFF788179), fontSize = 13.sp)
                        }
                        Surface(color = Color(0xFFE3EDE3), shape = RoundedCornerShape(20.dp)) {
                            Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 11.sp, color = Color(0xFF316C58))
                        }
                    }
                }
                if (tab == 0) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF183D32)), shape = RoundedCornerShape(28.dp)) {
                            Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(when (state.phase) { Phase.RECORDING -> if (state.silenced) "●  麦克风被静音" else "●  正在记录"; Phase.PAUSED -> "Ⅱ  已暂停"; Phase.STARTING -> "正在启动"; Phase.STOPPING -> "正在保存"; Phase.INTERRUPTED -> "录音已中断"; else -> "今天，从此刻开始" }, color = Color(0xFFB8DCC5), fontSize = 15.sp)
                                Text(formatDuration(state.capturedMs), fontFamily = FontFamily.Monospace, fontSize = 46.sp, color = Color.White)
                                LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Color(0xFFA7D9B7), trackColor = Color(0xFF3A5C4D))
                                Text(state.message, color = Color(0xFFD4E5DA), fontSize = 13.sp, lineHeight = 21.sp)
                                Text(if (!settings.vadEnabled) "连续保存 · Opus ${settings.bitrate / 1000} kbps"
                                    else if (state.phase != Phase.RECORDING) "仅保存人声 · Opus ${settings.bitrate / 1000} kbps"
                                    else if (state.vadSpeech) "检测到人声 · 正在保留" else "等待人声 · 静音不保存",
                                    color = Color(0xFFB8DCC5), fontSize = 12.sp)
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                if (state.phase == Phase.RECORDING) command(RecordingService.PAUSE)
                                else if (getPreferences(MODE_PRIVATE).getBoolean("consent", false)) requestStart()
                                else consent = true
                            }, enabled = !ioBusy && !upload.busy && state.phase !in setOf(Phase.STARTING, Phase.STOPPING), modifier = Modifier.weight(1f).height(56.dp)) {
                                Text(when (state.phase) { Phase.RECORDING -> "暂停录音"; Phase.PAUSED -> "继续录音"; else -> "开始录音" })
                            }
                            OutlinedButton(onClick = { command(RecordingService.STOP) }, enabled = state.phase in setOf(Phase.RECORDING, Phase.PAUSED), modifier = Modifier.height(56.dp)) { Text("停止") }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metric("已存音频", formatDuration(totalMs), Modifier.weight(1f))
                            Metric("本地占用", "%.1f MB".format(totalBytes / 1048576.0), Modifier.weight(1f))
                        }
                    }
                    item {
                        InfoCard("锁屏，也继续记录", "录音由独立前台服务运行，通知栏可暂停或停止。建议在系统电池设置中将 DayTrace 设为「无限制」，并在最近任务中锁定应用。")
                    }
                    item { Text("本次采集 ${formatDuration(state.capturedMs)} · 保留 ${formatDuration(state.retainedMs)}\n${if (settings.vadEnabled) "检测人声 ${formatDuration(state.voiceMs)} · WebRTC VAD" else "连续录音 · 不过滤静音"}\n16 kHz / 单声道 · Opus ${settings.bitrate / 1000} kbps\n当前配置最长 ${settings.vadConfig.maximumSegmentMs} ms 一段\nAES-GCM 本地加密 · 中断 ${state.interruptions} 次", color = Color(0xFF788179), fontSize = 12.sp, lineHeight = 21.sp) }
                } else if (tab == 1) {
                    item { Text("声音时间线", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                    item { Text("${segments.size} 段已保存。新录音按当前配置最长 ${settings.vadConfig.maximumSegmentMs} ms 分段；人声结束、暂停或停止也会保存有效尾段。", color = Color(0xFF788179), fontSize = 13.sp) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("数据上传", fontWeight = FontWeight.SemiBold)
                                Text(if (serverAddress.isBlank()) "请先到设置页保存服务地址。" else "服务：$serverAddress", fontSize = 12.sp)
                                Button(enabled = !active && !ioBusy && !upload.busy && serverAddress.isNotBlank(), onClick = {
                                    app.uploads.start(serverAddress, segments.toList())
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (upload.busy) "同步中…" else "上传待同步数据 / 重试失败项") }
                                if (upload.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                if (upload.message.isNotBlank()) Text("${upload.message}\n目标：${upload.target}", fontSize = 12.sp)
                                Text("暂停后上传录音及采集事件；已成功的片段会跳过。上传期间请保持应用打开，详细结果可在日志页查看。", fontSize = 12.sp)
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = { bundleExport.launch("DayTrace-analysis-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip") },
                            enabled = !active && !ioBusy && segments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (ioBusy) "正在处理…" else "导出分析包（录音 + 时间线）")
                        }
                        Text("用于远端转写和事件回溯；含全部已保存录音、时间信息和采集日志。请先暂停。", fontSize = 12.sp, color = Color(0xFF788179))
                    }
                    if (segments.isEmpty()) item { InfoCard("还没有录音", "开始录音后，声音会按时间保存在这里。手动上传前，音频仅保存在本机。") }
                    items(segments, key = { it.file.name }) { segment ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(SimpleDateFormat("MM月dd日  HH:mm:ss", Locale.CHINA).format(Date(segment.info.header.startedAt)), fontWeight = FontWeight.SemiBold)
                                Text("${formatDuration(segment.info.durationMs)}  ·  %.2f MB  ·  ${segment.info.header.codec.uppercase()}${if (segment.info.header.vad) " / 人声" else " / 连续"}".format(segment.file.length() / 1048576.0), color = Color(0xFF788179), fontSize = 12.sp)
                                Row {
                                    TextButton(onClick = { play(segment) }, enabled = !active && !ioBusy) { Text(if (playing == segment.file.name) "停止试听" else "试听") }
                                    TextButton(onClick = {
                                        pendingExport = segment.file.name
                                        val name = "DayTrace-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(segment.info.header.startedAt))}"
                                        if (segment.info.header.codec == "opus") opusExport.launch("$name.opus") else wavExport.launch("$name.wav")
                                    }, enabled = !ioBusy) { Text(if (segment.info.header.codec == "opus") "导出 Opus" else "导出 WAV") }
                                    TextButton(onClick = { deleting = segment }, enabled = !ioBusy && !upload.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                                val receipt = if (serverAddress.isBlank()) null else receipts[UploadLedger.key(serverAddress, segment.info.header.segmentId)]
                                Text(receipt?.message ?: "尚未上传到当前服务", fontSize = 12.sp,
                                    color = if (receipt?.status in listOf("failed", "interrupted")) MaterialTheme.colorScheme.error else Color(0xFF788179))
                                TextButton(enabled = !active && !ioBusy && !upload.busy && serverAddress.isNotBlank() && receipt?.status != "completed",
                                    onClick = { app.uploads.start(serverAddress, listOf(segment)) }) {
                                    Text(if (receipt?.status in listOf("failed", "interrupted")) "重试上传" else "上传此片段")
                                }
                            }
                        }
                    }
                } else if (tab == 2) {
                    item { Text("服务设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                    item {
                        ServerSettings(serverAddress, enabled = !upload.busy, onTest = { address, result ->
                            lifecycleScope.launch(Dispatchers.IO) { app.logs.add("连接测试", "$address：${result.message}", !result.success) }
                        }) {
                            serverPreferences.save(it)
                            withContext(Dispatchers.IO) { app.logs.add("服务设置", "服务地址已保存：${ServerAddress.normalize(it)}") }
                        }
                    }
                    item { Text("录音设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("只保存人声", fontWeight = FontWeight.SemiBold)
                                    Switch(checked = settings.vadEnabled, onCheckedChange = { store.configure(settings.copy(vadEnabled = it)) }, enabled = !active)
                                }
                                Text("WebRTC VAD · 前置 ${settings.vadConfig.preRollMs} ms\n静音 ${settings.vadConfig.silenceMs} ms 结束 · 最短累计人声 ${settings.vadConfig.minimumSpeechMs} ms\n关闭后连续保存全部声音。请先暂停再修改。", fontSize = 12.sp, lineHeight = 20.sp)
                                Text("Opus 比特率", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(12, 16, 24, 32).forEach { kbps ->
                                        FilterChip(selected = settings.bitrate == kbps * 1000, onClick = { store.configure(settings.copy(bitrate = kbps * 1000)) },
                                            enabled = !active, label = { Text("${kbps}k") })
                                    }
                                }
                                Text("16 kbps 连续保存约 8 MB/小时；人声模式随实际说话时长减少。", fontSize = 12.sp)
                                Text("人声筛选强度", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("宽松", "适中", "严格", "最严").forEachIndexed { mode, label ->
                                        FilterChip(selected = settings.vadMode == mode, onClick = { store.configure(settings.copy(vadMode = mode)) },
                                            enabled = !active, label = { Text(label, fontSize = 12.sp) })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        TimingSettings(settings.vadConfig, active) { config ->
                            store.configure(store.settings.value.copy(vadConfig = config))
                            feedback = "时长已保存，下次开始或继续录音时生效。"
                        }
                    }
                    item { InfoCard("本地存储", "音频使用 Android Keystore 密钥加密。最多保存 4 GB，剩余空间低于 128 MB 时停止并提示，不会自动覆盖旧录音。卸载应用会删除录音和密钥，请先导出。导出的 Opus / WAV 和分析包均未加密。") }
                    item { InfoCard("当前版本", "已启用 Opus 压缩、WebRTC VAD 和手动上传。录音上传后由服务端转写；应用尚未展示转写文本或 AI 总结。人声检测可能误判，重要记录可关闭筛选。") }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) { Text("打开系统应用设置") }
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("打开电池优化设置") }
                            OutlinedButton(onClick = { csvExport.launch("DayTrace-diagnostics.csv") }, enabled = store.diagnostics.exists() && !ioBusy, modifier = Modifier.fillMaxWidth()) { Text("导出诊断 CSV") }
                            TextButton(onClick = { deleteAll = true }, enabled = !active && !ioBusy && !upload.busy && segments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("删除全部录音", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item { Text("系统电池优化豁免：${if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "已开启" else "未开启"}\n系统强行停止、重启或撤销权限会中断录音。应用会提示手动重新开始，不会绕过系统限制。", fontSize = 12.sp, color = Color(0xFF788179), lineHeight = 21.sp) }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("最近日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { logsRefresh++ }) { Text("刷新") }
                        }
                        Text("最近 200 条，按时间倒序，每 3 秒刷新。包括采集事件、连接测试和上传结果；完整采集日志仍保留在分析包中。", fontSize = 12.sp)
                    }
                    if (recentLogs.isEmpty()) item { Text("暂无日志") }
                    items(recentLogs, key = { it.id }) { LogCard(it) }
                }
            }
        }
        if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开始环境录音") },
            text = { Text("DayTrace 会在锁屏后继续使用麦克风，并显示持续录音通知。请确认录音符合当地规定，并获得涉及人员的知情同意。音频加密保存在本机，您可以随时停止和删除。") },
            confirmButton = { TextButton(onClick = { consent = false; getPreferences(MODE_PRIVATE).edit().putBoolean("consent", true).apply(); requestStart() }) { Text("同意并开始") } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } })
        if (feedback != null) AlertDialog(onDismissRequest = { feedback = null }, title = { Text("DayTrace") }, text = { Text(feedback!!) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("知道了") } })
        if (deleting != null || deleteAll) AlertDialog(onDismissRequest = { deleting = null; deleteAll = false }, title = { Text(if (deleteAll) "删除全部录音？" else "删除这段录音？") }, text = { Text("删除本地录音后无法恢复。已上传到服务端或导出的文件不会删除；删除事件将在下次同步时上传。") },
            confirmButton = { TextButton(onClick = {
                stopPlayback()
                runCatching {
                    if (deleteAll) segments.toList().forEach { store.delete(it) } else deleting?.let { store.delete(it) }
                }.onFailure { feedback = it.message }
                deleting = null; deleteAll = false
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null; deleteAll = false }) { Text("取消") } })
    }

    @Composable private fun Metric(label: String, value: String, modifier: Modifier) {
        Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = Color(0xFF788179), fontSize = 12.sp)
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    @Composable private fun InfoCard(title: String, body: String) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE9EDE3)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, fontSize = 13.sp, lineHeight = 22.sp, color = Color(0xFF626F62))
            }
        }
    }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }
}
