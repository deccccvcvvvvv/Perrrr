package pro.cslauncher.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.*

/** Colours of the pixel UI kit. */
object Px {
    val border = Color(0xFF1E1E1F)
    val panel = Color(0xF2313233)
    val panelLight = Color(0xFF4A4B4D)
    val panelDark = Color(0xFF262627)
    val inset = Color(0xFF1B1B1C)
    val insetShade = Color(0xFF111112)
    val face = Color(0xFF48494A); val faceHi = Color(0xFF6D6E70); val faceDepth = Color(0xFF2A2B2C)
    val green = Color(0xFF3C8527); val greenHi = Color(0xFF52A535); val greenDepth = Color(0xFF1D4D13)
    val red = Color(0xFFB83232); val redHi = Color(0xFFDA4D4D); val redDepth = Color(0xFF5E1717)
    val selFace = Color(0xFF5F7E52); val selHi = Color(0xFF7FA16F); val selDepth = Color(0xFF2F4428)
    val disabled = Color(0xFF3A3B3C); val disabledText = Color(0xFF8A8B8D)
    val splash = Color(0xFFFFE866)
    val textShadow = Color(0xFF2A2A2A)
}

val PixelTextShadow = Shadow(Px.textShadow, Offset(3f, 3f), 0f)

/** Stone frame: dark outline, flat face, 1-step light top/left and dark bottom/right edges. */
fun DrawScope.pixelFrame(face: Color, light: Color, dark: Color, border: Color, b: Float) {
    val w = size.width; val h = size.height
    drawRect(border)
    drawRect(face, Offset(b, b), Size(w - 2 * b, h - 2 * b))
    drawRect(light, Offset(b, b), Size(w - 2 * b, b))
    drawRect(light, Offset(b, b), Size(b, h - 2 * b))
    drawRect(dark, Offset(b, h - 2 * b), Size(w - 2 * b, b))
    drawRect(dark, Offset(w - 2 * b, b), Size(b, h - 2 * b))
}

/** Sunken field (text inputs, progress tracks). */
fun DrawScope.pixelInset(b: Float, fill: Color = Px.inset) {
    val w = size.width; val h = size.height
    drawRect(Px.border)
    drawRect(fill, Offset(b, b), Size(w - 2 * b, h - 2 * b))
    drawRect(Px.insetShade, Offset(b, b), Size(w - 2 * b, b))
    drawRect(Px.insetShade, Offset(b, b), Size(b, h - 2 * b))
    drawRect(Color(0xFF3A3B3C), Offset(b, h - 2 * b), Size(w - 2 * b, b))
}

@Composable
fun PixelPanel(modifier: Modifier = Modifier, selected: Boolean = false, padding: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    if (!p.pixel) {
        val shape = RoundedCornerShape(8.dp)
        Surface(modifier = modifier, shape = shape, color = if (selected) p.selected else p.card,
            border = BorderStroke(1.dp, if (selected) p.accent.copy(alpha = 0.75f) else p.border)) {
            Column(Modifier.padding(padding), content = content)
        }
        return
    }
    val sel by animateColorAsState(if (selected) Color(0xFF6CC349) else Px.border, tween(160), label = "panelBorder")
    Column(modifier.drawBehind {
        val b = 2.dp.toPx()
        pixelFrame(Px.panel, Px.panelLight, Px.panelDark, sel, b)
        if (selected) { drawRect(sel, Offset.Zero, Size(size.width, b)); drawRect(sel, Offset(0f, size.height - b), Size(size.width, b)) }
    }.padding(padding + 2.dp), content = content)
}

enum class PxStyle { NORMAL, PRIMARY, DANGER, SELECTED }

