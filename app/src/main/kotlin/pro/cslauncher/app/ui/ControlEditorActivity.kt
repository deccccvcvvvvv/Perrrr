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
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
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
import pro.cslauncher.app.ui.screens.SwitchRow
import pro.cslauncher.app.ui.screens.LinkButton
import pro.cslauncher.app.ui.screens.AppTextField
import pro.cslauncher.app.ui.theme.*
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
        val mono = remember { runCatching { androidx.core.content.res.ResourcesCompat.getFont(this@ControlEditorActivity, pro.cslauncher.app.R.font.monocraft) }.getOrNull() }
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
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0F1F5.toInt(); textSize = 11 * density; textAlign = android.graphics.Paint.Align.CENTER; typeface = mono }
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

            // ---- floating editor button (top centre) and layout / mode indicator ----
            Row(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelIconButton("settings", "Editor menu", { menu = true }, size = 38.dp)
                PixelIconButton("back", "Undo", { edit { editor.undo() } }, enabled = editor.canUndo, size = 38.dp)
                Box(Modifier.background(Color(0xE0202022)).border(2.dp, Color(0xFF0E0E0F)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("${layout.name} · $mode${if (editor.dirty) " · unsaved" else ""}", color = if (editor.dirty) Color(0xFFFFD866) else Color(0xFFCFD0D2),
                        fontFamily = Monocraft, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                }
                PixelIconButton("check", "Save", { if (save()) message = "Saved" }, style = if (editor.dirty) PxStyle.PRIMARY else PxStyle.NORMAL, size = 38.dp)
            }
            if (layout.elements(mode).isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                PixelIcon("gamepad", size = 40.dp)
                Spacer(Modifier.height(6.dp))
                Text("This mode is empty. Open the menu to add a button.", color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp)
            }
            message?.let { msg ->
                LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); if (message == msg) message = null }
                PixelPanel(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).widthIn(max = 520.dp), padding = 6.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = Color.White, fontFamily = Monocraft, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp))
                        Spacer(Modifier.width(8.dp))
                        PixelButton("OK", { message = null }, height = 28.dp, fontSize = 10.sp)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- editor menu (two columns: fits a landscape phone)
        if (menu) PixelDialog({ menu = false }, "Edit controls", widthFraction = 0.86f, buttons = {
            PixelButton("Exit", { menu = false; if (editor.dirty) confirmExit = true else onClose(null) }, height = 34.dp, fontSize = 11.sp)
            PixelButton("Save", { if (save()) { menu = false; message = "Saved" } }, icon = "check", height = 34.dp, fontSize = 11.sp)
            PixelButton("Save & exit", { if (save()) onClose(editor.current.id) }, style = PxStyle.PRIMARY, icon = "door", height = 34.dp, fontSize = 11.sp)
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    GroupLabel("Mode")
                    Row(Modifier.horizontalScrollCompat(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelTabs(layout.modes.keys.toList(), mode, { it }, { mode = it; selected = null }, height = 32.dp)
                        PixelButton("Mode", { newMode = true }, icon = "plus", height = 32.dp, fontSize = 10.sp)
                    }
                    GroupLabel("Add")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelTile("plus", "Button", {
                            edit { editor.add(mode, ControlElement(id = "button", label = "", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_SPACE)))) }
                            selected = editor.current.elements(mode).last().id; menu = false }, Modifier.weight(1f))
                        PixelTile("gamepad", "Joystick", {
                            edit { editor.add(mode, ControlElement(id = "stick", kind = ElementKind.JOYSTICK, x = 0.18f, y = 0.65f, widthDp = 130f, heightDp = 130f, opacity = 0.5f, joystick = JoystickConfig())) }
                            menu = false }, Modifier.weight(1f))
                        PixelTile("mouse", "Touchpad", {
                            edit { editor.add(mode, ControlElement(id = "pad", kind = ElementKind.TOUCHPAD, x = 0.5f, y = 0.5f, widthDp = 220f, heightDp = 140f, opacity = 0.2f, touchpad = TouchpadConfig())) }
                            menu = false }, Modifier.weight(1f))
                    }
                    GroupLabel("Edit")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinkButton("Undo", { edit { editor.undo() } }, enabled = editor.canUndo, icon = "back")
                        LinkButton("Redo", { edit { editor.redo() } }, enabled = editor.canRedo, icon = "refresh")
                        Spacer(Modifier.width(4.dp))
                        Text("Grid", color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp)
                        PixelToggle(grid, { grid = it })
                    }
                }
                Column(Modifier.weight(1f)) {
                    val cs by Services.settings.state.collectAsState()
                    fun cursorUp(f: (pro.cslauncher.core.settings.CursorSettings) -> pro.cslauncher.core.settings.CursorSettings) =
                        Services.settings.update { it.copy(cursor = f(it.cursor)) }
                    val virtual = cs.cursor.mode == pro.cslauncher.core.settings.CursorMode.TOUCHPAD
                    GroupLabel("Mouse & touch")
                    SwitchRow("Virtual mouse", if (virtual) "Menus: drag to move the pointer, tap to click" else "Menus: touch buttons and slots directly", virtual) {
                        cursorUp { c -> c.withVirtualMouse(!virtual) } }
                    SwitchRow("Pointer on touch", "Show the cursor where you touch", cs.cursor.pointerOnTouch, enabled = !virtual) {
                        cursorUp { c -> c.withPointerOnTouch(!c.pointerOnTouch) } }
                    GroupLabel("Layout")
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinkButton("Layouts", { layouts = true; menu = false }, icon = "list")
                        LinkButton("Export", { exportLauncher.launch("${editor.current.id}.json"); menu = false }, icon = "upload")
                        LinkButton("Import", { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); menu = false }, icon = "folder")
                        LinkButton("Copy JSON", {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS layout", ControlLayoutCodec.encode(editor.current)))
                            message = "Layout JSON copied"; menu = false }, icon = "book")
                        LinkButton("Paste JSON", { importing = true; menu = false }, icon = "edit")
                        LinkButton("Reset", { edit { editor.resetAll(DefaultLayouts.defaultTouch()) }; mode = editor.current.defaultMode; menu = false }, icon = "warning", style = PxStyle.DANGER)
                    }
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
            PixelDialog({ newMode = false }, "New mode", widthFraction = 0.5f, buttons = {
                PixelButton("Cancel", { newMode = false }, height = 34.dp, fontSize = 11.sp)
                PixelButton("Add", { if (name.isNotBlank()) { edit { editor.addMode(name) }; mode = name }; newMode = false }, style = PxStyle.PRIMARY,
                    enabled = name.isNotBlank(), icon = "plus", height = 34.dp, fontSize = 11.sp)
            }) {
                AppTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(24) }, "Name (a-z, 0-9)", placeholder = "building")
                Text("Switch modes in game with a \"Switch mode\" button.", color = Color(0xFF9A9B9D), fontFamily = Monocraft, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (confirmExit) ConfirmDialog("Discard changes?", "Unsaved changes to this layout will be lost.", "Discard", true, onConfirm = { onClose(null) }, onDismiss = { confirmExit = false })
        if (importing) {
            var text by remember { mutableStateOf("") }
            PixelDialog({ importing = false }, "Paste layout JSON", widthFraction = 0.7f, buttons = {
                PixelButton("Cancel", { importing = false }, height = 34.dp, fontSize = 11.sp)
                PixelButton("Import", {
                    runCatching { store.import(text) }.onSuccess { l -> editor = LayoutEditor(l); mode = l.defaultMode; version++; message = "Imported '${l.name}'" }
                        .onFailure { message = "Invalid layout: ${it.message}" }; importing = false
                }, style = PxStyle.PRIMARY, enabled = text.isNotBlank(), icon = "check", height = 34.dp, fontSize = 11.sp)
            }) {
                PixelTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 180.dp), placeholder = "{ \"format\": … }", singleLine = false, minLines = 4)
            }
        }
    }

    @Composable private fun GroupLabel(text: String) {
        Text(text.uppercase(), color = Color(0xFF9A9B9D), fontFamily = Monocraft, fontSize = 9.sp, lineHeight = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }

    @Composable private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

    // ---------------------------------------------------------------- properties dialog
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PropertiesDialog(e: ControlElement, layout: ControlLayout, mode: String, onDismiss: () -> Unit,
                                 onChange: (Boolean, (ControlElement) -> ControlElement) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
        var pickSlot by remember { mutableStateOf<Int?>(null) }
        var pickJoy by remember { mutableStateOf<String?>(null) }
        val title = when (e.kind) { ElementKind.BUTTON -> "Edit button"; ElementKind.JOYSTICK -> "Edit joystick"; else -> "Edit touchpad" }
        PixelDialog(onDismiss, title, widthFraction = 0.9f, buttons = {
            PixelButton("Delete", onDelete, style = PxStyle.DANGER, icon = "trash", height = 34.dp, fontSize = 11.sp)
            PixelButton("Duplicate", onDuplicate, icon = "plus", height = 34.dp, fontSize = 11.sp)
            PixelButton("Done", onDismiss, style = PxStyle.PRIMARY, icon = "check", height = 34.dp, fontSize = 11.sp)
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- what it does
                Column(Modifier.weight(1f)) {
                    AppTextField(e.label, { v -> onChange(true) { it.copy(label = v.take(12)) } }, "Label", placeholder = "empty = key names")
                    if (e.kind == ElementKind.BUTTON) {
                        GroupLabel("Keys pressed together (up to 4)")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            e.actions.forEachIndexed { i, a ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelButton(a.describe(), { pickSlot = i }, height = 32.dp, fontSize = 10.sp)
                                    if (e.actions.size > 1) PixelIconButton("close", "Remove ${a.describe()}", {
                                        onChange(false) { it.copy(actions = it.actions.filterIndexed { j, _ -> j != i }) } }, size = 32.dp)
                                }
                            }
                            if (e.actions.size < 4) PixelButton("Key", { pickSlot = e.actions.size }, icon = "plus", height = 32.dp, fontSize = 10.sp)
                        }
                        ChoiceRow("Behaviour", ButtonBehavior.entries, e.behavior, label = { behaviourLabel(it) }) { b -> onChange(false) { it.copy(behavior = b) } }
                        ChoiceRow("Visible", Visibility.entries, e.visibility, label = { when (it) { Visibility.ALWAYS -> "Always"; Visibility.IN_GAME -> "In game"; Visibility.IN_MENU -> "In menus" } }) { v ->
                            onChange(false) { it.copy(visibility = v) } }
                        SwitchRow("Swipeable", "Slide a finger across to press", e.swipeable) { v -> onChange(false) { it.copy(swipeable = v) } }
                    }
                    if (e.kind == ElementKind.JOYSTICK) {
                        val j = e.joystick ?: JoystickConfig()
                        fun setJ(f: (JoystickConfig) -> JoystickConfig) = onChange(true) { it.copy(joystick = f(it.joystick ?: JoystickConfig())) }
                        ChoiceRow("Role", JoystickRole.entries, j.role, label = { if (it == JoystickRole.MOVEMENT) "Movement (WASD)" else "Camera" }) { r -> setJ { it.copy(role = r) } }
                        SliderRow("Dead zone", j.deadZone, 0f..0.6f, format = { "${(it * 100).toInt()}%" }) { v -> setJ { it.copy(deadZone = v) } }
                        SliderRow("Auto-sprint at", j.sprintThreshold, 0f..1f, format = { if (it < 0.05f) "off" else "${(it * 100).toInt()}%" }) { v -> setJ { it.copy(sprintThreshold = if (v < 0.05f) 0f else v) } }
                        SwitchRow("Floating", "Centre where you touch", j.floating) { v -> setJ { it.copy(floating = v) } }
                        if (j.role == JoystickRole.MOVEMENT) {
                            GroupLabel("Direction keys")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("forward" to j.forward, "back" to j.back, "left" to j.left, "right" to j.right).forEach { (n, k) ->
                                    PixelButton("$n: ${Glfw.names[k] ?: k}", { pickJoy = n }, height = 32.dp, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    if (e.kind == ElementKind.TOUCHPAD) {
                        val t = e.touchpad ?: TouchpadConfig()
                        SliderRow("Sensitivity", t.sensitivity, 0.2f..3f, format = { "%.1fx".format(it) }) { v -> onChange(true) { it.copy(touchpad = t.copy(sensitivity = v)) } }
                        SwitchRow("Tap to click", null, t.tapToClick) { v -> onChange(false) { it.copy(touchpad = t.copy(tapToClick = v)) } }
                    }
                }
                // ---- how it looks
                Column(Modifier.weight(1f)) {
                    SliderRow("Width", e.widthDp, 24f..320f, format = { "${it.toInt()} dp" }) { v -> onChange(true) { it.copy(widthDp = v) } }
                    SliderRow("Height", e.heightDp, 24f..320f, format = { "${it.toInt()} dp" }) { v -> onChange(true) { it.copy(heightDp = v) } }
                    SliderRow("Opacity", e.opacity, 0f..1f, format = { "${(it * 100).toInt()}%" }) { v -> onChange(true) { it.copy(opacity = v) } }
                    SliderRow("Corner radius", e.cornerRadius, 0f..0.5f, format = { "${(it * 200).toInt()}%" }) { v -> onChange(true) { it.copy(cornerRadius = v) } }
                    GroupLabel("Colour")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SWATCHES.forEach { c ->
                            val sel = c == e.colorArgb
                            Box(Modifier.size(30.dp).background(if (sel) Color(0xFF6CC349) else Color(0xFF0E0E0F)).padding(if (sel) 3.dp else 2.dp)
                                .background(Color(c.toInt())).clickable { onChange(false) { it.copy(colorArgb = c) } })
                        }
                    }
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
        val perRow = if (LocalConfiguration.current.screenWidthDp >= 600) 6 else 4
        PixelDialog(onDismiss, if (keysOnly) "Pick a key" else "Pick a key or action", widthFraction = 0.8f, scroll = false, buttons = {
            PixelButton("Cancel", onDismiss, height = 34.dp, fontSize = 11.sp)
        }) {
            PixelTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = "Search keys (e.g. shift, F5)")
            LazyColumn(Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                groups.forEach { (title, items) ->
                    val shown = items.filter { q.isBlank() || it.describe().contains(q, ignoreCase = true) }
                    if (shown.isNotEmpty()) {
                        item(key = "h_$title") { GroupLabel(title) }
                        items(shown.chunked(perRow)) { row ->
                            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { a -> PixelButton(a.describe(), { onPick(a) }, Modifier.weight(1f), height = 34.dp, fontSize = 10.sp) }
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
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
        var all by remember { mutableStateOf(store.list()) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ControlLayout?>(null) }
        PixelDialog(onDismiss, "Layouts", widthFraction = 0.62f, scroll = false, buttons = {
            PixelButton("Close", onDismiss, height = 34.dp, fontSize = 11.sp)
            PixelButton("New layout", { creating = true }, style = PxStyle.PRIMARY, icon = "plus", height = 34.dp, fontSize = 11.sp)
        }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(all, key = { it.id }) { l ->
                    val current = l.id == currentId
                    PixelPanel(Modifier.fillMaxWidth().clickable { onOpen(l) }, selected = current, padding = 6.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelIcon("gamepad", size = 22.dp); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name + if (current) "  (editing)" else "", color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
                                Text("${l.modes.size} modes · ${l.modes.values.sumOf { it.size }} controls", color = Color(0xFF9A9B9D), fontFamily = Monocraft, fontSize = 10.sp, lineHeight = 12.sp)
                            }
                            if (l.id != ControlLayoutStore.DEFAULT_ID) PixelIconButton("trash", "Delete ${l.name}", { deleting = l }, style = PxStyle.DANGER, size = 32.dp)
                        }
                    }
                }
            }
        }
        if (creating) {
            var name by remember { mutableStateOf("") }
            var base by remember { mutableStateOf(DefaultLayouts.all().first()) }
            PixelDialog({ creating = false }, "New layout", widthFraction = 0.6f, buttons = {
                PixelButton("Cancel", { creating = false }, height = 34.dp, fontSize = 11.sp)
                PixelButton("Create", {
                    val id = LayoutIds.fromName(name, all.map { it.id }.toSet())
                    runCatching { base.copy(id = id, name = name.ifBlank { "Layout" }).also(store::save) }
                        .onSuccess { l -> all = store.list(); creating = false; onOpen(l) }.onFailure { onMessage("Cannot create: ${it.message}") }
                }, style = PxStyle.PRIMARY, icon = "check", height = 34.dp, fontSize = 11.sp)
            }) {
                AppTextField(name, { name = it.take(48) }, "Name", placeholder = "My layout")
                ChoiceRow("Start from", DefaultLayouts.all(), base, label = { it.name }) { base = it }
            }
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
