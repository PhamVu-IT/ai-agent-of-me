package vn.phamvu.trolyai.teacher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Màn 📸 Học ngoại ngữ qua camera. [onSpeak] đọc to một câu bằng giọng của ngôn ngữ đang học.
 */
@Composable
fun LanguageTeacherScreen(
    vm: LanguageTeacherViewModel,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onSpeak: (String, Locale) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { if (vm.quiz.value != null) vm.stopQuiz() else onBack() }
    if (vm.quiz.value != null) { QuizView(vm, onSpeak); return }
    val l = vm.lang.value

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text("📸 Học ngoại ngữ", style = MaterialTheme.typography.titleLarge)
            }
            Text("Chụp đồ vật quanh bạn, AI dạy tên của chúng kèm phiên âm, nghĩa và câu ví dụ. Bấm 🔊 để nghe đọc.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudyLang.entries.forEach { s ->
                    FilterChip(selected = l == s, onClick = { vm.setLang(s) }, label = { Text(s.label) }, enabled = !vm.busy.value)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTakePhoto, enabled = !vm.busy.value) { Text("📷 Chụp ảnh") }
                OutlinedButton(onClick = onPickPhoto, enabled = !vm.busy.value) { Text("🖼️ Chọn ảnh") }
                if (vm.saved.isNotEmpty()) OutlinedButton(onClick = vm::nextQuiz) { Text("🃏 Ôn ${vm.saved.size} từ") }
            }
        }
        vm.photo.value?.let { bmp ->
            item {
                Image(bmp.asImageBitmap(), null,
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop)
            }
        }
        if (vm.busy.value) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("AI đang xem ảnh…", Modifier.padding(start = 10.dp))
            }
        }
        if (vm.error.value.isNotEmpty()) item {
            Text(vm.error.value, color = MaterialTheme.colorScheme.error)
        }
        items(vm.items, key = { "n:" + it.word }) { v -> WordCard(v, vm.isSaved(v), { vm.toggleSave(v) }, onSpeak) }

        if (vm.saved.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("📒 Sổ từ (${vm.saved.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(vm.saved, key = { "s:" + it.lang + it.word }) { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${v.word}  ·  ${v.meaning}", style = MaterialTheme.typography.bodyMedium)
                        if (v.pronunciation.isNotEmpty()) Text(v.pronunciation, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onSpeak(v.word, v.lang.locale) }) { Text("🔊") }
                    TextButton(onClick = { vm.toggleSave(v) }) { Text("✕") }
                }
            }
        }
    }
}

@Composable
private fun WordCard(v: VocabItem, saved: Boolean, onSave: () -> Unit, onSpeak: (String, Locale) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.word, style = MaterialTheme.typography.headlineSmall)
                    if (v.pronunciation.isNotEmpty()) Text(v.pronunciation, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { onSpeak(v.word, v.lang.locale) }) { Text("🔊") }
                TextButton(onClick = onSave) { Text(if (saved) "★" else "☆") }
            }
            Text(v.meaning, style = MaterialTheme.typography.titleSmall)
            if (v.example.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.example, style = MaterialTheme.typography.bodyMedium)
                    if (v.exampleVi.isNotEmpty()) Text(v.exampleVi, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onSpeak(v.example, v.lang.locale) }) { Text("🔊") }
            }
        }
    }
}

/** Thẻ đố: hiện nghĩa tiếng Việt, bạn gõ từ bằng ngôn ngữ đang học. */
@Composable
private fun QuizView(vm: LanguageTeacherViewModel, onSpeak: (String, Locale) -> Unit) {
    val q = vm.quiz.value ?: return
    var answer by remember(q) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::stopQuiz) { Text("←") }
            Text("🃏 Ôn từ", style = MaterialTheme.typography.titleLarge)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${q.lang.label}: từ nào nghĩa là", style = MaterialTheme.typography.labelLarge)
                Text(q.meaning, style = MaterialTheme.typography.headlineSmall)
                if (vm.quizReveal.value) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.word, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onSpeak(q.word, q.lang.locale) }) { Text("🔊") }
                    }
                    if (q.pronunciation.isNotEmpty()) Text(q.pronunciation, color = MaterialTheme.colorScheme.primary)
                    if (q.example.isNotEmpty()) Text(q.example, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Gõ từ bằng ${q.lang.prompt}") })
        if (vm.quizNote.value.isNotEmpty()) Text(vm.quizNote.value, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.checkAnswer(answer) }, enabled = answer.isNotBlank()) { Text("Kiểm tra") }
            OutlinedButton(onClick = { vm.quizReveal.value = true; onSpeak(q.word, q.lang.locale) }) { Text("Xem đáp án") }
            TextButton(onClick = vm::nextQuiz) { Text("Từ khác →") }
        }
    }
}
