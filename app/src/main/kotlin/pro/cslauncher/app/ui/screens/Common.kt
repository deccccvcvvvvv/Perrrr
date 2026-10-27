package pro.cslauncher.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.ui.theme.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import java.io.File

/** Compact screen header (about 46dp): back button, title and right-aligned actions that scroll if they do not fit. */
@Composable fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    if (p.pixel) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { PixelIconButton("back", "Back", onBack, size = 36.dp); Spacer(Modifier.width(8.dp)) }
            PixelTitle(title, Modifier.weight(1f, fill = false).padding(end = 10.dp), fontSize = 17.sp)
            Spacer(Modifier.weight(0.001f))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) } else Spacer(Modifier.width(8.dp))
        Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Header action sized for phones: 36dp, icon plus a short label (label hidden when [compact]). */
@Composable fun HeaderAction(text: String, icon: String, onClick: () -> Unit, style: PxStyle = PxStyle.NORMAL, compact: Boolean = false, enabled: Boolean = true) {
    val p = LocalPalette.current
    if (!p.pixel) { AppButton(text, onClick, style = style, enabled = enabled); return }
    if (compact) PixelIconButton(icon, text, onClick, style = style, enabled = enabled, size = 36.dp)
    else PixelButton(text, onClick, style = style, enabled = enabled, icon = icon, height = 36.dp, fontSize = 11.sp)
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    if (p.pixel) Text(text, color = Color(0xFFE6E6E6), fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, style = TextStyle(shadow = PixelTextShadow), modifier = modifier.padding(top = 10.dp, bottom = 6.dp))
    else Text(text.uppercase(), color = p.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, modifier = modifier.padding(top = 14.dp, bottom = 8.dp))
}

/** A panel with its own small header line (icon + title + optional trailing content). */
@Composable fun PanelSection(title: String, icon: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    CsCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && p.pixel) { PixelIcon(icon, size = 16.dp); Spacer(Modifier.width(6.dp)) }
            Text(title, color = Color.White, fontFamily = if (p.pixel) Monocraft else null, fontSize = 13.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = if (p.pixel) PixelTextShadow else null), modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** Small secondary action (replaces Material text buttons in the pixel theme). */
@Composable fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, style: PxStyle = PxStyle.NORMAL, enabled: Boolean = true) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    if (p.pixel) PixelButton(text, onClick, modifier, style, enabled, icon, height = 32.dp, fontSize = 11.sp)
    else TextButton(onClick = { MinecraftUiClickSound.play(ctx); onClick() }, modifier = modifier, enabled = enabled) { Text(text, color = if (style == PxStyle.DANGER) p.danger else p.accent) }
}

/** Theme-aware single-line text input. */
@Composable fun AppTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "", singleLine: Boolean = true) {
    val p = LocalPalette.current
    if (p.pixel) PixelTextField(value, onChange, modifier.fillMaxWidth(), placeholder = placeholder, singleLine = singleLine, label = label)
    else OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine, modifier = modifier.fillMaxWidth())
}

/** Theme-aware progress bar (null = indeterminate). */
@Composable fun AppProgress(fraction: Float? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    if (p.pixel) PixelProgress(fraction, modifier, height = 12.dp)
    else if (fraction == null) LinearProgressIndicator(modifier.fillMaxWidth(), color = p.accent) else LinearProgressIndicator({ fraction }, modifier.fillMaxWidth(), color = p.accent)
}

@Composable fun Chip(text: String, color: Color = LocalPalette.current.textSecondary) {
    val p = LocalPalette.current
    if (p.pixel) {
        Box(Modifier.background(Color(0xFF1E1E1F)).padding(2.dp).background(Color(0xFF242526)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(text, color = color, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = Monocraft, maxLines = 1)
        }
        return
    }
    Surface(shape = RoundedCornerShape(50), color = p.secondarySurface.copy(alpha = if (p.glass) 0.6f else 1f)) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)
    }
}

@Composable fun InfoRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = p.textSecondary, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        Text(value, color = p.text, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable fun ClickRow(title: String, subtitle: String? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { MinecraftUiClickSound.play(ctx); onClick() }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp, lineHeight = 16.sp)
            if (subtitle != null) Text(subtitle, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = if (enabled) p.text else p.textMuted, fontSize = 13.sp, lineHeight = 16.sp)
            if (subtitle != null) Text(subtitle, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp)
        }
        if (p.pixel) PixelToggle(checked, onChange, enabled = enabled) else Switch(checked = checked, onCheckedChange = { MinecraftUiClickSound.play(ctx); onChange(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.background, uncheckedTrackColor = p.secondarySurface))
    }
}

@Composable fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row { Text(title, color = p.text, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f)); Text(format(value), color = Color(0xFF9FE07F).takeIf { p.pixel } ?: p.textSecondary, fontSize = 12.sp, lineHeight = 16.sp) }
        if (p.pixel) PixelSlider(value, onChange, range, steps = steps) else Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.secondarySurface))
    }
}

@Composable fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = p.text, fontSize = 13.sp, lineHeight = 16.sp)
        if (p.pixel) { Spacer(Modifier.height(5.dp)); PixelTabs(options, selected, label, onSelect, enabled = enabled, height = 32.dp); return@Column }
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable fun ConfirmDialog(title: String, text: String, confirm: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    if (p.pixel) {
        PixelDialog(onDismiss, title, widthFraction = 0.5f, buttons = {
            PixelButton("Cancel", onDismiss)
            PixelButton(confirm, { onConfirm(); onDismiss() }, style = if (destructive) PxStyle.DANGER else PxStyle.PRIMARY)
        }) { Text(text, color = p.textSecondary, fontSize = 12.sp, lineHeight = 15.sp) }
        return
    }
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { MinecraftUiClickSound.play(ctx); onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) p.danger else p.accent) } },
        dismissButton = { TextButton(onClick = { MinecraftUiClickSound.play(ctx); onDismiss() }) { Text("Cancel", color = p.textSecondary) } },
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

/** Theme-aware button: pixel bevel in the pixel theme, Material otherwise. */
@Composable fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: PxStyle = PxStyle.NORMAL, enabled: Boolean = true, icon: String? = null) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    if (p.pixel) { PixelButton(text, onClick, modifier, style, enabled, icon); return }
    val click = { if (enabled) { MinecraftUiClickSound.play(ctx); onClick() } }
    val content: @Composable RowScope.() -> Unit = { Text(text) }
    when (style) {
        PxStyle.PRIMARY -> Button(click, modifier, enabled, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White), content = content)
        PxStyle.SELECTED -> FilledTonalButton(click, modifier, enabled, colors = ButtonDefaults.filledTonalButtonColors(containerColor = p.selected, contentColor = p.text), content = content)
        PxStyle.DANGER -> OutlinedButton(click, modifier, enabled, border = androidx.compose.foundation.BorderStroke(1.dp, p.danger), content = content)
        else -> OutlinedButton(click, modifier, enabled, content = content)
    }
}

/** Small square "badge" (e.g. Installed). */
@Composable fun Badge(text: String, color: Color = Color(0xFF6CC349), icon: String? = null) {
    val p = LocalPalette.current
    if (!p.pixel) {
        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
            Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        return
    }
    Row(Modifier.height(18.dp).background(Color(0xFF1E1E1F)).padding(2.dp).background(color.copy(alpha = 0.22f)).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { PixelIcon(icon, size = 10.dp, tint = color); Spacer(Modifier.width(3.dp)) }
        Text(text, color = color, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Monocraft, maxLines = 1)
    }
}
