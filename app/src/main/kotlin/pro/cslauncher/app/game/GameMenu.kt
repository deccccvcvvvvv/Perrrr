package pro.cslauncher.app.game

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import pro.cslauncher.app.ui.screens.ChoiceRow
import pro.cslauncher.app.ui.screens.SectionTitle
import pro.cslauncher.app.ui.screens.SliderRow
import pro.cslauncher.app.ui.screens.SwitchRow
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.controls.ControlLayout
import pro.cslauncher.core.controls.Glfw
import pro.cslauncher.core.settings.*
import java.io.File
import java.io.RandomAccessFile

/** What the in-game menu needs from the running game (implemented by [GameActivity]). */
interface GameMenuHost {
    val settings: State<LauncherSettings>
    /** Applies immediately to the running controls; saved shortly after the last change. */
    fun applySettings(s: LauncherSettings)
    fun closeMenu()
    fun controlsHidden(): Boolean
    fun toggleControls()
    fun showKeyboard()
    fun editControls()
    fun tapKey(key: Int)
    fun recording(): Boolean
    fun recordingPaused(): Boolean
    fun togglePause()
    fun toggleRecording()
    fun layouts(): List<ControlLayout>
    fun currentLayoutId(): String
    fun switchLayout(id: String)
    fun quitGame()
    fun forceStop()
    val gameLog: File
    val rendererLog: File?
    /** Lines for the Info tab (frame rate, memory, renderer…), refreshed while it is visible. */
    fun infoLines(): List<Pair<String, String>>
}

enum class GameMenuTab(val label: String, val icon: String) { QUICK("Quick", "bolt"), CONTROLS("Tune", "settings"), LOG("Log", "terminal"), INFO("Info", "chart") }

/**
 * Pixel-style side panel over the running game: quick actions, live control tuning, live game/renderer log and
 * performance info. Nothing here polls unless the panel (or the tab) is visible; everything stops when it is closed.
 * Sized for landscape phones: a compact header with live stats, a vertical icon tab rail and full-height content.
 */
