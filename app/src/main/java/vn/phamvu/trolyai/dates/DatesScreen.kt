package vn.phamvu.trolyai.dates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.phamvu.trolyai.App
import vn.phamvu.trolyai.data.ImportantDate
import vn.phamvu.trolyai.data.Setting
import java.time.LocalDate

/**
 * Màn 📅 Ngày quan trọng: xem các ngày giỗ, sinh nhật, kỷ niệm trợ lý đang nhớ (xếp theo ngày gần nhất),
 * thêm, sửa, xoá, và chọn giờ gửi bản tin buổi sáng.
 */
@Composable
fun DatesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val db = (ctx.applicationContext as App).db
    val dates = remember { Dates(db) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Dates.Upcoming>>(emptyList()) }
    var editing by remember { mutableStateOf<ImportantDate?>(null) }
    var deleting by remember { mutableStateOf<ImportantDate?>(null) }
    var briefHour by remember { mutableIntStateOf(7) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        list = dates.upcoming()
        briefHour = db.settings().get(Dates.BRIEF_HOUR)?.toIntOrNull() ?: 7
    }
    fun setHour(h: Int) {
        briefHour = h.coerceIn(5, 10)
        scope.launch {
            db.settings().put(Setting(Dates.BRIEF_HOUR, briefHour.toString()))
            DailyBrief.scheduleNext(ctx, db)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text("📅 Ngày quan trọng", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = {
                    editing = ImportantDate(title = "", day = 1, month = 1, lunar = true, kind = "gio",
                        remindDaysBefore = Dates.defaultLead("gio"))
                }) { Text("+ Thêm") }
            }
        }
        item {
            Text("Năm nào trợ lý cũng tự nhắc, không cần bạn hỏi: trong bản tin buổi sáng (trước vài ngày, trước 3 ngày, " +
                "hôm trước và đúng ngày), và nói trước khi bạn mở chat. Kể trong chat như \"giỗ ông nội mùng 10 tháng 3 âm\" " +
                "là trợ lý tự ghi vào đây.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("☀️ Bản tin buổi sáng", style = MaterialTheme.typography.titleSmall)
                        Text("Gửi lúc $briefHour:00 nếu hôm đó có việc cần nhắc", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { setHour(briefHour - 1) }) { Text("−") }
                    Text("${briefHour}h")
                    TextButton(onClick = { setHour(briefHour + 1) }) { Text("+") }
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                scope.launch { note = DailyBrief.build(db) ?: "Hôm nay không có gì cần nhắc." }
            }) { Text("Xem thử bản tin hôm nay") }
            if (note.isNotEmpty()) Card(Modifier.padding(top = 8.dp)) { Text(note, Modifier.padding(12.dp)) }
        }
        if (list.isEmpty()) item {
            Text("Chưa có ngày nào. Bấm \"+ Thêm\" hoặc kể cho trợ lý trong chat.", Modifier.padding(top = 16.dp))
        }
        items(list, key = { it.d.id }) { u -> DateRow(u, onEdit = { editing = u.d }, onDelete = { deleting = u.d }) }
    }

    editing?.let { d ->
        EditDateDialog(d, onDismiss = { editing = null }, onSave = { s ->
            scope.launch {
                if (s.id == 0L) {
                    val added = dates.add(s.title, s.day, s.month, s.year, s.lunar, s.kind, "tu_them", s.remindDaysBefore)
                    if (added == null) note = "\"${s.title}\" trùng với một ngày đã có trong sổ."
                } else {
                    db.dates().update(s)
                }
                editing = null
                refresh++
            }
        })
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Xoá \"${d.title}\"?") },
            text = { Text("Trợ lý sẽ không nhắc ngày này nữa và không tự thêm lại.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { dates.delete(d); deleting = null; refresh++ } }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Thôi") } },
        )
    }
}

@Composable
private fun DateRow(u: Dates.Upcoming, onEdit: () -> Unit, onDelete: () -> Unit) {
    val d = u.d
    val icon = when (d.kind) { "gio" -> "🕯️"; "sinh_nhat" -> "🎂"; "ky_niem" -> "💞"; else -> "📅" }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$icon ${d.title}", style = MaterialTheme.typography.titleSmall)
                val years = Anniversary.yearsLabel(d.kind, u.next.years)?.let { " · $it" } ?: ""
                Text(Anniversary.label(d.day, d.month, d.lunar).replaceFirstChar { it.uppercase() } + years,
                    style = MaterialTheme.typography.bodySmall)
                Text("Lần tới: ${Anniversary.solarLabel(u.next.date)} (${Anniversary.whenLabel(u.next.daysLeft)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (u.next.daysLeft <= d.remindDaysBefore) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.source != "tu_them") Text(
                    if (d.source == "ai") "Trợ lý ghi khi bạn kể" else "Trợ lý tìm thấy trong lời kể cũ",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDelete) { Text("Xoá") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDateDialog(d: ImportantDate, onDismiss: () -> Unit, onSave: (ImportantDate) -> Unit) {
    var title by remember { mutableStateOf(d.title) }
    var day by remember { mutableStateOf(d.day.toString()) }
    var month by remember { mutableStateOf(d.month.toString()) }
    var year by remember { mutableStateOf(d.year?.toString() ?: "") }
    var lunar by remember { mutableStateOf(d.lunar) }
    var kind by remember { mutableStateOf(d.kind) }
    var lead by remember { mutableStateOf(d.remindDaysBefore.toString()) }

    val dd = day.toIntOrNull() ?: 0
    val mm = month.toIntOrNull() ?: 0
    val valid = title.isNotBlank() && mm in 1..12 && dd in 1..(if (lunar) 30 else 31)
    val preview = if (valid) Anniversary.next(dd, mm, lunar, year.toIntOrNull(), LocalDate.now()).let {
        "Lần tới: ${Anniversary.solarLabel(it.date)} (${Anniversary.whenLabel(it.daysLeft)})"
    } else "Nhập tên, ngày (1–${if (lunar) 30 else 31}) và tháng (1–12)"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.id == 0L) "Thêm ngày quan trọng" else "Sửa ngày quan trọng") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dates.kinds.forEach { (k, label) ->
                        FilterChip(selected = kind == k, label = { Text(label) }, onClick = {
                            // Thêm mới: chọn giỗ thì mặc định âm lịch và nhắc trước một tuần
                            if (d.id == 0L && k != kind) { lunar = k == "gio"; lead = Dates.defaultLead(k).toString() }
                            kind = k
                        })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Tên, ví dụ Giỗ ông nội") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                        label = { Text("Ngày") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(month, { month = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                        label = { Text("Tháng") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Âm lịch", Modifier.weight(1f))
                    Switch(checked = lunar, onCheckedChange = { lunar = it })
                }
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (kind == "gio") "Năm mất (không bắt buộc)" else "Năm gốc (không bắt buộc)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(2) },
                    label = { Text("Nhắc trước bao nhiêu ngày") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(preview, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(d.copy(title = title.trim(), day = dd, month = mm, lunar = lunar, kind = kind,
                    year = year.toIntOrNull()?.takeIf { it in 1900..2200 },
                    remindDaysBefore = (lead.toIntOrNull() ?: Dates.defaultLead(kind)).coerceIn(1, 30)))
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}
