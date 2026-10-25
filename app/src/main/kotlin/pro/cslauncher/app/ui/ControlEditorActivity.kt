package pro.cslauncher.app.ui

import pro.cslauncher.core.settings.withVirtualMouse
import pro.cslauncher.core.settings.withPointerOnTouch
import pro.cslauncher.core.settings.pointerOnTouch
import androidx.compose.runtime.collectAsState
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlin.math.abs

/**
 * Full-screen control layout editor.
 *  - tap a control to edit it (keys, behaviour, size, colour…), drag to move, drag the corner handle to resize;
 *  - the ⚙ button opens the editor menu (add controls, undo/redo, grid, modes, layouts, import/export, save);
 *  - [ControlEditorOverlayActivity] runs the same editor translucently on top of a running game.
 */
open class ControlEditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAYOUT = "layout"
        const val RESULT_LAYOUT = "layoutId"
        fun open(ctx: Context, layoutId: String) = ctx.startActivity(Intent(ctx, ControlEditorActivity::class.java).putExtra(EXTRA_LAYOUT, layoutId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    protected open val overGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        val store = ControlLayoutStore(Services.dirs.config.resolve("controls"))
        val initial = runCatching { store.load(intent.getStringExtra(EXTRA_LAYOUT) ?: ControlLayoutStore.DEFAULT_ID) }.getOrElse { DefaultLayouts.defaultTouch() }
        setContent {
            val s by Services.settings.state.collectAsState()
            CsTheme(s.theme, s.reduceTransparency, s.reduceMotion) {
                Editor(store, initial, onClose = { saved -> if (saved != null) setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_LAYOUT, saved)); finish() })
            }
        }
    }

    private enum class Drag { NONE, MOVE, RESIZE }

    @Composable
    private fun Editor(store: ControlLayoutStore, initial: ControlLayout, onClose: (savedId: String?) -> Unit) {
        val p = LocalPalette.current
        var editor by remember { mutableStateOf(LayoutEditor(initial)) }
        var version by remember { mutableIntStateOf(0) }
        val layout = remember(version, editor) { editor.current }
        var mode by remember { mutableStateOf(initial.defaultMode) }
        var selected by remember { mutableStateOf<String?>(null) }
        var editing by remember { mutableStateOf<String?>(null) }
        var grid by remember { mutableStateOf(true) }
        var menu by remember { mutableStateOf(false) }
        var layouts by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var newMode by remember { mutableStateOf(false) }
        var confirmExit by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var size by remember { mutableStateOf(IntSize(0, 0)) }
        val density = LocalDensity.current.density
        val settings by Services.settings.state.collectAsState()
        val geo = Geometry(maxOf(size.width, 1), maxOf(size.height, 1), density, paddingPx = (settings.touch.safeAreaPaddingDp * density).toInt())
        val ready = size.width > 8 && size.height > 8
        val gridStep = 0.0125f
        val handlePx = 22 * density
        fun edit(f: () -> Unit) { f(); version++ }
        fun save(): Boolean = runCatching { store.save(editor.current); editor.markSaved() }.onFailure { message = "Cannot save: ${it.message}" }.isSuccess
        BackHandler { when { editing != null -> editing = null; menu -> menu = false; editor.dirty -> confirmExit = true; else -> onClose(null) } }

        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(ControlLayoutCodec.encode(editor.current).toByteArray()) } }
                .onSuccess { message = "Layout exported" }.onFailure { message = "Export failed: ${it.message}" }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }.also { require(it.length < 1_000_000) { "File too large" } } }
                .mapCatching { store.import(it) }
                .onSuccess { l -> editor = LayoutEditor(l); mode = l.defaultMode; selected = null; version++; message = "Imported '${l.name}'" }
                .onFailure { message = "Invalid layout: ${it.message}" }
        }

        Box(Modifier.fillMaxSize().background(if (overGame) Color(0x66000000) else Color(0xFF15171C))) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }
                .pointerInput(mode, version, grid, ready) {
                    if (!ready) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val els = layout.elements(mode)
                        val sel = els.firstOrNull { it.id == selected }
                        val selRect = sel?.let { geo.rectOf(it) }
                        var drag = Drag.NONE; var targetId: String? = null
                        var offX = 0f; var offY = 0f
                        if (selRect != null && abs(down.position.x - selRect.right) < handlePx && abs(down.position.y - selRect.bottom) < handlePx) {
                            drag = Drag.RESIZE; targetId = sel.id
                        } else {
                            val hit = els.lastOrNull { geo.rectOf(it).contains(down.position.x, down.position.y) }
                            if (hit != null) { targetId = hit.id; val r = geo.rectOf(hit); offX = r.cx - down.position.x; offY = r.cy - down.position.y; drag = Drag.MOVE }
                        }
                        var moved = false; var total = Offset.Zero
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            total += c.positionChange()
                            if (!moved && total.getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved && targetId != null) {
                                c.consume()
                                val id = targetId
                                if (drag == Drag.MOVE) {
                                    val (nx, ny) = geo.normalised(c.position.x + offX, c.position.y + offY)
                                    editor.move(mode, id, nx, ny, if (grid) gridStep else null); selected = id
                                } else if (drag == Drag.RESIZE && selRect != null) {
                                    val w = ((c.position.x - selRect.left) / density).coerceIn(24f, 320f)
                                    val h = ((c.position.y - selRect.top) / density).coerceIn(24f, 320f)
                                    val snap = { v: Float -> if (grid) (Math.round(v / 4f) * 4f) else v }
                                    editor.resize(mode, id, snap(w), snap(h))
                                }
                                version++
                            }
                        }
                        editor.endGesture()
                        if (!moved) {
                            if (targetId != null) { selected = targetId; editing = targetId } else selected = null
                        }
                    }
                }) {
                if (!ready) return@Canvas
                if (grid) {
                    var g = 0f
                    while (g <= 1.0001f) {
                        val x = geo.safeLeft + g * geo.safeWidth; val y = geo.safeTop + g * geo.safeHeight
                        drawLine(Color(0x12FFFFFF), Offset(x, geo.safeTop.toFloat()), Offset(x, (geo.safeTop + geo.safeHeight).toFloat()))
                        drawLine(Color(0x12FFFFFF), Offset(geo.safeLeft.toFloat(), y), Offset((geo.safeLeft + geo.safeWidth).toFloat(), y))
                        g += 0.05f
                    }
                }
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F1F5.toInt(); textSize = 12 * density; textAlign = android.graphics.Paint.Align.CENTER }
                layout.elements(mode).forEach { e ->
                    val r = geo.rectOf(e); val isSel = e.id == selected
                    val col = Color(e.colorArgb.toInt()).copy(alpha = e.opacity.coerceIn(0.2f, 1f))
                    val radius = CornerRadius(minOf(r.width, r.height) * e.cornerRadius)
                    when (e.kind) {
                        ElementKind.JOYSTICK -> { drawCircle(col, minOf(r.width, r.height) / 2, Offset(r.cx, r.cy)); drawCircle(Color(0xFF777B87), minOf(r.width, r.height) * 0.19f, Offset(r.cx, r.cy)) }
                        else -> drawRoundRect(col, Offset(r.left, r.top), Size(r.width, r.height), radius)
                    }
                    drawRoundRect(if (isSel) Color(0xFFC5CBD5) else Color(0x66FFFFFF), Offset(r.left, r.top), Size(r.width, r.height), radius, style = Stroke(if (isSel) 3f else 1.2f))
                    val text = e.label.ifBlank { if (e.kind == ElementKind.BUTTON) e.actions.joinToString("+") { it.describe() } else e.kind.name.lowercase() }.take(12)
                    drawContext.canvas.nativeCanvas.drawText(text, r.cx, r.cy + 4 * density, paint)
                    if (isSel) drawCircle(Color(0xFFC5CBD5), 8 * density, Offset(r.right, r.bottom))
                }
            }

            // ---- floating editor button (top centre) and mode indicator ----
            Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(p.elevated.copy(alpha = 0.95f), CircleShape).border(1.dp, p.border, CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center) { Text("⚙", color = p.text, fontSize = 20.sp) }
                Spacer(Modifier.width(8.dp))
                Text("${layout.name} · $mode${if (editor.dirty) " · unsaved" else ""}", color = p.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.background(p.elevated.copy(alpha = 0.85f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (layout.elements(mode).isEmpty()) Text("Empty mode — open ⚙ and add a button", color = p.textSecondary, modifier = Modifier.align(Alignment.Center))
            message?.let { Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton(onClick = { message = null }) { Text("OK") } }) { Text(it) } }
        }

        // ---------------------------------------------------------------- editor menu
        if (menu) Dialog(onDismissRequest = { menu = false }) {
            Column(Modifier.width(360.dp).background(p.elevated, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Edit controls", color = p.text, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Mode", color = p.textSecondary, fontSize = 12.sp)
                FlowRowCompat { layout.modes.keys.forEach { m -> FilterChip(m == mode, { mode = m; selected = null }, label = { Text(m) }, modifier = Modifier.padding(end = 4.dp)) }
                    AssistChip(onClick = { newMode = true }, label = { Text("+ Mode") }) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                MenuItem("+ Add button") { edit { editor.add(mode, ControlElement(id = "button", label = "", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_SPACE)))) }; selected = editor.current.elements(mode).last().id; menu = false }
                MenuItem("+ Add joystick") { edit { editor.add(mode, ControlElement(id = "stick", kind = ElementKind.JOYSTICK, x = 0.18f, y = 0.65f, widthDp = 130f, heightDp = 130f, opacity = 0.5f, joystick = JoystickConfig())) }; menu = false }
                MenuItem("+ Add touchpad") { edit { editor.add(mode, ControlElement(id = "pad", kind = ElementKind.TOUCHPAD, x = 0.5f, y = 0.5f, widthDp = 220f, heightDp = 140f, opacity = 0.2f, touchpad = TouchpadConfig())) }; menu = false }
                Row {
                    TextButton(onClick = { edit { editor.undo() } }, enabled = editor.canUndo) { Text("Undo") }
                    TextButton(onClick = { edit { editor.redo() } }, enabled = editor.canRedo) { Text("Redo") }
                    FilterChip(grid, { grid = !grid }, label = { Text("Snap to grid") })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                Text("Mouse & touch", color = p.textSecondary, fontSize = 12.sp)
                val cs by pro.cslauncher.app.Services.settings.state.collectAsState()
                fun cursorUp(f: (pro.cslauncher.core.settings.CursorSettings) -> pro.cslauncher.core.settings.CursorSettings) =
                    pro.cslauncher.app.Services.settings.update { it.copy(cursor = f(it.cursor)) }
                FlowRowCompat {
                    FilterChip(cs.cursor.mode == pro.cslauncher.core.settings.CursorMode.TOUCHPAD, { cursorUp { it.withVirtualMouse(it.mode != pro.cslauncher.core.settings.CursorMode.TOUCHPAD) } },
                        label = { Text("Virtual mouse") }, modifier = Modifier.padding(end = 4.dp))
                    FilterChip(cs.cursor.pointerOnTouch, { cursorUp { it.withPointerOnTouch(!it.pointerOnTouch) } }, enabled = cs.cursor.mode != pro.cslauncher.core.settings.CursorMode.TOUCHPAD,
                        label = { Text("Pointer on touch") })
                }
                Text(if (cs.cursor.mode == pro.cslauncher.core.settings.CursorMode.TOUCHPAD) "Menus: drag to move the pointer, tap to click." else "Menus: touch buttons and slots directly.",
                    color = p.textMuted, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                MenuItem("Layouts…") { layouts = true; menu = false }
                MenuItem("Export to file") { exportLauncher.launch("${editor.current.id}.json"); menu = false }
                MenuItem("Import from file") { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); menu = false }
                MenuItem("Copy JSON to clipboard") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS layout", ControlLayoutCodec.encode(editor.current))); message = "Layout JSON copied"; menu = false }
                MenuItem("Paste JSON…") { importing = true; menu = false }
                MenuItem("Reset to default layout") { edit { editor.resetAll(DefaultLayouts.defaultTouch()) }; mode = editor.current.defaultMode; menu = false }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                Row {
                    Button(onClick = { if (save()) { menu = false; message = "Saved" } }) { Text("Save") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { if (save()) onClose(editor.current.id) }) { Text("Save & exit") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { menu = false; if (editor.dirty) confirmExit = true else onClose(null) }) { Text("Exit", color = p.textSecondary) }
                }
            }
        }

        // ---------------------------------------------------------------- per-control properties
        editing?.let { id ->
            val e = layout.elements(mode).firstOrNull { it.id == id }
            if (e == null) editing = null
            else PropertiesDialog(e, layout, mode, onDismiss = { editing = null; editor.endGesture() },
                onChange = { coalesce, f -> edit { editor.update(mode, id, coalesce) { f(it) } } },
                onDuplicate = { edit { editor.duplicate(mode, id) }; editing = null },
                onDelete = { edit { editor.delete(mode, id) }; selected = null; editing = null })
        }

        if (layouts) LayoutsDialog(store, editor.current.id, onDismiss = { layouts = false }, onOpen = { l ->
            if (editor.dirty) save(); editor = LayoutEditor(l); mode = l.defaultMode; selected = null; version++; layouts = false
        }, onMessage = { message = it })

        if (newMode) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { newMode = false }, title = { Text("New mode") },
                text = { OutlinedTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(24) }, label = { Text("Name (a-z, 0-9)") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { edit { editor.addMode(name) }; mode = name }; newMode = false }) { Text("Add") } },
                dismissButton = { TextButton(onClick = { newMode = false }) { Text("Cancel") } })
        }
        if (confirmExit) ConfirmDialog("Discard changes?", "Unsaved changes to this layout will be lost.", "Discard", true, onConfirm = { onClose(null) }, onDismiss = { confirmExit = false })
        if (importing) {
            var text by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { importing = false }, title = { Text("Import layout") },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Paste layout JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) },
                confirmButton = { TextButton(onClick = {
                    runCatching { store.import(text) }.onSuccess { l -> editor = LayoutEditor(l); mode = l.defaultMode; version++; message = "Imported '${l.name}'" }
                        .onFailure { message = "Invalid layout: ${it.message}" }; importing = false }) { Text("Import") } },
                dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } })
        }
    }

    @Composable private fun MenuItem(text: String, onClick: () -> Unit) {
        Text(text, color = LocalPalette.current.text, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
    }

    /** Simple wrapping row (keeps the dependency set minimal). */
    @Composable private fun FlowRowCompat(content: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScrollCompat(), verticalAlignment = Alignment.CenterVertically) { content() }
    }
    @Composable private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

    // ---------------------------------------------------------------- properties dialog
    @Composable
    private fun PropertiesDialog(e: ControlElement, layout: ControlLayout, mode: String, onDismiss: () -> Unit,
                                 onChange: (Boolean, (ControlElement) -> ControlElement) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
        val p = LocalPalette.current
        var pickSlot by remember { mutableStateOf<Int?>(null) }
        var pickJoy by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(0.9f).fillMaxHeight(0.92f).background(p.elevated, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Edit ${e.kind.name.lowercase()}", color = p.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done", color = p.accent) }
                }
                OutlinedTextField(e.label, { v -> onChange(true) { it.copy(label = v.take(12)) } }, label = { Text("Label (empty = key names)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                if (e.kind == ElementKind.BUTTON) {
                    Spacer(Modifier.height(8.dp))
                    Text("Keys pressed together (up to 4)", color = p.textSecondary, fontSize = 12.sp)
                    Row(Modifier.horizontalScrollCompat(), verticalAlignment = Alignment.CenterVertically) {
                        e.actions.forEachIndexed { i, a ->
                            InputChip(selected = false, onClick = { pickSlot = i }, label = { Text(a.describe()) },
                                trailingIcon = { if (e.actions.size > 1) Text("✕", color = p.textSecondary, modifier = Modifier.clickable { onChange(false) { it.copy(actions = it.actions.filterIndexed { j, _ -> j != i }) } }) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                        if (e.actions.size < 4) AssistChip(onClick = { pickSlot = e.actions.size }, label = { Text("+ Key") })
                    }
                    ChoiceRow("Behaviour", ButtonBehavior.entries, e.behavior, label = { behaviourLabel(it) }) { b -> onChange(false) { it.copy(behavior = b) } }
                    ChoiceRow("Visible", Visibility.entries, e.visibility, label = { when (it) { Visibility.ALWAYS -> "Always"; Visibility.IN_GAME -> "In game"; Visibility.IN_MENU -> "In menus" } }) { v -> onChange(false) { it.copy(visibility = v) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(e.swipeable, { v -> onChange(false) { it.copy(swipeable = v) } }); Spacer(Modifier.width(8.dp))
                        Text("Swipeable (slide a finger across to press)", color = p.text, fontSize = 14.sp)
                    }
                }
                if (e.kind == ElementKind.JOYSTICK) {
                    val j = e.joystick ?: JoystickConfig()
                    fun setJ(f: (JoystickConfig) -> JoystickConfig) = onChange(true) { it.copy(joystick = f(it.joystick ?: JoystickConfig())) }
                    ChoiceRow("Role", JoystickRole.entries, j.role, label = { if (it == JoystickRole.MOVEMENT) "Movement (WASD)" else "Camera" }) { r -> setJ { it.copy(role = r) } }
                    SliderRow("Dead zone", j.deadZone, 0f..0.6f, format = { "${(it * 100).toInt()}%" }) { v -> setJ { it.copy(deadZone = v) } }
                    SliderRow("Auto-sprint at", j.sprintThreshold, 0f..1f, format = { if (it < 0.05f) "off" else "${(it * 100).toInt()}%" }) { v -> setJ { it.copy(sprintThreshold = if (v < 0.05f) 0f else v) } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(j.floating, { v -> setJ { it.copy(floating = v) } }); Spacer(Modifier.width(8.dp)); Text("Floating (centre where you touch)", color = p.text, fontSize = 14.sp) }
                    if (j.role == JoystickRole.MOVEMENT) Row(Modifier.horizontalScrollCompat()) {
                        listOf("forward" to j.forward, "back" to j.back, "left" to j.left, "right" to j.right).forEach { (n, k) ->
                            AssistChip(onClick = { pickJoy = n }, label = { Text("$n: ${Glfw.names[k] ?: k}") }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
                if (e.kind == ElementKind.TOUCHPAD) {
                    val t = e.touchpad ?: TouchpadConfig()
                    SliderRow("Sensitivity", t.sensitivity, 0.2f..3f, format = { "%.1fx".format(it) }) { v -> onChange(true) { it.copy(touchpad = t.copy(sensitivity = v)) } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(t.tapToClick, { v -> onChange(false) { it.copy(touchpad = t.copy(tapToClick = v)) } }); Spacer(Modifier.width(8.dp)); Text("Tap to click", color = p.text) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                SliderRow("Width", e.widthDp, 24f..320f, format = { "${it.toInt()} dp" }) { v -> onChange(true) { it.copy(widthDp = v) } }
                SliderRow("Height", e.heightDp, 24f..320f, format = { "${it.toInt()} dp" }) { v -> onChange(true) { it.copy(heightDp = v) } }
                SliderRow("Opacity", e.opacity, 0f..1f, format = { "${(it * 100).toInt()}%" }) { v -> onChange(true) { it.copy(opacity = v) } }
                SliderRow("Corner radius", e.cornerRadius, 0f..0.5f, format = { "${(it * 200).toInt()}%" }) { v -> onChange(true) { it.copy(cornerRadius = v) } }
                Text("Colour", color = p.textSecondary, fontSize = 12.sp)
                Row(Modifier.horizontalScrollCompat().padding(vertical = 6.dp)) {
                    SWATCHES.forEach { c ->
                        Box(Modifier.padding(end = 6.dp).size(30.dp).background(Color(c.toInt()), CircleShape)
                            .border(if (c == e.colorArgb) 3.dp else 1.dp, if (c == e.colorArgb) p.accent else p.border, CircleShape)
                            .clickable { onChange(false) { it.copy(colorArgb = c) } })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.border)
                Row {
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                    TextButton(onClick = onDelete) { Text("Delete", color = p.danger) }
                }
            }
        }
        pickSlot?.let { slot ->
            KeyPicker(layout, mode, onDismiss = { pickSlot = null }) { a ->
                onChange(false) { el -> val list = el.actions.toMutableList(); if (slot < list.size) list[slot] = a else list += a; el.copy(actions = list.take(4)) }
                pickSlot = null
            }
        }
        pickJoy?.let { which ->
            KeyPicker(layout, mode, keysOnly = true, onDismiss = { pickJoy = null }) { a ->
                val k = (a as? InputAction.Key)?.key ?: return@KeyPicker
                onChange(false) { el -> val j = el.joystick ?: JoystickConfig()
                    el.copy(joystick = when (which) { "forward" -> j.copy(forward = k); "back" -> j.copy(back = k); "left" -> j.copy(left = k); else -> j.copy(right = k) }) }
                pickJoy = null
            }
        }
    }

    private fun behaviourLabel(b: ButtonBehavior) = when (b) {
        ButtonBehavior.HOLD -> "Hold"; ButtonBehavior.TOGGLE -> "Toggle"; ButtonBehavior.TAP -> "Tap"
        ButtonBehavior.DOUBLE_TAP -> "Double tap"; ButtonBehavior.REPEAT -> "Repeat"
    }

    /** Searchable key/mouse/special action picker. */
    @Composable
    private fun KeyPicker(layout: ControlLayout, mode: String, keysOnly: Boolean = false, onDismiss: () -> Unit, onPick: (InputAction) -> Unit) {
        val p = LocalPalette.current
        var q by remember { mutableStateOf("") }
        val groups: List<Pair<String, List<InputAction>>> = remember(layout, mode, keysOnly) {
            val keys = Glfw.names.keys
            fun k(r: Iterable<Int>) = r.filter { it in keys }.map { InputAction.Key(it) as InputAction }
            buildList {
                if (!keysOnly) add("Mouse" to listOf(InputAction.Mouse(Glfw.MOUSE_LEFT), InputAction.Mouse(Glfw.MOUSE_RIGHT), InputAction.Mouse(Glfw.MOUSE_MIDDLE), InputAction.Scroll(1f), InputAction.Scroll(-1f)))
                add("Common" to k(listOf(Glfw.KEY_SPACE, Glfw.KEY_LEFT_SHIFT, Glfw.KEY_LEFT_CONTROL, Glfw.KEY_ESCAPE, Glfw.KEY_E, Glfw.KEY_Q, Glfw.KEY_F, Glfw.KEY_T, Glfw.KEY_TAB, Glfw.KEY_ENTER)))
                add("Letters" to k(Glfw.KEY_A..Glfw.KEY_Z))
                add("Numbers" to k(Glfw.KEY_0..Glfw.KEY_9))
                add("Function" to k(Glfw.KEY_F1..Glfw.KEY_F12))
                add("Modifiers" to k(listOf(Glfw.KEY_LEFT_SHIFT, Glfw.KEY_RIGHT_SHIFT, Glfw.KEY_LEFT_CONTROL, Glfw.KEY_RIGHT_CONTROL, Glfw.KEY_LEFT_ALT, Glfw.KEY_RIGHT_ALT, Glfw.KEY_CAPS_LOCK)))
                add("Navigation" to k(listOf(Glfw.KEY_UP, Glfw.KEY_DOWN, Glfw.KEY_LEFT, Glfw.KEY_RIGHT, Glfw.KEY_PAGE_UP, Glfw.KEY_PAGE_DOWN, Glfw.KEY_HOME, Glfw.KEY_END, Glfw.KEY_INSERT, Glfw.KEY_DELETE, Glfw.KEY_BACKSPACE)))
                add("Symbols" to k(listOf(Glfw.KEY_MINUS, Glfw.KEY_EQUAL, Glfw.KEY_LEFT_BRACKET, Glfw.KEY_RIGHT_BRACKET, Glfw.KEY_BACKSLASH, Glfw.KEY_SEMICOLON, Glfw.KEY_APOSTROPHE, Glfw.KEY_COMMA, Glfw.KEY_PERIOD, Glfw.KEY_SLASH, Glfw.KEY_GRAVE)))
                if (!keysOnly) add("Launcher" to (listOf(InputAction.ShowKeyboard, InputAction.ToggleOverlay, InputAction.ToggleRecording, InputAction.LauncherMenu, InputAction.ToggleVirtualMouse) +
                    layout.modes.keys.filter { it != mode }.map { InputAction.SwitchMode(it) }))
            }
        }
        Dialog(onDismissRequest = onDismiss) {
            Column(Modifier.width(380.dp).heightIn(max = 520.dp).background(p.elevated, RoundedCornerShape(16.dp)).padding(12.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search keys") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    groups.forEach { (title, items) ->
                        val shown = items.filter { q.isBlank() || it.describe().contains(q, ignoreCase = true) }
                        if (shown.isNotEmpty()) {
                            item { Text(title, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                            items(shown.chunked(4)) { row ->
                                Row {
                                    row.forEach { a ->
                                        Box(Modifier.weight(1f).padding(3.dp).height(40.dp).background(p.card, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
                                            .clickable { onPick(a) }, contentAlignment = Alignment.Center) { Text(a.describe(), color = p.text, fontSize = 13.sp, maxLines = 1) }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- layout manager
    @Composable
    private fun LayoutsDialog(store: ControlLayoutStore, currentId: String, onDismiss: () -> Unit, onOpen: (ControlLayout) -> Unit, onMessage: (String) -> Unit) {
        val p = LocalPalette.current
        var all by remember { mutableStateOf(store.list()) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ControlLayout?>(null) }
        Dialog(onDismissRequest = onDismiss) {
            Column(Modifier.width(380.dp).heightIn(max = 520.dp).background(p.elevated, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Layouts", color = p.text, fontSize = 18.sp)
                LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 8.dp)) {
                    items(all, key = { it.id }) { l ->
                        Row(Modifier.fillMaxWidth().clickable { onOpen(l) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name + if (l.id == currentId) "  (editing)" else "", color = p.text)
                                Text("${l.modes.size} modes · ${l.modes.values.sumOf { it.size }} controls", color = p.textSecondary, fontSize = 12.sp)
                            }
                            if (l.id != ControlLayoutStore.DEFAULT_ID) TextButton(onClick = { deleting = l }) { Text("Delete", color = p.danger) }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { creating = true }) { Text("New layout") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
        if (creating) {
            var name by remember { mutableStateOf("") }
            var base by remember { mutableStateOf(DefaultLayouts.all().first()) }
            AlertDialog(onDismissRequest = { creating = false }, title = { Text("New layout") },
                text = { Column {
                    OutlinedTextField(name, { name = it.take(48) }, label = { Text("Name") }, singleLine = true)
                    ChoiceRow("Start from", DefaultLayouts.all(), base, label = { it.name }) { base = it }
                } },
                confirmButton = { TextButton(onClick = {
                    val id = LayoutIds.fromName(name, all.map { it.id }.toSet())
                    runCatching { base.copy(id = id, name = name.ifBlank { "Layout" }).also(store::save) }
                        .onSuccess { l -> all = store.list(); creating = false; onOpen(l) }.onFailure { onMessage("Cannot create: ${it.message}") }
                }) { Text("Create") } },
                dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
        }
        deleting?.let { l ->
            ConfirmDialog("Delete '${l.name}'?", "Profiles using it fall back to the default layout.", "Delete", true,
                onConfirm = { runCatching { store.delete(l.id) }.onFailure { onMessage(it.message ?: "Delete failed") }; all = store.list(); deleting = null },
                onDismiss = { deleting = null })
        }
    }
}

private val SWATCHES = listOf(0xFF1E1E26, 0xFF30343D, 0xFF4A4F5C, 0xFFC5CBD5, 0xFFFFFFFF, 0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFFF9A825, 0xFF00838F)

/** Same editor, translucent, in the game process: edits are applied to the running game when saved. */
class ControlEditorOverlayActivity : ControlEditorActivity() {
    override val overGame = true
}