/** Bevelled 3D button: the face sinks into its depth when pressed (with a light haptic tick). */
@Composable
fun PixelButton(
    text: String?, onClick: () -> Unit, modifier: Modifier = Modifier, style: PxStyle = PxStyle.NORMAL, enabled: Boolean = true,
    icon: String? = null, height: Dp = 40.dp, fontSize: TextUnit = 12.sp, contentDescription: String? = null,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    if (!p.pixel) {
        val click = { if (enabled) { MinecraftUiClickSound.play(context); onClick() } }
        val buttonModifier = modifier.heightIn(min = height).semantics { if (contentDescription != null) this.contentDescription = contentDescription }
        val contents: @Composable RowScope.() -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) PixelIcon(icon, size = 18.dp, tint = if (enabled) p.text else p.textMuted)
                if (icon != null && !text.isNullOrBlank()) Spacer(Modifier.width(8.dp))
                if (!text.isNullOrBlank()) Text(text, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when (style) {
            PxStyle.PRIMARY -> Button(click, buttonModifier, enabled, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White), content = contents)
            PxStyle.SELECTED -> FilledTonalButton(click, buttonModifier, enabled, colors = ButtonDefaults.filledTonalButtonColors(containerColor = p.selected, contentColor = p.text), content = contents)
            PxStyle.DANGER -> OutlinedButton(click, buttonModifier, enabled, border = BorderStroke(1.dp, p.danger.copy(alpha = 0.8f)), content = contents)
            PxStyle.NORMAL -> OutlinedButton(click, buttonModifier, enabled, content = contents)
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(if (reduce) 0 else 60), label = "press")
    val haptic = LocalHapticFeedback.current
    val (face, hi, depth) = when {
        !enabled -> Triple(Px.disabled, Color(0xFF4A4B4C), Color(0xFF242425))
        style == PxStyle.PRIMARY -> Triple(Px.green, Px.greenHi, Px.greenDepth)
        style == PxStyle.DANGER -> Triple(Px.red, Px.redHi, Px.redDepth)
        style == PxStyle.SELECTED -> Triple(Px.selFace, Px.selHi, Px.selDepth)
        else -> Triple(Px.face, Px.faceHi, Px.faceDepth)
    }
    val textColor = if (enabled) Color.White else Px.disabledText
    val depthDp = 4.dp
    Box(
        modifier
            .heightIn(min = height)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .clickable(interaction, null, enabled = enabled, role = Role.Button) { MinecraftUiClickSound.play(context); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .drawBehind {
                val d = depthDp.toPx(); val b = 2.dp.toPx(); val top = d * press
                val w = size.width; val h = size.height
                drawRect(Px.border, Offset(0f, top), Size(w, h - top))
                drawRect(depth, Offset(b, top + b), Size(w - 2 * b, h - top - 2 * b))
                val faceH = h - top - 2 * b - d * (1 - press)
                drawRect(face, Offset(b, top + b), Size(w - 2 * b, faceH))
                drawRect(hi, Offset(b, top + b), Size(w - 2 * b, b))
                drawRect(hi.copy(alpha = 0.55f), Offset(b, top + b), Size(b, faceH))
            }
            .padding(start = if (text.isNullOrEmpty()) 6.dp else 10.dp, end = if (text.isNullOrEmpty()) 6.dp else 10.dp, top = 2.dp + depthDp * press, bottom = 2.dp + depthDp * (1 - press)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) PixelIcon(icon, size = when { height >= 56.dp -> 24.dp; height < 34.dp -> 14.dp; else -> 18.dp }, tint = textColor)
            if (icon != null && !text.isNullOrEmpty()) Spacer(Modifier.width(if (height < 34.dp) 5.dp else 7.dp))
            if (!text.isNullOrEmpty()) Text(text, color = textColor, fontFamily = Monocraft, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = if (enabled) PixelTextShadow else null), textAlign = TextAlign.Center)
        }
    }
}

/** Square icon button sharing the bevel look. */
@Composable
fun PixelIconButton(icon: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: PxStyle = PxStyle.NORMAL, enabled: Boolean = true, size: Dp = 38.dp) =
    PixelButton(null, onClick, modifier.widthIn(min = size), style, enabled, icon = icon, height = size, contentDescription = contentDescription)

/** On/off switch: a sunken slot with a sliding stone knob; the slot turns green when on. */
@Composable
fun PixelToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    val context = LocalContext.current
    if (!p.pixel) {
        Switch(checked = checked, onCheckedChange = { MinecraftUiClickSound.play(context); onCheckedChange(it) }, modifier = modifier, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = Color.White, uncheckedTrackColor = p.secondarySurface))
        return
    }
    val reduce = LocalReduceMotion.current
    val t by animateFloatAsState(if (checked) 1f else 0f, if (reduce) snap() else spring(dampingRatio = 0.6f, stiffness = 700f), label = "toggle")
    val haptic = LocalHapticFeedback.current
    Canvas(modifier.size(48.dp, 26.dp).clickable(enabled = enabled, role = Role.Switch) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCheckedChange(!checked) }) {
        val b = 2.dp.toPx()
        pixelInset(b, lerp(Px.inset, if (enabled) Px.green else Px.disabled, t))
        val k = size.height - 2 * b
        val x = b + (size.width - 2 * b - k) * t
        drawRect(Px.border, Offset(x, b), Size(k, k))
        drawRect(if (enabled) Px.face else Px.disabled, Offset(x + b, b + b), Size(k - 2 * b, k - 3 * b))
        drawRect(if (enabled) Px.faceHi else Px.disabled, Offset(x + b, b + b), Size(k - 2 * b, b))
        drawRect(Px.faceDepth, Offset(x + b, b + k - 2 * b), Size(k - 2 * b, b))
    }
}

