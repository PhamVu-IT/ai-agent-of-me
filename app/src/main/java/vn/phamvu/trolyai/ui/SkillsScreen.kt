package vn.phamvu.trolyai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.phamvu.trolyai.ChatViewModel
import vn.phamvu.trolyai.mcp.McpServer
import vn.phamvu.trolyai.skills.Skill

/**
 * Màn 🧩 Skill & MCP, như "Skill Manager" và "Manage MCP servers" của Google AI Edge Gallery:
 * bật / tắt skill, thêm skill từ đường link hay thư mục trong máy, thêm máy chủ MCP và chọn công cụ được dùng.
 */
@Composable
fun SkillsScreen(vm: ChatViewModel, onImportFolder: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.refreshTools() }
    var addUrl by remember { mutableStateOf(false) }
    var addMcp by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text("🧩 Skill & MCP", style = MaterialTheme.typography.titleLarge)
            }
            Text("Trợ lý tự chọn skill / công cụ khi cần. Skill cần mạng (Wikipedia, bản đồ, QR) chỉ chạy khi có Internet; " +
                "trò chuyện thường vẫn offline. Mô hình nhỏ chỉ có 4096 token, nên chỉ bật những gì bạn dùng.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.toolsNote.value.isNotEmpty()) Text(vm.toolsNote.value, Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium)
            if (vm.toolsBusy.value) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Skill", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { addUrl = true }) { Text("＋ Từ link") }
                TextButton(onClick = onImportFolder) { Text("＋ Từ máy") }
            }
        }
        items(vm.skillList, key = { "s:" + it.name }) { s -> SkillCard(vm, s) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Máy chủ MCP", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { addMcp = true }) { Text("＋ Thêm") }
            }
            if (vm.mcpServers.isEmpty()) Text("Chưa có. Thêm đường link máy chủ MCP dạng Streamable HTTP (thường kết thúc bằng /mcp).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.mcpServers, key = { "m:" + it.url }) { s -> McpCard(vm, s) }
    }

    if (addUrl) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUrl = false },
            title = { Text("Thêm skill từ link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Link tới thư mục skill (chứa SKILL.md), ví dụ https://ten.github.io/skills/my-skill",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://…") })
                }
            },
            confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { vm.addSkillFromUrl(url); addUrl = false }) { Text("Thêm") } },
            dismissButton = { TextButton(onClick = { addUrl = false }) { Text("Huỷ") } },
        )
    }
    if (addMcp) {
        var url by remember { mutableStateOf("") }
        var header by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addMcp = false },
            title = { Text("Thêm máy chủ MCP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Link máy chủ") },
                        placeholder = { Text("https://…/mcp") })
                    OutlinedTextField(header, { header = it }, singleLine = true, label = { Text("Tên header (tuỳ chọn)") },
                        placeholder = { Text("Authorization hoặc X-Goog-Api-Key") })
                    OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Giá trị header (tuỳ chọn)") })
                    Text("Header được lưu trong bộ nhớ riêng của app, chỉ gửi tới máy chủ này.",
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = {
                    vm.connectMcp(McpServer(url.trim(), header.trim(), value.trim()))
                    addMcp = false
                }) { Text("Kết nối") }
            },
            dismissButton = { TextButton(onClick = { addMcp = false }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun SkillCard(vm: ChatViewModel, s: Skill) {
    var secret by remember(s.name) { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (s.builtIn) "Có sẵn (từ Google AI Edge Gallery)" else "Bạn thêm",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(checked = vm.tools.skills.isEnabled(s.name), onCheckedChange = { vm.setSkillEnabled(s.name, it) })
            }
            if (s.requireSecret) {
                Text(s.secretDescription.ifBlank { "Skill này cần khoá bí mật (API key)." },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(secret, { secret = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text(if (vm.tools.skills.secret(s.name).isNotEmpty()) "Đã lưu (nhập để đổi)" else "Khoá") })
                    TextButton(enabled = secret.isNotBlank(), onClick = { vm.setSkillSecret(s.name, secret); secret = "" }) { Text("Lưu") }
                }
            }
            if (!s.builtIn) TextButton(onClick = { vm.deleteSkill(s) }) { Text("Xoá skill") }
        }
    }
}

@Composable
private fun McpCard(vm: ChatViewModel, s: McpServer) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.titleSmall)
                    Text(s.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.enabled, onCheckedChange = { vm.setMcpEnabled(s.url, it) })
            }
            s.tools.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = t.enabled, onCheckedChange = { vm.setMcpToolEnabled(s.url, t.name, it) })
                }
            }
            Row {
                TextButton(onClick = { vm.connectMcp(s) }) { Text("Kết nối lại") }
                TextButton(onClick = { vm.removeMcp(s.url) }) { Text("Xoá") }
            }
        }
    }
}
