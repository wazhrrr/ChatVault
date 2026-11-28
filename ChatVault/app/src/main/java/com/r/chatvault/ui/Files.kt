package com.r.chatvault.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.r.chatvault.R
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

fun openExternal(context: android.content.Context, a: Attachment): Boolean {
    val p = a.path ?: return false
    return runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(p))
        val mime = a.mime.ifBlank { "*/*" }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }.isSuccess
}

fun iconFor(kind: String) = when (kind) {
    "image" -> Icons.Outlined.Image
    "video" -> Icons.Outlined.Movie
    "zip" -> Icons.Outlined.Archive
    "pdf" -> Icons.Outlined.PictureAsPdf
    "text" -> Icons.Outlined.Description
    else -> Icons.Outlined.InsertDriveFile
}

private fun decodeSample(path: String, req: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > req && sample < 16) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

@Composable
fun rememberThumb(path: String?, req: Int = 480): ImageBitmap? {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bmp = if (path == null) null else withContext(Dispatchers.IO) { decodeSample(path, req) }
    }
    return bmp
}

/** 附件缩略图行（会话内） */
@Composable
fun AttachmentStrip(items: List<Attachment>, onOpen: (Attachment) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.take(4).forEach { a ->
            val bmp = rememberThumb(a.path, 360)
            Box(
                Modifier
                    .size(if (a.kind == "image") 76.dp else 52.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clickable { onOpen(a) },
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bmp, contentDescription = a.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        iconFor(a.kind),
                        contentDescription = null,
                        tint = if (a.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FileRow(a: Attachment, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                iconFor(a.kind), null,
                tint = if (a.exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(19.dp)
            )
        }
        val missingText = stringResource(R.string.file_missing)
        val orphanText = stringResource(R.string.files_orphan_suffix)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(Fmt.bytes(a.size))
                    append(" · ")
                    append(if (a.exists) a.kind else missingText)
                    if (a.convId == null) append(orphanText)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (a.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ImageGrid(items: List<Attachment>, onOpen: (Attachment) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items, key = { it.id }) { a ->
            val bmp = rememberThumb(a.path, 420)
            Box(
                Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clickable { onOpen(a) },
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bmp, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(iconFor(a.kind), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun AttachmentViewer(a: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Fmt.bytes(a.size)} · ${a.kind}" + if (!a.exists) stringResource(R.string.files_missing_suffix) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { if (!openExternal(context, a)) onDismiss() }) {
                        Icon(Icons.Outlined.OpenInNew, stringResource(R.string.open_other_app))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.common_close)) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        !a.exists || a.path == null -> MissingFile(a)
                        a.kind == "image" -> ZoomImage(a.path)
                        a.kind == "video" -> VideoPane(a.path)
                        a.kind == "zip" -> ZipPane(a.path)
                        a.kind == "text" -> TextPane(a.path)
                        a.kind == "pdf" || a.kind == "doc" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(iconFor(a.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                            Text(stringResource(R.string.no_preview), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.open_with_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> TextPane(a.path)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingFile(a: Attachment) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp))
        Text(stringResource(R.string.no_entity), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.record_name, a.name, Fmt.bytes(a.size)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ZoomImage(path: String) {
    val bmp = rememberThumb(path, 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp == null) {
            Text(stringResource(R.string.image_decode_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(
                bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
            )
        }
    }
}

@Composable
private fun VideoPane(path: String) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(path)
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { mp: MediaPlayer -> mp.isLooping = false; start() }
            }
        },
        modifier = Modifier.fillMaxWidth().height(280.dp)
    )
}

@Composable
private fun ZipPane(path: String) {
    val entries = remember(path) {
        runCatching {
            ZipFile(File(path)).use { zf -> zf.entries().toList().filter { !it.isDirectory }.take(400).map { "${it.name}  ·  ${Fmt.bytes(it.size)}" } }
        }.getOrDefault(emptyList())
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.zip_entries, entries.size),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(entries) { e ->
            Text(e, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MonoStyle, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TextPane(path: String) {
    val total = remember(path) { File(path).length() }
    val tooLarge = stringResource(R.string.file_too_large, Fmt.bytes(total))
    val cannotRead = stringResource(R.string.cannot_read, "")
    val content = remember(path, tooLarge, cannotRead) {
        runCatching {
            val f = File(path)
            val limit = 150_000
            if (f.length() > 240_000) {
                f.inputStream().bufferedReader().use { r ->
                    val buf = CharArray(limit)
                    val n = r.read(buf, 0, limit)
                    String(buf, 0, n.coerceAtLeast(0)) + "\n\n" + tooLarge
                }
            } else f.readText()
        }.getOrElse { cannotRead + (it.message ?: "") }
    }
    SelectionContainer {
        Text(
            content,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            style = MonoStyle
        )
    }
}