/** Slider with a sunken track, green fill and a stone thumb. Horizontal drags only, so it never blocks vertical scrolling. */
@Composable
fun PixelSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, steps: Int = 0, enabled: Boolean = true) {
    val p = LocalPalette.current
    if (!p.pixel) {
        Slider(value = value.coerceIn(valueRange), onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange, steps = steps,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.secondarySurface))
        return
    }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val frac = ((value.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) / span)
    val latest by rememberUpdatedState(onValueChange)
    fun emit(x: Float, width: Float, thumb: Float) {
        var f = ((x - thumb / 2) / (width - thumb)).coerceIn(0f, 1f)
        if (steps > 0) { val n = steps + 1; f = (f * n).roundToInt() / n.toFloat() }
        latest(valueRange.start + f * span)
    }
    Canvas(modifier.fillMaxWidth().height(32.dp)
        .pointerInput(enabled, valueRange, steps) { if (enabled) detectTapGestures { emit(it.x, size.width.toFloat(), 18.dp.toPx()) } }
        .pointerInput(enabled, valueRange, steps) { if (enabled) detectHorizontalDragGestures { ch, _ -> ch.consume(); emit(ch.position.x, size.width.toFloat(), 18.dp.toPx()) } }
    ) {
        val b = 2.dp.toPx(); val thumb = 18.dp.toPx(); val trackH = 12.dp.toPx()
        val top = (size.height - trackH) / 2
        val travel = size.width - thumb
        translate(0f, top) {
            drawRect(Px.border, Offset.Zero, Size(size.width, trackH))
            drawRect(Px.inset, Offset(b, b), Size(size.width - 2 * b, trackH - 2 * b))
            drawRect(if (enabled) Px.green else Px.disabled, Offset(b, b), Size(max(0f, thumb / 2 + travel * frac - b), trackH - 2 * b))
            drawRect(if (enabled) Px.greenHi else Px.disabled, Offset(b, b), Size(max(0f, thumb / 2 + travel * frac - b), b))
        }
        val x = travel * frac
        translate(x, 0f) {
            drawRect(Px.border, Offset.Zero, Size(thumb, size.height))
            drawRect(Px.faceDepth, Offset(b, b), Size(thumb - 2 * b, size.height - 2 * b))
            drawRect(Px.face, Offset(b, b), Size(thumb - 2 * b, size.height - 4 * b))
            drawRect(Px.faceHi, Offset(b, b), Size(thumb - 2 * b, b))
        }
    }
}

