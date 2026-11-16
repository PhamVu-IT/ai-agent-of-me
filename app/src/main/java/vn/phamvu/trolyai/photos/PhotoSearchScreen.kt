package vn.phamvu.trolyai.photos

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Màn 🖼️ Tìm ảnh bằng chữ, offline: "con mèo trên ghế", "bãi biển năm 2023", "hoá đơn tiền điện".
 * [onAskPermission] xin quyền đọc ảnh (MainActivity giữ trình xin quyền).
 */
@Composable
fun PhotoSearchScreen(vm: PhotoSearchViewModel, onAskPermission: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val view = LocalView.current
    DisposableEffect(vm.indexing.value) {
        view.keepScreenOn = vm.indexing.value
        onDispose { view.keepScreenOn = false }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(vm, onAskPermission, onBack) }
        items(vm.results, key = { it.entry.id }) { hit -> Thumb(hit) }
        if (vm.searched.value && vm.results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Không thấy ảnh nào khớp. Thử chữ khác, hoặc đọc thêm ảnh ở trên.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun Header(vm: PhotoSearchViewModel, onAskPermission: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("🖼️ Tìm ảnh", style = MaterialTheme.typography.titleLarge)
        }
        Text("AI xem từng ảnh một lần và ghi lại mô tả ngắn, sau đó bạn tìm bằng lời như \"con mèo\", " +
            "\"bãi biển năm 2023\", \"hoá đơn\". Mọi thứ ở trong máy, ảnh không gửi đi đâu.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!vm.permitted.value) {
            Button(onClick = onAskPermission) { Text("Cho phép đọc ảnh") }
            return@Column
        }

        // Tiến độ đọc thư viện
        val total = vm.total.intValue
        val done = vm.captioned.intValue
        Text("Đã đọc $done / $total ảnh", style = MaterialTheme.typography.titleSmall)
        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.indexing.value) OutlinedButton(onClick = vm::stopIndex) { Text("■ Dừng") }
            else Button(onClick = vm::startIndex, enabled = done < total) {
                Text(if (done == 0) "Bắt đầu đọc ảnh" else "Đọc tiếp")
            }
            if (!vm.indexing.value && done > 0) TextButton(onClick = vm::clearIndex) { Text("Đọc lại từ đầu") }
        }
        if (vm.indexing.value && total - done > 0) Text(
            "Mỗi ảnh mất vài giây. Có thể dừng bất cứ lúc nào, lần sau đọc tiếp.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.note.value.isNotEmpty()) Text(vm.note.value, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tự đọc ảnh mới khi đang sạc", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = vm.autoIndex.value, onCheckedChange = vm::setAuto)
        }

        // Ô tìm
        OutlinedTextField(
            value = vm.query.value,
            onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Tìm: con mèo, sinh nhật, bãi biển năm 2023…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            trailingIcon = { TextButton(onClick = vm::search, enabled = done > 0) { Text("Tìm") } },
        )
        if (done == 0) Text("Hãy đọc ảnh trước rồi mới tìm được.", style = MaterialTheme.typography.labelSmall)
        else if (vm.searched.value && vm.results.isNotEmpty()) Text("${vm.results.size} ảnh", style = MaterialTheme.typography.labelMedium)
    }
}

/** Ảnh nhỏ; bấm để mở bằng app xem ảnh, giữ lâu để xem mô tả AI đã viết. */
@Composable
private fun Thumb(hit: PhotoHit) {
    val ctx = LocalContext.current
    var showCaption by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, hit.entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(hit.entry.uri, Size(320, 320), null)
                else MediaStore.Images.Thumbnails.getThumbnail( // cách cũ cho Android 9 trở xuống
                    ctx.contentResolver, hit.entry.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }.getOrNull()
        }
    }
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable {
                if (showCaption) showCaption = false
                else runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(hit.entry.uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            },
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        TextButton(onClick = { showCaption = !showCaption }, Modifier.align(Alignment.TopEnd).size(36.dp),
            contentPadding = PaddingValues(0.dp)) { Text("ⓘ") }
        if (showCaption) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
            Text(hit.entry.caption, Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
