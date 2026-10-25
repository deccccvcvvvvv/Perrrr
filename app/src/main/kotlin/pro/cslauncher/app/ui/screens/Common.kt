package pro.cslauncher.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.ui.theme.LocalPalette
import java.io.File

@Composable fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) } else Spacer(Modifier.width(8.dp))
        Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = LocalPalette.current.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))

@Composable fun Chip(text: String, color: Color = LocalPalette.current.textSecondary) {
    val p = LocalPalette.current
    Surface(shape = RoundedCornerShape(50), color = p.secondarySurface.copy(alpha = if (p.glass) 0.6f else 1f)) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)
    }
}

@Composable fun InfoRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun ClickRow(title: String, subtitle: String? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = p.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.text else p.textMuted, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = p.textSecondary, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.background, uncheckedTrackColor = p.secondarySurface))
    }
}

@Composable fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row { Text(title, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text(format(value), color = p.textSecondary, fontSize = 14.sp) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.secondarySurface))
    }
}

@Composable fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = p.text, fontSize = 15.sp)
        FlowRowCompat {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, enabled = enabled(o), label = { Text(label(o)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.selected, selectedLabelColor = p.text, labelColor = p.textSecondary))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun FlowRowCompat(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable fun ConfirmDialog(title: String, text: String, confirm: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) p.danger else p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.textSecondary) } },
        containerColor = p.elevated, titleContentColor = p.text, textContentColor = p.textSecondary)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun relativeTime(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when { d < 60 -> "just now"; d < 3600 -> "${d / 60} min ago"; d < 86400 -> "${d / 3600} h ago"; else -> "${d / 86400} d ago" }
}

/** Copies a document picked through the system file picker into the cache, keeping its display name. */
fun copyToCache(ctx: Context, uri: Uri): File {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?.replace(Regex("[^A-Za-z0-9._ -]"), "_") ?: "import.bin"
    val dir = File(ctx.cacheDir, "imports").apply { mkdirs() }
    val out = File(dir, name)
    ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: error("Cannot read the selected file")
    return out
}
