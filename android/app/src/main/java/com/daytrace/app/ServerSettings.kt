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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ServerSettings(savedAddress: String, enabled: Boolean = true, onTest: (String, ConnectionResult) -> Unit = { _, _ -> },
    onSave: suspend (String) -> Unit) {
    var address by rememberSaveable(savedAddress) { mutableStateOf(savedAddress) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConnectionResult?>(null) }
    val parsed = remember(address) { runCatching { ServerAddress.normalize(address) } }
    val error = parsed.exceptionOrNull()?.message
    val changed = parsed.getOrNull() != savedAddress
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("服务连接", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = address, onValueChange = { address = it; result = null },
                label = { Text("服务地址") }, placeholder = { Text("http://192.168.177.100:8000") },
                supportingText = { Text(if (address.isNotBlank() && error != null) error else "支持 HTTP / HTTPS，可填写 IP 和端口。") },
                isError = address.isNotBlank() && error != null, enabled = !busy && enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Text("测试当前输入地址，无需先保存。API 暂不认证；测试仅检查服务状态，不上传录音。", fontSize = 12.sp, lineHeight = 20.sp)
            if (changed && error == null) Text("有未保存的地址修改。", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && !busy && error == null && changed, onClick = {
                    focus.clearFocus()
                    busy = true
                    result = null
                    scope.launch {
                        try {
                            onSave(address)
                            result = ConnectionResult(true, "服务地址已保存。")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            result = ConnectionResult(false, e.message ?: "保存失败，请重试。")
                        } finally { busy = false }
                    }
                }) { Text(if (busy && !testing) "保存中…" else "保存地址") }
                OutlinedButton(enabled = enabled && !busy && error == null, onClick = {
                    focus.clearFocus()
                    busy = true
                    testing = true
                    result = null
                    scope.launch {
                        try { result = ServerConnection.test(address).also { onTest(address, it) } }
                        finally { busy = false; testing = false }
                    }
                }) { Text(if (testing) "测试中…" else "测试连通性") }
            }
            if (testing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            result?.let { Text(it.message, fontSize = 12.sp,
                color = if (it.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
}
