package com.daytrace.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TimingSettings(config: VadConfig, active: Boolean, onSave: (VadConfig) -> Unit) {
    var preRoll by rememberSaveable(config.preRollMs) { mutableStateOf(config.preRollMs.toString()) }
    var silence by rememberSaveable(config.silenceMs) { mutableStateOf(config.silenceMs.toString()) }
    var minimum by rememberSaveable(config.minimumSpeechMs) { mutableStateOf(config.minimumSpeechMs.toString()) }
    var maximum by rememberSaveable(config.maximumSegmentMs) { mutableStateOf(config.maximumSegmentMs.toString()) }
    val values = listOf(preRoll, silence, minimum, maximum).map { it.toIntOrNull() }
    val error = VadConfig.validationError(values[0], values[1], values[2], values[3])
    val changed = values != listOf(config.preRollMs, config.silenceMs, config.minimumSpeechMs, config.maximumSegmentMs)
    val focus = LocalFocusManager.current

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("分段时长（毫秒）", fontWeight = FontWeight.SemiBold)
            Text("20 ms 步进；暂停后保存，下次开始或继续录音时生效。前 3 项用于人声模式，最大片段时长也用于连续录音。",
                fontSize = 12.sp, lineHeight = 20.sp)
            TimingField("前置缓存", preRoll, "保留开口前的声音，0–5000 ms。", !active) { preRoll = it }
            TimingField("静音结束间隔", silence, "连续静音达到此时长后结束片段，20–5000 ms。", !active) { silence = it }
            TimingField("最短累计人声", minimum, "累计人声不足此时长不保存；调大可能漏掉简短回应，20–2000 ms。", !active) { minimum = it }
            TimingField("最大片段时长", maximum, "达到上限后分段并继续保存，20–300000 ms。", !active) { maximum = it }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (active) Text("正在录音，请先暂停再修改。", fontSize = 12.sp)
            else if (changed && error == null) Text("有未保存的修改。", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !active && error == null && changed, onClick = {
                    focus.clearFocus()
                    onSave(VadConfig(values[0]!!, values[1]!!, values[2]!!, values[3]!!))
                }) { Text("保存时长") }
                TextButton(enabled = !active, onClick = {
                    val defaults = VadConfig()
                    preRoll = defaults.preRollMs.toString(); silence = defaults.silenceMs.toString()
                    minimum = defaults.minimumSpeechMs.toString(); maximum = defaults.maximumSegmentMs.toString()
                }) { Text("填入默认值") }
            }
        }
    }
}

@Composable
private fun TimingField(label: String, value: String, hint: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text("$label（ms）") },
        supportingText = { Text(hint) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
