package pro.cslauncher.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.BuildConfig
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.ControlEditorActivity
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.settings.*
import pro.cslauncher.core.storage.StorageAnalyzer
import pro.cslauncher.core.storage.StorageEntry
import java.nio.file.Files

private enum class Category(val title: String, val subtitle: String) {
    APPEARANCE("Appearance", "Theme, transparency, motion"), PERFORMANCE("Performance", "Presets, renderer, frame limit"),
    CONTROLS("Controls", "Touch, layouts, physical input"), CURSOR("Cursor", "Mode, shape, size, sensitivity"),
    RECORDING("Recording", "Quality, audio, storage"), RUNTIMES("Runtimes & components", "Java, LWJGL and renderer packs"),
    DOWNLOADS("Downloads & network", "Concurrency, versions, API keys"), ACCOUNTS("Accounts", "Microsoft sign-in configuration"),
    STORAGE("Storage", "Usage and cleanup"), PRIVACY("Privacy", "Recent activity"), DIAGNOSTICS("Diagnostics", "Logs and reports"), ABOUT("About", "Version and licenses")
}

@Composable
fun SettingsScreen(vm: LauncherViewModel, onOpen: (Overlay) -> Unit) {
    val p = LocalPalette.current
    var open by rememberSaveableCategory()
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(open?.title ?: "Settings", onBack = if (open != null) ({ open = null }) else null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (open) {
                null -> Category.entries.forEach { c -> ClickRow(c.title, c.subtitle, onClick = { if (c == Category.DIAGNOSTICS) onOpen(Overlay.Logs) else if (c == Category.ACCOUNTS && false) Unit else open = c }); HorizontalDivider(color = p.border) }
                Category.APPEARANCE -> {
                    ChoiceRow("Theme", ThemeChoice.entries, s.theme, label = { when (it) { ThemeChoice.PIXEL -> "Pixel (blocky)"; ThemeChoice.DARK_GRAPHITE -> "Dark Graphite"; ThemeChoice.GLASSMORPHISM -> "Glassmorphism" } }) { t -> vm.updateSettings { it.copy(theme = t) } }
                    SwitchRow("Animated background", "Moving pixel landscape behind the launcher (paused when hidden)", s.animatedBackground, enabled = !s.reduceMotion) { v -> vm.updateSettings { it.copy(animatedBackground = v) } }
                    SwitchRow("Reduce transparency", "Opaque surfaces instead of glass (also used automatically below Android 12)", s.reduceTransparency) { v -> vm.updateSettings { it.copy(reduceTransparency = v) } }
                    SwitchRow("Reduce motion", "Disable non-essential animations", s.reduceMotion) { v -> vm.updateSettings { it.copy(reduceMotion = v) } }
                }
                Category.PERFORMANCE -> {
                    ChoiceRow("Preset", PerformancePreset.entries, s.performancePreset, label = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }) { v -> vm.updateSettings { it.copy(performancePreset = v) } }
                    PerformanceAdvisor.presetEffects(s.performancePreset).forEach { e -> Text("• ${e.setting}: ${e.value}", color = p.textSecondary, fontSize = 13.sp) }
                    Text("Presets change real game options (written to options.txt at launch) and the render scale. They do not overclock or clean memory.", color = p.textMuted, fontSize = 12.sp)
                    ChoiceRow("Default renderer", listOf("auto") + Services.renderers.backends.map { it.id }, s.defaultRenderer, label = { it }) { v -> vm.updateSettings { it.copy(defaultRenderer = v) } }
                    SliderRow("Frame-rate limit", s.frameRateLimit.toFloat(), 0f..120f, steps = 7, format = { if (it < 1) "Game setting" else "${it.toInt()} FPS" }) { v -> vm.updateSettings { it.copy(frameRateLimit = (v / 15).toInt() * 15) } }
                }
                Category.CONTROLS -> {
                    val t = s.touch
                    fun up(f: (TouchSettings) -> TouchSettings) = vm.updateSettings { it.copy(touch = f(it.touch)) }
                    SliderRow("Look sensitivity (horizontal)", t.lookSensitivityX, 0.2f..3f, format = { "%.1f×".format(it) }) { v -> up { it.copy(lookSensitivityX = v) } }
                    SliderRow("Look sensitivity (vertical)", t.lookSensitivityY, 0.2f..3f, format = { "%.1f×".format(it) }) { v -> up { it.copy(lookSensitivityY = v) } }
                    SwitchRow("Invert vertical look", null, t.invertY) { v -> up { it.copy(invertY = v) } }
                    SliderRow("Camera smoothing", t.smoothing, 0f..0.8f, format = { "%.0f%%".format(it * 100) }) { v -> up { it.copy(smoothing = v) } }
                    ChoiceRow("Tap on the game view", TapAction.entries, t.tapAction, label = { when (it) { TapAction.NONE -> "Nothing"; TapAction.MOUSE_LEFT -> "Attack"; TapAction.MOUSE_RIGHT -> "Use / place" } }) { v -> up { it.copy(tapAction = v) } }
                    SwitchRow("Long press to break", null, t.longPressBreak) { v -> up { it.copy(longPressBreak = v) } }
                    SliderRow("Long-press delay", t.longPressMs.toFloat(), 150f..800f, format = { "${it.toInt()} ms" }) { v -> up { it.copy(longPressMs = v.toInt()) } }
                    SwitchRow("Hide touch controls when a keyboard, mouse or controller is used", null, t.hideOverlayWithPhysicalInput) { v -> up { it.copy(hideOverlayWithPhysicalInput = v) } }
                    SwitchRow("Vibrate on button press", null, t.vibrateOnPress) { v -> up { it.copy(vibrateOnPress = v) } }
                    SliderRow("Safe-area padding", t.safeAreaPaddingDp, 0f..32f, format = { "${it.toInt()} dp" }) { v -> up { it.copy(safeAreaPaddingDp = v) } }
                    SliderRow("Controls opacity", t.controlsOpacity, 0.1f..1.5f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(controlsOpacity = v) } }
                    SliderRow("Controls size", t.controlsScale, 0.6f..1.6f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(controlsScale = v) } }
                    SwitchRow("Game menu tab", "A small tab at the top edge opens the in-game menu with every layout", t.showMenuHandle) { v -> up { it.copy(showMenuHandle = v) } }
                    SwitchRow("FPS and memory read-out", "Shown in the top-left corner while playing", t.showFpsHud) { v -> up { it.copy(showFpsHud = v) } }
                    TextButton(onClick = { ControlEditorActivity.open(vm.getApplication(), vm.ui.value.selected?.controlLayoutId ?: "default-touch") }) { Text("Open control layout editor", color = p.accent) }
                }
                Category.CURSOR -> {
                    val c = s.cursor
                    fun up(f: (CursorSettings) -> CursorSettings) = vm.updateSettings { it.copy(cursor = f(it.cursor)) }
                    SwitchRow("Virtual mouse", "Menus: drag anywhere to move a pointer and tap to click. Off: touch buttons and slots directly.", c.mode == CursorMode.TOUCHPAD) { v -> up { it.withVirtualMouse(v) } }
                    SwitchRow("Show pointer on touch", "Direct touch only: draw the pointer where your finger is", c.pointerOnTouch, enabled = c.mode != CursorMode.TOUCHPAD) { v -> up { it.withPointerOnTouch(v) } }
                    ChoiceRow("Mode", CursorMode.entries, c.mode, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> up { it.copy(mode = v) } }
                    Text(when (c.mode) { CursorMode.DESKTOP -> "The pointer jumps to your finger in menus."; CursorMode.TOUCHPAD -> "Drag anywhere to move the pointer like a laptop touchpad; tap to click."
                        CursorMode.IMMERSIVE -> "No visible pointer; touches act directly."; CursorMode.SMART -> "Camera control in game, visible pointer in menus and inventories." }, color = p.textMuted, fontSize = 12.sp)
                    ChoiceRow("Shape", CursorShape.entries, c.shape, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> up { it.copy(shape = v) } }
                    SliderRow("Size", c.sizeDp, 12f..48f, format = { "${it.toInt()} dp" }) { v -> up { it.copy(sizeDp = v) } }
                    SliderRow("Opacity", c.opacity, 0.3f..1f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(opacity = v) } }
                    SliderRow("Sensitivity", c.sensitivity, 0.3f..3f, format = { "%.1f×".format(it) }) { v -> up { it.copy(sensitivity = v) } }
                    SliderRow("Smoothing", c.smoothing, 0f..0.8f, format = { "%.0f%%".format(it * 100) }) { v -> up { it.copy(smoothing = v) } }
                    SwitchRow("Click animation", null, c.clickAnimation) { v -> up { it.copy(clickAnimation = v) } }
                    SwitchRow("Pointer trail", null, c.trail) { v -> up { it.copy(trail = v) } }
                    SwitchRow("Use external mouse when connected", null, c.externalMouse) { v -> up { it.copy(externalMouse = v) } }
                }
                Category.RECORDING -> RecordingSettingsSection(vm, s.recording)
                Category.RUNTIMES -> RuntimesSection(vm)
                Category.DOWNLOADS -> {
                    SliderRow("Parallel downloads", s.downloadConcurrency.toFloat(), 1f..12f, steps = 10, format = { it.toInt().toString() }) { v -> vm.updateSettings { it.copy(downloadConcurrency = v.toInt()) } }
                    Text("Takes effect after restarting the launcher.", color = p.textMuted, fontSize = 12.sp)
                    SwitchRow("Show snapshots", null, s.showSnapshots) { v -> vm.updateSettings { it.copy(showSnapshots = v) } }
                    SwitchRow("Show old alpha/beta versions", null, s.showHistorical) { v -> vm.updateSettings { it.copy(showHistorical = v) } }
                    var key by remember { mutableStateOf(Services.secrets.get("curseforge.apiKey").orEmpty()) }
                    OutlinedTextField(key, { key = it }, label = { Text("CurseForge API key (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("CurseForge requires a personal key from console.curseforge.com. It is stored encrypted with the Android Keystore. Modrinth needs no key.", color = p.textMuted, fontSize = 12.sp)
                    TextButton(onClick = { if (key.isBlank()) Services.secrets.remove("curseforge.apiKey") else Services.secrets.put("curseforge.apiKey", key.trim()); vm.toast("Saved") }) { Text("Save key", color = p.accent) }
                }
                Category.ACCOUNTS -> {
                    var id by remember { mutableStateOf(s.msaClientId) }
                    OutlinedTextField(id, { id = it }, label = { Text("Azure application (client) ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Microsoft sign-in needs an Azure app registration approved for Minecraft services (public client, device-code flow). This ID is not a secret; no credentials are embedded in the app.", color = p.textMuted, fontSize = 12.sp)
                    TextButton(onClick = { vm.updateSettings { it.copy(msaClientId = id.trim()) }; vm.toast("Saved") }) { Text("Save", color = p.accent) }
                    TextButton(onClick = { onOpen(Overlay.Accounts) }) { Text("Manage accounts", color = p.accent) }
                }
                Category.STORAGE -> StorageSection(vm)
                Category.PRIVACY -> {
                    SwitchRow("Recent activity", "Show launches, installs and recordings on the home screen", s.recentActivityEnabled) { v -> vm.updateSettings { it.copy(recentActivityEnabled = v) } }
                    TextButton(onClick = { vm.clearActivity() }) { Text("Clear recent activity", color = p.danger) }
                    Text("Tokens are stored only in the Android Keystore-encrypted store and are never written to logs, reports or exported profiles.", color = p.textMuted, fontSize = 12.sp)
                }
                Category.DIAGNOSTICS -> {}
                Category.ABOUT -> {
                    InfoRow("Version", BuildConfig.VERSION_NAME); InfoRow("Device ABI", Services.abi); InfoRow("Android API", android.os.Build.VERSION.SDK_INT.toString())
                    Text("CS Launcher Pro is an independent launcher and is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Text("Open-source components: Kotlin, kotlinx.coroutines, kotlinx.serialization, AndroidX/Jetpack Compose (Apache-2.0). Imported runtime and renderer packs carry their own licenses (shown in Runtimes & components).", color = p.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun rememberSaveableCategory(): MutableState<Category?> = remember { mutableStateOf(null) }

@Composable
private fun RecordingSettingsSection(vm: LauncherViewModel, r: RecordingSettings) {
    val p = LocalPalette.current
    fun up(f: (RecordingSettings) -> RecordingSettings) = vm.updateSettings { it.copy(recording = f(it.recording)) }
    val encoders = remember { runCatching { Services.device.videoEncoders() }.getOrDefault(emptyList()) }
    ChoiceRow("Resolution", RecordingResolution.entries, r.resolution, label = { when (it) { RecordingResolution.P480 -> "480p"; RecordingResolution.P720 -> "720p"; RecordingResolution.P1080 -> "1080p"; RecordingResolution.NATIVE -> "Native" } }) { v -> up { it.copy(resolution = v) } }
    ChoiceRow("Frame rate", listOf(24, 30, 60), r.fps, label = { "$it FPS" }) { v -> up { it.copy(fps = v) } }
    ChoiceRow("Quality", RecordingQuality.entries, r.quality, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> up { it.copy(quality = v) } }
    if (r.quality == RecordingQuality.CUSTOM) SliderRow("Bitrate", r.customBitrateKbps.toFloat(), 1000f..40000f, format = { "${it.toInt() / 1000} Mbps" }) { v -> up { it.copy(customBitrateKbps = v.toInt()) } }
    val hevc = encoders.any { it.mime == "video/hevc" && it.hardware }
    SwitchRow("Prefer HEVC (smaller files)", if (hevc) "Hardware HEVC encoder available" else "No hardware HEVC encoder on this device", r.preferHevc && hevc, enabled = hevc) { v -> up { it.copy(preferHevc = v) } }
    ChoiceRow("Audio", AudioSourceChoice.entries, r.audio, label = { when (it) { AudioSourceChoice.NONE -> "None"; AudioSourceChoice.MICROPHONE -> "Microphone"; AudioSourceChoice.GAME_AUDIO -> "Game"; AudioSourceChoice.GAME_AND_MICROPHONE -> "Game + mic" } },
        enabled = { android.os.Build.VERSION.SDK_INT >= 29 || (it != AudioSourceChoice.GAME_AUDIO && it != AudioSourceChoice.GAME_AND_MICROPHONE) }) { v -> up { it.copy(audio = v) } }
    if (android.os.Build.VERSION.SDK_INT < 29) Text("Game audio capture needs Android 10 or newer.", color = p.textMuted, fontSize = 12.sp)
    SwitchRow("Also save to the gallery (Movies)", null, r.saveToGallery) { v -> up { it.copy(saveToGallery = v) } }
    SwitchRow("Show recording indicator in game", null, r.showOverlay) { v -> up { it.copy(showOverlay = v) } }
    SectionTitle("Encoders on this device")
    if (encoders.isEmpty()) Text("No AVC/HEVC encoder reported — recording is unavailable.", color = p.warning, fontSize = 13.sp)
    encoders.forEach { e -> Text("${e.name} · ${e.mime.removePrefix("video/")} · up to ${e.maxWidth}×${e.maxHeight}${if (e.hardware) " · hardware" else " · software"}", color = p.textSecondary, fontSize = 12.sp) }
    Text("Recording asks for screen-capture permission each session and shows a notification while active. Files are stored in the launcher's recordings folder.", color = p.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RuntimesSection(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val runtimes = remember(refresh) { runCatching { Services.runtimes.list() }.getOrDefault(emptyList()) }
    val components = remember(refresh) { runCatching { Services.components.list() }.getOrDefault(emptyList()) }
    var pendingRuntime by remember { mutableStateOf<java.io.File?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun work(block: () -> Unit) { busy = true; scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { vm.toast(it.message) } }; busy = false; refresh++ } }
    val pickRuntime = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) work { pendingRuntime = copyToCache(ctx, u) } }
    val pickComponent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) work { val f = copyToCache(ctx, u); try { val c = Services.components.import(f.toPath(), Services.abi); vm.toast("Installed ${c.manifest.name} ${c.manifest.version} (all checksums verified)") } finally { f.delete() } }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
    Text("Android cannot run desktop Java or desktop OpenGL drivers. The game needs a Java runtime built for Android (${Services.abi}) and component packs for LWJGL and a renderer. They are imported as files and verified (SHA-256 per file) before use; nothing is downloaded from unofficial sources automatically.",
        color = p.textSecondary, fontSize = 13.sp)
    SectionTitle("Java runtimes")
    if (runtimes.isEmpty()) Text("None installed.", color = p.warning)
    runtimes.forEach { r ->
        CsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("${r.id} · Java ${r.major}", color = p.text, fontWeight = FontWeight.Medium)
            Text("${r.version} · ${r.source.name.lowercase()}${if (r.libjvm() == null) " · libjvm.so missing" else ""}", color = if (r.libjvm() == null) p.danger else p.textSecondary, fontSize = 12.sp)
            TextButton(onClick = { work { Services.runtimes.remove(r.id, Services.profiles.load().profiles.filter { it.runtimeId == r.id }.map { it.name }) } }) { Text("Remove", color = p.danger) }
        }
    }
    TextButton(onClick = { pickRuntime.launch(arrayOf("*/*")) }) { Text("Import runtime (.tar.gz / .tar.xz / .zip)", color = p.accent) }
    SectionTitle("Component packs")
    if (components.isEmpty()) Text("None installed. LWJGL for Android is required to start the game.", color = p.warning)
    components.forEach { c ->
        CsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("${c.manifest.name} ${c.manifest.version}", color = p.text, fontWeight = FontWeight.Medium)
            Text("${c.manifest.kind} · ${c.manifest.abi} · ${c.manifest.license} · ${c.manifest.provides.joinToString()}", color = p.textSecondary, fontSize = 12.sp)
            Text("Source: ${c.manifest.source}", color = p.textMuted, fontSize = 11.sp)
            Row {
                TextButton(onClick = { work { val bad = Services.components.verify(c); vm.toast(if (bad.isEmpty()) "All files verified" else "Damaged: ${bad.joinToString()}") } }) { Text("Verify", color = p.accent) }
                TextButton(onClick = { work { Services.components.remove(c.manifest.id) } }) { Text("Remove", color = p.danger) }
            }
        }
    }
    TextButton(onClick = { pickComponent.launch(arrayOf("application/zip", "*/*")) }) { Text("Import component pack (.zip with cs-component.json)", color = p.accent) }

    pendingRuntime?.let { f ->
        var id by remember { mutableStateOf(f.name.substringBefore('.').lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(32)) }
        var sha by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { f.delete(); pendingRuntime = null }, containerColor = p.elevated, titleContentColor = p.text,
            title = { Text("Import Java runtime") },
            text = { Column {
                Text(f.name, color = p.textSecondary, fontSize = 13.sp)
                OutlinedTextField(id, { id = it }, label = { Text("Runtime id") }, singleLine = true)
                OutlinedTextField(sha, { sha = it }, label = { Text("Expected SHA-256 (recommended)") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { pendingRuntime = null; work { try { val r = Services.runtimes.install(f.toPath(), id, sha.trim().ifBlank { null }, "imported:${f.name}", true); vm.toast("Installed Java ${r.major} (${r.id})") } finally { f.delete() } } }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { f.delete(); pendingRuntime = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StorageSection(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<StorageEntry>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf<StorageEntry?>(null) }
    LaunchedEffect(refresh) {
        entries = withContext(Dispatchers.IO) { runCatching { StorageAnalyzer(Services.dirs).analyze(Services.profiles.load().profiles.mapNotNull { pr -> runCatching { pr.gameDir(Services.dirs) }.getOrNull() }) }.getOrDefault(emptyList()) }
    }
    val free = remember(refresh) { pro.cslauncher.core.util.FsUtil.usableSpace(Services.dirs.root) }
    InfoRow("Free space", formatBytes(free))
    if (entries == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
    entries?.sortedByDescending { it.bytes }?.forEach { e ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.category.title, color = p.text); Text(formatBytes(e.bytes) + if (e.category.regenerable) " · can be re-downloaded" else " · your data", color = p.textSecondary, fontSize = 12.sp)
            }
            if (e.category.regenerable && e.bytes > 0) TextButton(onClick = { clearing = e }) { Text("Clear", color = p.accent) }
        }
    }
    Text("Only data the launcher can regenerate can be cleared here. Worlds, mods, resource packs, screenshots and recordings are never removed automatically.", color = p.textMuted, fontSize = 12.sp)
    clearing?.let { e -> ConfirmDialog("Clear ${e.category.title}?", "Frees about ${formatBytes(e.bytes)}. It will be downloaded or rebuilt again when needed.", "Clear", false,
        { scope.launch { val freed = withContext(Dispatchers.IO) { runCatching { StorageAnalyzer(Services.dirs).clear(e.category) }.getOrDefault(0L) }; vm.toast("Freed ${formatBytes(freed)}"); refresh++ } }, { clearing = null }) }
}
