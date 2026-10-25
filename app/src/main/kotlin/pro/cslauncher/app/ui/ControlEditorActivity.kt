package pro.cslauncher.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.screens.ChoiceRow
import pro.cslauncher.app.ui.screens.ConfirmDialog
import pro.cslauncher.app.ui.screens.SliderRow
import pro.cslauncher.app.ui.theme.CsTheme
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.controls.*

/** Full-screen, landscape control layout editor with undo/redo, grid snapping, modes, import/export. */
class ControlEditorActivity : ComponentActivity() {
    companion object {
        fun open(ctx: Context, layoutId: String) = ctx.startActivity(Intent(ctx, ControlEditorActivity::class.java).putExtra("layout", layoutId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        val store = ControlLayoutStore(Services.dirs.config.resolve("controls"))
        val initial = store.load(intent.getStringExtra("layout") ?: ControlLayoutStore.DEFAULT_ID)
        setContent {
            val s by Services.settings.state.collectAsState()
            CsTheme(s.theme, s.reduceTransparency, s.reduceMotion) { Editor(store, initial, onClose = { finish() }) }
        }
    }

    @Composable
    private fun Editor(store: ControlLayoutStore, initial: ControlLayout, onClose: () -> Unit) {
        val p = LocalPalette.current
        val editor = remember { LayoutEditor(initial) }
        var version by remember { mutableIntStateOf(0) }          // bumps on every edit to recompose
        val layout = remember(version) { editor.current }
        var mode by remember { mutableStateOf(layout.defaultMode) }
        var selected by remember { mutableStateOf<String?>(null) }
        var grid by remember { mutableStateOf(true) }
        var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
        var confirmExit by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val density = LocalDensity.current.density
        val settings by Services.settings.state.collectAsState()
        val geo = Geometry(size.width, size.height, density, paddingPx = (settings.touch.safeAreaPaddingDp * density).toInt())
        fun edit(f: () -> Unit) { f(); version++ }
        val gridStep = 0.025f
        BackHandler { if (editor.dirty) confirmExit = true else onClose() }

        Box(Modifier.fillMaxSize().background(Color(0xFF15171C))) {
            // ---- canvas ----
            Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }
                .pointerInput(mode, version) {
                    detectTapGestures { pos -> selected = layout.elements(mode).lastOrNull { geo.rectOf(it).contains(pos.x, pos.y) }?.id }
                }
                .pointerInput(mode, version, grid) {
                    var dragId: String? = null; var off = Offset.Zero
                    detectDragGestures(
                        onDragStart = { pos -> val e = layout.elements(mode).lastOrNull { geo.rectOf(it).contains(pos.x, pos.y) }; dragId = e?.id; selected = e?.id ?: selected
                            e?.let { val r = geo.rectOf(it); off = Offset(r.cx - pos.x, r.cy - pos.y) } },
                        onDragEnd = { editor.endGesture(); dragId = null },
                        onDragCancel = { editor.endGesture(); dragId = null },
                        onDrag = { change, _ ->
                            val id = dragId ?: return@detectDragGestures
                            val (nx, ny) = geo.normalised(change.position.x + off.x, change.position.y + off.y)
                            editor.move(mode, id, nx, ny, if (grid) gridStep else null); version++
                        })
                }) {
                if (grid) { var g = 0f; while (g <= 1f) { val x = geo.safeLeft + g * geo.safeWidth; val y = geo.safeTop + g * geo.safeHeight
                    drawLine(Color(0x14FFFFFF), Offset(x, geo.safeTop.toFloat()), Offset(x, (geo.safeTop + geo.safeHeight).toFloat()))
                    drawLine(Color(0x14FFFFFF), Offset(geo.safeLeft.toFloat(), y), Offset((geo.safeLeft + geo.safeWidth).toFloat(), y)); g += gridStep * 4 } }
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F1F5.toInt(); textSize = 12 * density; textAlign = android.graphics.Paint.Align.CENTER }
                layout.elements(mode).forEach { e ->
                    val r = geo.rectOf(e); val sel = e.id == selected
                    val col = Color(e.colorArgb.toInt()).copy(alpha = e.opacity.coerceIn(0.15f, 1f))
                    if (e.kind == ElementKind.JOYSTICK) { drawCircle(col, minOf(r.width, r.height) / 2, Offset(r.cx, r.cy)); drawCircle(Color(0xFF777B87), minOf(r.width, r.height) * 0.19f, Offset(r.cx, r.cy)) }
                    else drawRoundRect(col, Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(minOf(r.width, r.height) * e.cornerRadius))
                    drawRoundRect(if (sel) Color(0xFFC5CBD5) else Color(0xFF34343F), Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(minOf(r.width, r.height) * e.cornerRadius), style = Stroke(if (sel) 3f else 1.5f))
                    drawContext.canvas.nativeCanvas.drawText(e.label.ifBlank { e.actions.firstOrNull()?.describe() ?: e.kind.name.lowercase() }.take(10), r.cx, r.cy + 4 * density, paint)
                }
            }

            // ---- toolbar ----
            Row(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(8.dp).background(p.elevated.copy(alpha = 0.92f), MaterialTheme.shapes.medium).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                layout.modes.keys.forEach { m -> FilterChip(m == mode, { mode = m; selected = null }, label = { Text(m) }, modifier = Modifier.padding(horizontal = 2.dp)) }
                TextButton(onClick = { edit { editor.add(mode, ControlElement(id = "button", label = "Btn", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_SPACE)))) } }) { Text("+ Button") }
                TextButton(onClick = { edit { editor.add(mode, ControlElement(id = "stick", kind = ElementKind.JOYSTICK, x = 0.15f, y = 0.7f, widthDp = 130f, heightDp = 130f, joystick = JoystickConfig())) } }) { Text("+ Joystick") }
                TextButton(onClick = { edit { editor.undo() } }, enabled = editor.canUndo) { Text("Undo") }
                TextButton(onClick = { edit { editor.redo() } }, enabled = editor.canRedo) { Text("Redo") }
                FilterChip(grid, { grid = !grid }, label = { Text("Grid") })
                TextButton(onClick = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS layout", ControlLayoutCodec.encode(editor.current)))
                    error = "Layout JSON copied to clipboard"
                }) { Text("Export") }
                TextButton(onClick = { importing = true }) { Text("Import") }
                TextButton(onClick = { edit { editor.resetAll(DefaultLayouts.defaultTouch()) } }) { Text("Reset") }
                TextButton(onClick = { runCatching { store.save(editor.current) }.onSuccess { onClose() }.onFailure { error = it.message } }) { Text("Save", color = p.accent) }
                TextButton(onClick = { if (editor.dirty) confirmExit = true else onClose() }) { Text("Close", color = p.textSecondary) }
            }

            // ---- properties of the selected control ----
            val sel = layout.elements(mode).firstOrNull { it.id == selected }
            if (sel != null) Column(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).width(300.dp).fillMaxHeight().padding(top = 64.dp, end = 8.dp, bottom = 8.dp)
                .background(p.elevated.copy(alpha = 0.95f), MaterialTheme.shapes.medium).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(sel.id, color = p.text, fontSize = 16.sp)
                OutlinedTextField(sel.label, { v -> edit { editor.update(mode, sel.id, coalesce = true) { it.copy(label = v.take(12)) } } }, label = { Text("Label") }, singleLine = true)
                SliderRow("Width", sel.widthDp, 24f..240f, format = { "${it.toInt()} dp" }) { v -> edit { editor.resize(mode, sel.id, v, sel.heightDp) } }
                SliderRow("Height", sel.heightDp, 24f..240f, format = { "${it.toInt()} dp" }) { v -> edit { editor.resize(mode, sel.id, sel.widthDp, v) } }
                SliderRow("Opacity", sel.opacity, 0.1f..1f, format = { "${(it * 100).toInt()}%" }) { v -> edit { editor.update(mode, sel.id, coalesce = true) { it.copy(opacity = v) } } }
                SliderRow("Corner radius", sel.cornerRadius, 0f..0.5f, format = { "${(it * 200).toInt()}%" }) { v -> edit { editor.update(mode, sel.id, coalesce = true) { it.copy(cornerRadius = v) } } }
                if (sel.kind == ElementKind.BUTTON) {
                    ChoiceRow("Behaviour", ButtonBehavior.entries, sel.behavior, label = { it.name.lowercase().replace('_', ' ') }) { b -> edit { editor.update(mode, sel.id) { it.copy(behavior = b) } } }
                    ChoiceRow("Visible", Visibility.entries, sel.visibility, label = { it.name.lowercase().replace('_', ' ') }) { v -> edit { editor.update(mode, sel.id) { it.copy(visibility = v) } } }
                    val options: List<InputAction> = Glfw.names.keys.sorted().map { InputAction.Key(it) as InputAction } +
                        listOf(InputAction.Mouse(0), InputAction.Mouse(1), InputAction.Mouse(2), InputAction.Scroll(1f), InputAction.Scroll(-1f), InputAction.ShowKeyboard, InputAction.ToggleOverlay, InputAction.ToggleRecording, InputAction.LauncherMenu) +
                        layout.modes.keys.filter { it != mode }.map { InputAction.SwitchMode(it) }
                    ChoiceRow("Action", options, sel.actions.firstOrNull() ?: options.first(), label = { it.describe() }) { a -> edit { editor.update(mode, sel.id) { it.copy(actions = listOf(a)) } } }
                }
                Row {
                    TextButton(onClick = { edit { editor.duplicate(mode, sel.id) } }) { Text("Duplicate") }
                    TextButton(onClick = { edit { editor.delete(mode, sel.id) }; selected = null }) { Text("Delete", color = p.danger) }
                }
            }
            error?.let { Snackbar(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = { error = null }) { Text("OK") } }) { Text(it) } }
        }

        if (confirmExit) ConfirmDialog("Discard changes?", "Unsaved changes to this layout will be lost.", "Discard", true, onConfirm = onClose, onDismiss = { confirmExit = false })
        if (importing) {
            var text by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { importing = false }, title = { Text("Import layout") },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Paste layout JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) },
                confirmButton = { TextButton(onClick = { runCatching { store.import(text) }.onSuccess { error = "Imported as '${it.name}' (${it.id}). Select it in the profile editor." }.onFailure { error = "Invalid layout: ${it.message}" }; importing = false }) { Text("Import") } },
                dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } })
        }
    }
}