/** Segmented choice built from bevelled buttons; the chosen one is shown pressed-in and green. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> PixelTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
                  enabled: (T) -> Boolean = { true }, icon: (T) -> String? = { null }, fill: Boolean = false, height: Dp = 34.dp) {
    val p = LocalPalette.current
    val context = LocalContext.current
    if (!p.pixel) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { MinecraftUiClickSound.play(context); onSelect(o) }, enabled = enabled(o),
                    label = { Text(label(o), maxLines = 1) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.selected, selectedLabelColor = p.text, labelColor = p.textSecondary))
            }
        }
        return
    }
    if (fill) Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> PixelButton(label(o), { onSelect(o) }, Modifier.weight(1f), if (o == selected) PxStyle.SELECTED else PxStyle.NORMAL, enabled(o), icon(o), height, 11.sp) }
    } else FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> PixelButton(label(o), { onSelect(o) }, Modifier, if (o == selected) PxStyle.SELECTED else PxStyle.NORMAL, enabled(o), icon(o), height, 11.sp) }
    }
}

@Composable
fun PixelTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", singleLine: Boolean = true,
                   minLines: Int = 1, onDone: (() -> Unit)? = null, label: String? = null, secret: Boolean = false) {
    val p = LocalPalette.current
    if (!p.pixel) {
        OutlinedTextField(value, onValueChange, modifier = modifier.fillMaxWidth(), label = label?.let { { Text(it) } },
            placeholder = placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } }, singleLine = singleLine, minLines = minLines,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }))
        return
    }
    Column(modifier) {
        if (label != null) Text(label, color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(value, onValueChange, Modifier.fillMaxWidth(), visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, singleLine = singleLine, minLines = minLines,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = Monocraft), cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Search else ImeAction.Default),
            keyboardActions = KeyboardActions(onSearch = { onDone?.invoke() }, onDone = { onDone?.invoke() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().drawBehind { pixelInset(2.dp.toPx()) }.padding(horizontal = 10.dp, vertical = 9.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = Color(0xFF7A7B7E), fontSize = 13.sp, fontFamily = Monocraft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            })
    }
}

/** Segmented progress bar; indeterminate shows a block sliding along (static with reduced motion). */
@Composable
fun PixelProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = Px.green, highlight: Color = Px.greenHi, height: Dp = 14.dp) {
    val p = LocalPalette.current
    if (!p.pixel) {
        if (fraction == null) LinearProgressIndicator(modifier.fillMaxWidth(), color = p.accent)
        else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(), color = p.accent)
        return
    }
    val reduce = LocalReduceMotion.current
    val anim = if (fraction == null && !reduce) rememberInfiniteTransition(label = "indet").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x").value else 0.3f
    val shown by animateFloatAsState(fraction ?: 0f, tween(if (reduce) 0 else 250), label = "progress")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val b = 2.dp.toPx()
        pixelInset(b)
        val innerW = size.width - 2 * b; val innerH = size.height - 2 * b
        if (fraction != null) {
            val w = innerW * shown.coerceIn(0f, 1f)
            drawRect(color, Offset(b, b), Size(w, innerH)); drawRect(highlight, Offset(b, b), Size(w, b))
            val seg = 8.dp.toPx(); var x = b + seg
            while (x < b + w) { drawRect(Color(0x33000000), Offset(x, b), Size(b, innerH)); x += seg }
        } else {
            val block = innerW * 0.25f
            val x = b + (innerW + block) * anim - block
            val l = max(b, x); val r = min(b + innerW, x + block)
            if (r > l) { drawRect(color, Offset(l, b), Size(r - l, innerH)); drawRect(highlight, Offset(l, b), Size(r - l, b)) }
        }
    }
}

/** Yellow, tilted, gently pulsing tagline in the spirit of the classic title-screen splash. */
@Composable
fun SplashText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp) {
    val p = LocalPalette.current
    if (!p.pixel) {
        Text(text, modifier, color = p.textSecondary, fontSize = fontSize, maxLines = 1)
        return
    }
    val reduce = LocalReduceMotion.current
    val s = if (reduce) 1f else rememberInfiniteTransition(label = "splash").animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s").value
    Text(text, modifier.graphicsLayer { rotationZ = -12f; scaleX = s; scaleY = s }, color = Px.splash, fontFamily = Monocraft, fontSize = fontSize,
        style = TextStyle(shadow = Shadow(Color(0xFF3F3A10), Offset(3f, 3f), 0f)), maxLines = 1)
}

/** Title in the pixel font with a hard drop shadow. */
@Composable
fun PixelTitle(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 18.sp, color: Color = Color.White) {
    val p = LocalPalette.current
    if (!p.pixel) Text(text, modifier, color = if (color == Color.White) p.text else color, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else Text(text, modifier, color = color, fontFamily = Monocraft, fontSize = fontSize, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = PixelTextShadow))
}

/**
 * Modal panel that pops in (scale + fade). Sized for landscape phones: never taller than the screen, the body scrolls
 * while the title and the button row stay visible. Set [scroll] = false when the body brings its own lazy list.
 * In previews/screenshot tests it is drawn inline because dialog windows cannot be rendered there.
 */