@Composable
fun GameMenu(host: GameMenuHost, open: Boolean, initialTab: GameMenuTab = GameMenuTab.QUICK) {
    val reduce = LocalReduceMotion.current
    var tab by remember { mutableStateOf(initialTab) }
    LaunchedEffect(open, initialTab) { if (open) tab = initialTab }
    var stats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(open) { while (open && isActive) { stats = withContext(Dispatchers.Default) { runCatching { host.infoLines() }.getOrDefault(emptyList()) }; delay(1000) } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(tween(if (reduce) 0 else 160)), exit = fadeOut(tween(if (reduce) 0 else 140))) {
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(remember { MutableInteractionSource() }, null) { host.closeMenu() })
        }
        AnimatedVisibility(open,
            enter = if (reduce) EnterTransition.None else slideInHorizontally(tween(220)) { -it } + fadeIn(tween(160)),
            exit = if (reduce) ExitTransition.None else slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.CenterStart)) {
            val screenW = LocalConfiguration.current.screenWidthDp
            PixelPanel(Modifier.fillMaxHeight().width(minOf(480, (screenW * 0.62f).toInt()).coerceAtLeast(300).dp)
                .clickable(remember { MutableInteractionSource() }, null) {}   // taps inside never reach the scrim
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)), padding = 8.dp) {
                // ---------------------------------------------------------------- header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon("grass", size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    PixelTitle("Game menu", fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val fps = stats.firstOrNull { it.first == "Frame rate" }?.second
                        val fpsN = fps?.substringBefore(' ')?.toIntOrNull()
                        if (fps != null) StatChip(fps, when { fpsN == null -> Color.White; fpsN >= 45 -> Color(0xFF7FE05A); fpsN >= 25 -> Color(0xFFFFD94A); else -> Color(0xFFFF7A7A) })
                        stats.firstOrNull { it.first == "Game process memory" }?.second?.let { StatChip("RAM $it", Color(0xFFCFD0D2)) }
                        if (host.recording()) StatChip(if (host.recordingPaused()) "REC II" else "● REC", Color(0xFFFF6B6B))
                    }
                    Spacer(Modifier.width(6.dp))
                    PixelButton("Resume", { host.closeMenu() }, style = PxStyle.PRIMARY, icon = "play", height = 32.dp, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                // ---------------------------------------------------------------- tab rail + content
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(50.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        GameMenuTab.entries.forEach { t -> MenuTabButton(t, t == tab) { tab = t } }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AnimatedContent(tab, transitionSpec = {
                            if (reduce) EnterTransition.None togetherWith ExitTransition.None
                            else (fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 12 }) togetherWith fadeOut(tween(100))
                        }, label = "gameMenuTab") { t ->
                            when (t) {
                                GameMenuTab.QUICK -> QuickTab(host) { tab = it }
                                GameMenuTab.CONTROLS -> ControlsTab(host)
                                GameMenuTab.LOG -> LogTab(host)
                                GameMenuTab.INFO -> InfoTab(host, stats)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String, color: Color) {
    Box(Modifier.background(Color(0xFF1E1E1F)).padding(1.dp).background(Color(0xFF151516)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, color = color, fontFamily = Monocraft, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
    }
}

@Composable
private fun MenuTabButton(t: GameMenuTab, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelButton(null, onClick, Modifier.fillMaxWidth(), style = if (selected) PxStyle.SELECTED else PxStyle.NORMAL, icon = t.icon, height = 36.dp, contentDescription = t.label)
        Text(t.label.uppercase(), color = if (selected) Color(0xFF7FD36B) else Color(0xFF9A9B9D), fontFamily = Monocraft, fontSize = 8.sp, lineHeight = 10.sp,
            maxLines = 1, modifier = Modifier.padding(top = 1.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileGrid(columns: Int, content: @Composable (tile: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 5.dp
        // Floor to whole pixels: rounding the dp width up makes the last tile of a row wrap to the next line.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val w = with(density) { kotlin.math.floor(((maxWidth - gap * (columns - 1)) / columns).toPx() - 1f).toDp() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) { content(Modifier.width(w)) }
    }
}

@Composable
private fun GroupLabel(text: String) =
    Text(text, color = Color(0xFFB8B9BB), fontFamily = Monocraft, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

@Composable
private fun QuickTab(host: GameMenuHost, goTo: (GameMenuTab) -> Unit) {
    val s by host.settings
    var confirm by remember { mutableStateOf<String?>(null) }
    val virtual = s.cursor.mode == CursorMode.TOUCHPAD
    val hidden = host.controlsHidden()
    val rec = host.recording()
    val on = Color(0xFF6CC349)
    fun key(k: Int) { host.closeMenu(); host.tapKey(k) }
    val layouts = remember { host.layouts() }
    var layoutId by remember { mutableStateOf(host.currentLayoutId()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        var i = 0
        GroupLabel("Game")
        TileGrid(5) { tile ->
            PixelTile("list", "Pause Esc", { key(Glfw.KEY_ESCAPE) }, tile.staggerIn(i++))
            PixelTile("chat", "Chat", { key(Glfw.KEY_T) }, tile.staggerIn(i++))
            PixelTile("camera", "Shot F2", { key(Glfw.KEY_F2) }, tile.staggerIn(i++))
            PixelTile("info", "Debug F3", { key(Glfw.KEY_F3) }, tile.staggerIn(i++))
            PixelTile("head", "View F5", { key(Glfw.KEY_F5) }, tile.staggerIn(i++))
            PixelTile("image", "HUD F1", { key(Glfw.KEY_F1) }, tile.staggerIn(i++))
        }
        GroupLabel("Controls")
        TileGrid(5) { tile ->
            PixelTile("mouse", if (virtual) "Mouse ON" else "Mouse OFF", { host.applySettings(s.copy(cursor = s.cursor.withVirtualMouse(!virtual))) }, tile.staggerIn(i++), tint = if (virtual) on else Color.White)
            PixelTile("eye", if (hidden) "Show UI" else "Hide UI", { host.toggleControls() }, tile.staggerIn(i++), tint = if (hidden) Color(0xFFFFD94A) else Color.White)
            PixelTile("keyboard", "Keyboard", { host.closeMenu(); host.showKeyboard() }, tile.staggerIn(i++))
            PixelTile("edit", "Edit", { host.closeMenu(); host.editControls() }, tile.staggerIn(i++))
            PixelTile("settings", "Tune", { goTo(GameMenuTab.CONTROLS) }, tile.staggerIn(i++))
        }
        if (layouts.size > 1) {
            Spacer(Modifier.height(5.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Layout", color = Color(0xFFB8B9BB), fontSize = 10.sp)
                layouts.forEach { l -> PixelButton(l.name, { layoutId = l.id; host.switchLayout(l.id) }, style = if (l.id == layoutId) PxStyle.SELECTED else PxStyle.NORMAL, height = 28.dp, fontSize = 10.sp) }
            }
        }
        GroupLabel("Capture & info")
        TileGrid(5) { tile ->
            PixelTile("record", if (rec) "Stop rec" else "Record", { host.closeMenu(); host.toggleRecording() }, tile.staggerIn(i++), tint = if (rec) Color(0xFFE05050) else Color.White)
            if (rec) PixelTile("stop", if (host.recordingPaused()) "Resume" else "Pause", { host.togglePause() }, tile.staggerIn(i++))
            PixelTile("chart", if (s.touch.showFpsHud) "FPS ON" else "FPS OFF", { host.applySettings(s.copy(touch = s.touch.copy(showFpsHud = !s.touch.showFpsHud))) },
                tile.staggerIn(i++), tint = if (s.touch.showFpsHud) on else Color.White)
            PixelTile("terminal", "Live log", { goTo(GameMenuTab.LOG) }, tile.staggerIn(i++))
            PixelTile("door", "Quit", { confirm = "quit" }, tile.staggerIn(i++))
            PixelTile("stop", "Force stop", { confirm = "kill" }, tile.staggerIn(i++), tint = Color(0xFFE05050))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (virtual) "Virtual mouse: drag anywhere in menus to move the pointer, tap to click."
             else "Direct touch: tap buttons and slots where they are.", color = Color(0xFF9A9B9D), fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Monocraft)
        if (rec) {
            val clean = pro.cslauncher.app.record.RecorderService.state.collectAsState().value.clean
            Text(if (clean) "Clean video: the controls and this menu are not recorded." else "Screen recording: on-screen controls are visible in the video.",
                color = if (clean) Color(0xFF7FD36B) else Color(0xFFE0B040), fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Monocraft)
        }
    }
    confirm?.let { c ->
        PixelDialog({ confirm = null }, if (c == "quit") "Quit the game?" else "Force stop?", widthFraction = 0.5f, buttons = {
            PixelButton("Cancel", { confirm = null }, height = 34.dp)
            PixelButton(if (c == "quit") "Save & quit" else "Force stop", { confirm = null; if (c == "quit") host.quitGame() else host.forceStop() },
                style = if (c == "quit") PxStyle.PRIMARY else PxStyle.DANGER, height = 34.dp)
        }) {
            Text(if (c == "quit") "Minecraft saves the world and closes, then you return to the launcher."
                 else "The game is killed immediately. Progress since the last autosave is lost.", color = Color.White, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ControlsTab(host: GameMenuHost) {
    val s by host.settings
    val t = s.touch; val c = s.cursor
    fun touch(f: (TouchSettings) -> TouchSettings) = host.applySettings(s.copy(touch = f(t)))
    fun cur(f: (CursorSettings) -> CursorSettings) = host.applySettings(s.copy(cursor = f(c)))
    val layouts = remember { host.layouts() }
    var layoutId by remember { mutableStateOf(host.currentLayoutId()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Mouse")
        SwitchRow("Virtual mouse", "Touchpad-style pointer in menus", c.mode == CursorMode.TOUCHPAD) { v -> cur { it.withVirtualMouse(v) } }
        SwitchRow("Show pointer on touch", "Direct touch only", c.pointerOnTouch, enabled = c.mode != CursorMode.TOUCHPAD) { v -> cur { it.withPointerOnTouch(v) } }
        SliderRow("Pointer speed", c.sensitivity, 0.3f..3f, format = { "%.1f×".format(it) }) { v -> cur { it.copy(sensitivity = v) } }
        SliderRow("Pointer size", c.sizeDp, 12f..48f, format = { "${it.toInt()} dp" }) { v -> cur { it.copy(sizeDp = v) } }
        SectionTitle("Camera")
        SliderRow("Look sensitivity", t.lookSensitivityX, 0.2f..3f, format = { "%.1f×".format(it) }) { v -> touch { it.copy(lookSensitivityX = v, lookSensitivityY = v) } }
        SwitchRow("Invert vertical look", null, t.invertY) { v -> touch { it.copy(invertY = v) } }
        ChoiceRow("Tap on the game view", TapAction.entries, t.tapAction, label = { when (it) { TapAction.NONE -> "Nothing"; TapAction.MOUSE_LEFT -> "Attack"; TapAction.MOUSE_RIGHT -> "Use" } }) { v -> touch { it.copy(tapAction = v) } }
        SwitchRow("Long press to break", null, t.longPressBreak) { v -> touch { it.copy(longPressBreak = v) } }
        SectionTitle("On-screen controls")
        SliderRow("Opacity", t.controlsOpacity, 0.1f..1.5f, format = { "${(it * 100).toInt()}%" }) { v -> touch { it.copy(controlsOpacity = v) } }
        SliderRow("Size", t.controlsScale, 0.6f..1.6f, format = { "${(it * 100).toInt()}%" }) { v -> touch { it.copy(controlsScale = v) } }
        SwitchRow("Game menu tab at the top", "Turn off if it covers something; the ☰ buttons still open this menu", t.showMenuHandle) { v -> touch { it.copy(showMenuHandle = v) } }
        SwitchRow("Vibrate on button press", null, t.vibrateOnPress) { v -> touch { it.copy(vibrateOnPress = v) } }
        if (layouts.size > 1) ChoiceRow("Layout", layouts.map { it.id }, layoutId, label = { id -> layouts.first { it.id == id }.name }) { id -> layoutId = id; host.switchLayout(id) }
        Spacer(Modifier.height(6.dp))
        PixelButton("Open control editor", { host.closeMenu(); host.editControls() }, Modifier.fillMaxWidth(), icon = "edit", height = 34.dp, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

private enum class LogSource(val label: String) { GAME("Game"), RENDERER("Renderer") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogTab(host: GameMenuHost) {
    val ctx = LocalContext.current
    var source by remember { mutableStateOf(LogSource.GAME) }
    var problemsOnly by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    val list = rememberLazyListState()
    val file = if (source == LogSource.GAME) host.gameLog else host.rendererLog
    LaunchedEffect(file, problemsOnly) {
        while (isActive) {
            val all = withContext(Dispatchers.IO) { file?.let { tailLines(it, 160_000) } ?: emptyList() }
            lines = (if (problemsOnly) all.filter(::isProblem) else all).takeLast(500)
            delay(700)
        }
    }
    LaunchedEffect(lines.size, follow) { if (follow && lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelTabs(LogSource.entries, source, { it.label }, { source = it }, enabled = { it == LogSource.GAME || host.rendererLog != null }, height = 32.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelButton(if (problemsOnly) "Problems" else "All lines", { problemsOnly = !problemsOnly }, style = if (problemsOnly) PxStyle.SELECTED else PxStyle.NORMAL, height = 32.dp, fontSize = 11.sp, icon = "warning")
            PixelButton(if (follow) "Follow" else "Paused", { follow = !follow }, style = if (follow) PxStyle.SELECTED else PxStyle.NORMAL, height = 32.dp, fontSize = 11.sp)
            PixelButton("Copy", {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Minecraft log", lines.joinToString("\n")))
                android.widget.Toast.makeText(ctx, "Copied ${lines.size} lines", android.widget.Toast.LENGTH_SHORT).show()
            }, height = 32.dp, fontSize = 11.sp, icon = "list")
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().drawBehind { pixelInset(2.dp.toPx(), Color(0xFF101011)) }.padding(6.dp)) {
            if (lines.isEmpty()) Text(if (file?.isFile == true) "No matching lines yet" else "No log yet", color = Color(0xFF8A8B8D), fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(lines) { l ->
                    Text(l, color = lineColor(l), fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace, softWrap = true)
                }
            }
        }
    }
}

private fun isProblem(l: String): Boolean {
    val u = l.uppercase()
    return "ERROR" in u || "WARN" in u || "EXCEPTION" in u || "FATAL" in u || l.trimStart().startsWith("at ") || "CAUSED BY" in u
}
private fun lineColor(l: String): Color {
    val u = l.uppercase()
    return when {
        "ERROR" in u || "FATAL" in u || "EXCEPTION" in u || l.trimStart().startsWith("at ") || "CAUSED BY" in u -> Color(0xFFFF7A7A)
        "WARN" in u -> Color(0xFFFFD94A)
        else -> Color(0xFFD8D9DB)
    }
}

/** Last [maxBytes] of a log file as lines (the first, possibly cut, line is dropped). */
internal fun tailLines(f: File, maxBytes: Int): List<String> {
    if (!f.isFile) return emptyList()
    return runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length(); val start = maxOf(0L, len - maxBytes)
            val buf = ByteArray((len - start).toInt()); raf.seek(start); raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            val ls = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            if (start > 0 && ls.isNotEmpty()) ls.drop(1) else ls
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun InfoTab(host: GameMenuHost, stats: List<Pair<String, String>>) {
    val info = stats.ifEmpty { remember { runCatching { host.infoLines() }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TileGrid(2) { cell ->
            info.forEach { (k, v) ->
                Column(cell.drawBehind { pixelInset(2.dp.toPx(), Color(0xFF1A1A1B)) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(k, color = Color(0xFF9A9B9D), fontSize = 9.sp, lineHeight = 11.sp, fontFamily = Monocraft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontFamily = Monocraft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Frame rate counts frames the game presented in the last second. Updated every second while this menu is open.", color = Color(0xFF8A8B8D), fontSize = 9.sp, lineHeight = 11.sp)
    }
}

/** Resident memory of this (game) process in MB, from /proc/self/statm. */
internal fun processRssMb(): Long = runCatching {
    val pages = File("/proc/self/statm").readText().trim().split(' ')[1].toLong()
    pages * android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / (1024 * 1024)
}.getOrDefault(-1L)
