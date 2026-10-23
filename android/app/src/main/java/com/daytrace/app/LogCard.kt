package com.daytrace.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogCard(entry: LogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${entry.level} · ${entry.category}", fontWeight = FontWeight.SemiBold,
                color = if (entry.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(SimpleDateFormat("MM-dd HH:mm:ss.SSS z", Locale.CHINA).format(Date(entry.at)), fontSize = 12.sp)
            SelectionContainer {
                Text(entry.message, fontSize = 12.sp, maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看详情") }
        }
    }
}