@Composable
fun PixelDialog(onDismiss: () -> Unit, title: String, modifier: Modifier = Modifier, widthFraction: Float = 0.72f, scroll: Boolean = true,
                buttons: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val cfg = LocalConfiguration.current
    val maxH = (cfg.screenHeightDp - 16).coerceAtLeast(200).dp
    val maxW = (cfg.screenWidthDp - 24).coerceAtLeast(260).dp
    val p = LocalPalette.current
    if (!p.pixel) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxWidth(widthFraction).widthIn(min = minOf(320.dp, maxW), max = maxW).heightIn(max = maxH),
                shape = RoundedCornerShape(10.dp), color = p.elevated, border = BorderStroke(1.dp, p.border)) {
                Column(Modifier.padding(18.dp)) {
                    Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
                }
            }
        }
        return
    }
    val body = @Composable {
        val state = remember { MutableTransitionState(false).apply { targetState = true } }
        val reduce = LocalReduceMotion.current
        AnimatedVisibility(state, enter = if (reduce) EnterTransition.None else scaleIn(tween(170), 0.9f) + fadeIn(tween(140))) {
            PixelPanel(modifier.fillMaxWidth(widthFraction).widthIn(min = minOf(320.dp, maxW), max = maxW).heightIn(max = maxH), padding = 10.dp) {
                PixelTitle(title, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f, fill = false).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) { content() }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically, content = buttons)
            }
        }
    }
    if (LocalInspectionMode.current) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) { body() }
    } else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { body() }
}

/** Staggered entrance for list/grid items: fades in and rises a few pixels. Disabled with reduced motion. */
fun Modifier.staggerIn(index: Int): Modifier = composed {
    if (!LocalPalette.current.pixel || LocalReduceMotion.current) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(min(index, 12) * 32L); a.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18.dp.toPx() }
}

// ------------------------------------------------------------------ icons

private val iconCache = HashMap<Long, ImageBitmap>()

/** Rasterises a 12x12 icon from [PixelIconData] once per (icon, tint). */
fun pixelIconBitmap(name: String, tint: Color): ImageBitmap = synchronized(iconCache) {
    val key = name.hashCode().toLong() shl 32 or (tint.toArgb().toLong() and 0xFFFFFFFFL)
    iconCache.getOrPut(key) {
        val rows = PixelIconData.icons[name] ?: PixelIconData.icons.getValue("info")
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        val t = tint.toArgb(); val dark = lerp(tint, Color.Black, 0.38f).toArgb(); val light = lerp(tint, Color.White, 0.5f).toArgb()
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch ->
            val c = when (ch) { '.' -> 0; '#' -> t; '=' -> dark; '+' -> light; else -> PixelIconData.palette[ch]?.toInt() ?: 0 }
            bmp.setPixel(x, y, c)
        } }
        bmp.asImageBitmap()
    }
}

@Composable
fun PixelIcon(name: String, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = Color.White, contentDescription: String? = null) {
    val img = remember(name, tint) { pixelIconBitmap(name, tint) }
    Canvas(modifier.size(size).semantics { if (contentDescription != null) this.contentDescription = contentDescription }) {
        val s = min(this.size.width, this.size.height).toInt()
        drawImage(img, IntOffset.Zero, IntSize(12, 12), IntOffset.Zero, IntSize(s, s), filterQuality = FilterQuality.None)
    }
}

// ------------------------------------------------------------------ background scene

private class ScenePalette(val skyTop: Color, val skyBottom: Color, val far: Color, val mid: Color, val midTop: Color, val grass: Color, val grassDark: Color,
                           val dirt: Color, val dirtDark: Color, val sun: Color, val night: Boolean, val cloud: Color)

private fun paletteFor(hour: Int): ScenePalette = when (hour) {
    in 7..16 -> ScenePalette(Color(0xFF5E9CFF), Color(0xFFB9DCFF), Color(0xFF86A3C7), Color(0xFF3F7A30), Color(0xFF4C9139), Color(0xFF6CC349), Color(0xFF4E9A33),
        Color(0xFF8B5A2B), Color(0xFF6B4320), Color(0xFFFFF27A), false, Color(0xFFF4F7FF))
    in 17..19, 5, 6 -> ScenePalette(Color(0xFF3A4A8E), Color(0xFFFF9C6A), Color(0xFF7A6795), Color(0xFF355F2C), Color(0xFF427236), Color(0xFF5BA63D), Color(0xFF417C2C),
        Color(0xFF7A4E25), Color(0xFF5B391B), Color(0xFFFFB347), false, Color(0xFFFFD6C2))
    else -> ScenePalette(Color(0xFF080C26), Color(0xFF223066), Color(0xFF2B3763), Color(0xFF1C3720), Color(0xFF244628), Color(0xFF2F6B22), Color(0xFF235219),
        Color(0xFF4A3018), Color(0xFF362210), Color(0xFFE8ECF5), true, Color(0xFF59648F))
}

private fun hash(x: Int, seed: Int): Int { var h = x * 374761393 + seed * 668265263; h = (h xor (h ushr 13)) * 1274126177; return h xor (h ushr 16) }
private fun rnd(x: Int, seed: Int) = (hash(x, seed) and 0xFFFF) / 65535f
private fun noise(x: Float, seed: Int): Float {
    val i = floor(x).toInt(); val f = x - i; val u = f * f * (3 - 2 * f)
    return rnd(i, seed) * (1 - u) + rnd(i + 1, seed) * u
}

/**
 * Procedural blocky landscape with parallax layers (far mountains, hills, grass with trees, drifting clouds), coloured
 * by the time of day. Drawn as whole "scene pixels" on a ~90-row grid; ticks at ~20 fps only while the screen is
 * resumed, and is a still frame when [animate] is false.
 */
@Composable
fun PixelScene(modifier: Modifier = Modifier, animate: Boolean = true) {
    val pal = remember { paletteFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var t by remember { mutableFloatStateOf(37f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (animate) LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = System.nanoTime()
            while (true) { delay(50); val now = System.nanoTime(); t += ((now - last) / 1e9f).coerceAtMost(0.2f); last = now }
        }
    }
    Canvas(modifier) {
        val unit = max(2f, floor(size.height / 90f))
        val cols = ceil(size.width / unit).toInt() + 1
        val rows = ceil(size.height / unit).toInt()
        fun cell(c: Int, r: Int, w: Int, h: Int, color: Color) = drawRect(color, Offset(c * unit, r * unit), Size(w * unit, h * unit))
        // Sky in hard bands.
        val bands = 8
        for (i in 0 until bands) {
            val r0 = rows * 0.75f * i / bands; val r1 = rows * 0.75f * (i + 1) / bands
            drawRect(lerp(pal.skyTop, pal.skyBottom, i / (bands - 1f)), Offset(0f, floor(r0) * unit), Size(size.width, (ceil(r1) - floor(r0)) * unit))
        }
        drawRect(pal.skyBottom, Offset(0f, floor(rows * 0.75f) * unit), Size(size.width, size.height))
        if (pal.night) for (i in 0 until 60) {
            val x = (rnd(i, 11) * cols).toInt(); val y = (rnd(i, 12) * rows * 0.5f).toInt()
            val tw = if (animate) ((sin(t * 2f + i) + 1f) / 2f) else 0.6f
            cell(x, y, 1, 1, Color.White.copy(alpha = 0.35f + 0.6f * tw))
        }
        // Sun / moon.
        val sx = (cols * 0.78f).toInt(); val sy = (rows * 0.12f).toInt()
        cell(sx - 1, sy - 1, 10, 10, pal.sun.copy(alpha = 0.25f)); cell(sx, sy, 8, 8, pal.sun)
        if (pal.night) { cell(sx + 2, sy + 2, 2, 2, Color(0xFFC9CEDB)); cell(sx + 5, sy + 4, 1, 2, Color(0xFFC9CEDB)) }
        // Clouds (wrap around).
        val cloudOff = (t * 1.6f).toInt()
        for (i in 0 until 7) {
            val period = cols + 40
            val base = (rnd(i, 21) * period).toInt()
            val x = ((base - cloudOff) % period + period) % period - 30
            val y = (rows * (0.06f + rnd(i, 22) * 0.28f)).toInt()
            val w = 10 + (rnd(i, 23) * 16).toInt()
            cell(x, y, w, 2, pal.cloud); cell(x + 3, y - 1, w - 6, 1, pal.cloud); cell(x + 2, y + 2, w - 4, 1, pal.cloud.copy(alpha = 0.85f))
        }
        // Terrain layers: (speed, base row fraction, amplitude rows, noise scale, seed).
        fun layer(speed: Float, base: Float, amp: Float, scale: Float, seed: Int, draw: (col: Int, worldX: Int, top: Int) -> Unit) {
            val off = (t * speed).toInt()
            var c = 0
            while (c < cols) {
                val wx = c + off
                val top = (rows * base - amp * noise(wx / scale, seed) - amp * 0.35f * noise(wx / (scale * 0.37f), seed + 7)).toInt()
                draw(c, wx, top); c++
            }
        }
        // Far mountains: merge equal-height runs into single rects.
        run {
            val off = (t * 0.8f).toInt(); var c = 0
            while (c < cols) {
                val top = (rows * 0.62f - 22 * noise((c + off) / 14f, 3) - 6 * noise((c + off) / 5f, 4)).toInt()
                var e = c + 1
                while (e < cols && (rows * 0.62f - 22 * noise((e + off) / 14f, 3) - 6 * noise((e + off) / 5f, 4)).toInt() == top) e++
                cell(c, top, e - c, rows - top, pal.far); c = e
            }
        }
        layer(2.2f, 0.78f, 12f, 11f, 5) { c, _, top -> cell(c, top, 1, 1, pal.midTop); cell(c, top + 1, 1, rows - top, pal.mid) }
        val groundOff = (t * 6f).toInt()
        layer(6f, 0.92f, 5f, 9f, 9) { c, wx, top ->
            cell(c, top, 1, 1, pal.grass); cell(c, top + 1, 1, 1, pal.grassDark); cell(c, top + 2, 1, rows - top, pal.dirt)
            if (hash(wx, 31) and 3 == 0) cell(c, top + 3 + (rnd(wx, 32) * 5).toInt(), 1, 1, pal.dirtDark)
            if (rnd(wx, 33) < 0.18f) cell(c, top - 1, 1, 1, pal.grassDark)
        }
        // Trees on the ground layer.
        for (c in -3 until cols + 3) {
            val wx = c + groundOff
            if (hash(wx, 41) % 29 != 0) continue
            val top = (rows * 0.92f - 5f * noise(wx / 9f, 9) - 5f * 0.35f * noise(wx / (9f * 0.37f), 16)).toInt()
            val h = 4 + (rnd(wx, 42) * 3).toInt()
            cell(c, top - h, 1, h, Color(0xFF6B4A2A))
            cell(c - 2, top - h - 3, 5, 3, pal.grassDark); cell(c - 1, top - h - 4, 3, 1, pal.grassDark); cell(c - 1, top - h - 3, 2, 1, pal.grass)
        }
    }
}

/** Square bevelled tile with a large icon above a label (quick actions, menus). */
@Composable
fun PixelTile(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.White, enabled: Boolean = true) {
    val p = LocalPalette.current
    val context = LocalContext.current
    if (!p.pixel) {
        val shape = RoundedCornerShape(8.dp)
        Surface(modifier = modifier.heightIn(min = 68.dp).clip(shape).clickable(enabled = enabled) { MinecraftUiClickSound.play(context); onClick() },
            shape = shape, color = p.card, border = BorderStroke(1.dp, p.border)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PixelIcon(icon, size = 22.dp, tint = tint)
                Spacer(Modifier.height(6.dp))
                Text(label, color = p.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(if (reduce) 0 else 60), label = "tile")
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .clickable(interaction, null, enabled = enabled, role = Role.Button) { MinecraftUiClickSound.play(context); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .drawBehind {
                val d = 4.dp.toPx(); val b = 2.dp.toPx(); val top = d * press
                drawRect(Px.border, Offset(0f, top), Size(size.width, size.height - top))
                drawRect(Px.faceDepth, Offset(b, top + b), Size(size.width - 2 * b, size.height - top - 2 * b))
                val faceH = size.height - top - 2 * b - d * (1 - press)
                drawRect(if (enabled) Px.face else Px.disabled, Offset(b, top + b), Size(size.width - 2 * b, faceH))
                drawRect(Px.faceHi, Offset(b, top + b), Size(size.width - 2 * b, b))
            }
            .padding(start = 3.dp, end = 3.dp, top = 7.dp + 4.dp * press, bottom = 7.dp + 4.dp * (1 - press)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelIcon(icon, size = 24.dp, tint = if (enabled) tint else Px.disabledText)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) Color.White else Px.disabledText, fontFamily = Monocraft, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = PixelTextShadow))
    }
}

/** Framed icon "slot" like an inventory cell. */
@Composable
fun PixelSlot(modifier: Modifier = Modifier, size: Dp = 52.dp, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.size(size).drawBehind { pixelInset(2.dp.toPx(), Color(0xFF8B8B8B).copy(alpha = 0.35f)) }.padding(6.dp), contentAlignment = Alignment.Center, content = content)
