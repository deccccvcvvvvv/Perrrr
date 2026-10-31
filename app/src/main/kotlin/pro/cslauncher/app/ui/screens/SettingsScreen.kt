package pro.cslauncher.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.settings.*
import pro.cslauncher.core.storage.StorageAnalyzer
import pro.cslauncher.core.storage.StorageEntry

private enum class Category(val title: String, val subtitle: String, val icon: String = "settings") {
    GENERAL("General", "Preferences & startup", "settings"),
    APPEARANCE("Appearance", "Theme & animations", "sun"),
    PERFORMANCE("Performance", "Presets & frame rate", "bolt"),
    CONTROLS("Controls", "Touch, layouts, physical input", "gamepad"),
    CURSOR("Cursor", "Dynamic cursors & sensitivity", "mouse"),
    MEMORY("Memory", "Java allocation", "chart"),
    RENDERING("Rendering", "Driver compatibility", "bolt"),
    LAUNCH("Game Launch", "Resolution & JVM args", "terminal"),
    RECORDING("Recording", "Screen capture & audio", "record"),
    RUNTIMES("Runtimes", "Java & component packs", "terminal"),
    DOWNLOADS("Downloads", "Concurrency & keys", "download"),
    ACCOUNTS("Accounts", "Sign-in settings", "head"),
    STORAGE("Storage", "Usage & cleanup", "chest"),
    PRIVACY("Privacy", "Activity log", "shield"),
    DIAGNOSTICS("Diagnostics", "Logs & reports", "book"),
    ABOUT("About", "Version & licenses", "info")
}

/**
 * Authentic vertical Cubiq / Minecraft PC Styled Settings Screen.
 * Full mobile friendly vertical layout with horizontal category pill bar,
 * 3D Minecraft styling, card sections, and seamless responsive design.
 */
@Composable
fun SettingsScreen(vm: LauncherViewModel, onOpen: (Overlay) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var open by rememberSaveableCategory()
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1012))
    ) {
        // --- 1. Top Header Bar with Cubiq / Minecraft Title ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141416))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1F2226))
                    .border(BorderStroke(1.5.dp, Color(0xFF38C916)), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                PixelIcon("settings", size = 20.dp, tint = Color(0xFF55FF55))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "SETTINGS",
                    color = Color.White,
                    fontFamily = Monocraft,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = open.subtitle,
                    color = Color(0xFFAAAAAA),
                    fontFamily = Monocraft,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // --- 2. Horizontal Category Pill Bar (Vertically stacked on page, horizontally scrollable tabs) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF17181B))
                .border(BorderStroke(0.5.dp, Color(0xFF24262A)))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Category.entries.forEach { c ->
                val sel = c == open
                val bg = if (sel) Color(0xFF1E3A19) else Color(0xFF212328)
                val borderCol = if (sel) Color(0xFF55FF55) else Color(0xFF32353C)
                val textCol = if (sel) Color.White else Color(0xFFAAAAAA)

                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(6.dp))
                        .clickable {
                            MinecraftUiClickSound.play(context)
                            if (c == Category.DIAGNOSTICS) onOpen(Overlay.Logs) else open = c
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PixelIcon(c.icon, size = 15.dp, tint = if (sel) Color(0xFF55FF55) else textCol)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = c.title,
                        color = textCol,
                        fontFamily = Monocraft,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // --- 3. Main Vertical Settings Content Area ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Category Section Card Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF181A1D))
                    .border(BorderStroke(1.5.dp, Color(0xFF2A2D34)), RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (open) {
                        Category.GENERAL -> {
                            ChoiceRow("Startup screen", listOf("HOME", "PROFILES", "BROWSE", "DOWNLOADS", "SETTINGS"), s.startupScreen, label = { it.lowercase().replaceFirstChar(Char::uppercase) }) { v -> vm.updateSettings { it.copy(startupScreen = v) } }
                            SwitchRow("Show snapshots", "Include snapshots in the version selector", s.showSnapshots) { v -> vm.updateSettings { it.copy(showSnapshots = v) } }
                            SwitchRow("Historical versions", "Metadata only; the core still validates launch support", s.showHistorical) { v -> vm.updateSettings { it.copy(showHistorical = v) } }
                            CubiqMinecraftButton(
                                onClick = { vm.refreshManifest(true) },
                                style = MinecraftButtonStyle.STONE,
                                text = "Refresh version metadata",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.MEMORY -> {
                            SwitchRow("Automatic memory for new instances", "Recommended ${vm.recommendedHeapMb()} MB; existing instances stay unchanged", s.defaultMemoryMb == null) { v -> vm.updateSettings { it.copy(defaultMemoryMb = if (v) null else vm.recommendedHeapMb()) } }
                            s.defaultMemoryMb?.let { mb ->
                                SliderRow("Default allocation", mb.toFloat().coerceIn(512f, vm.maxHeapMb().toFloat().coerceAtLeast(513f)), 512f..vm.maxHeapMb().toFloat().coerceAtLeast(513f), format = { "${it.toInt()} MB" }) { v -> vm.updateSettings { it.copy(defaultMemoryMb = v.toInt() / 64 * 64) } }
                            }
                            Text("Device-safe maximum ${vm.maxHeapMb()} MB reserves memory for Android and rendering.", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
                        }

                        Category.RENDERING -> {
                            Text("Default for newly created instances only. Launch preflight validates the actual Minecraft version.", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
                            ChoiceRow("Default", listOf("auto") + Services.renderers.backends.map { it.id }, s.defaultRenderer, label = { it }) { v -> vm.updateSettings { it.copy(defaultRenderer = v) } }
                            var descriptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                            LaunchedEffect(Unit) {
                                descriptions = withContext(Dispatchers.IO) {
                                    val env = pro.cslauncher.core.renderer.RendererEnvironment(
                                        Services.platform, android.os.Build.VERSION.SDK_INT,
                                        Services.device.glesVersion, Services.device.vulkanApi, Services.components.availableLibraries(Services.abi), "1.21.11", true, false
                                    )
                                    Services.renderers.backends.map { backend -> backend.displayName to backend.status(env).let { "${it.state}: ${it.reason}" } }
                                }
                            }
                            descriptions.forEach { (title, detail) ->
                                SectionTitle(title)
                                Text(detail, color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
                            }
                            Text("Compatibility shown for Minecraft 1.21.11. Missing components can be imported in Runtimes.", color = Color(0xFF777777), fontSize = 11.sp, fontFamily = Monocraft)
                        }

                        Category.LAUNCH -> {
                            SwitchRow("Default fullscreen", "New instances only", s.defaultFullscreen) { v -> vm.updateSettings { it.copy(defaultFullscreen = v) } }
                            ChoiceRow("Default resolution", listOf("Device", "854×480", "1280×720", "1920×1080"), s.defaultResolutionWidth?.let { "${it}×${s.defaultResolutionHeight}" } ?: "Device", label = { it }) { value ->
                                val dims = value.split("×")
                                vm.updateSettings { it.copy(defaultResolutionWidth = dims.firstOrNull()?.toIntOrNull(), defaultResolutionHeight = dims.getOrNull(1)?.toIntOrNull()) }
                            }
                            var args by remember(s.defaultJvmArgs) { mutableStateOf(s.defaultJvmArgs) }
                            PixelTextField(args, { args = it }, label = "Default JVM arguments (new instances)", singleLine = false)
                            val errors = pro.cslauncher.core.profile.JvmArgs.validate(pro.cslauncher.core.profile.JvmArgs.tokenize(args)).filter { it.severity == pro.cslauncher.core.profile.JvmArgs.Severity.ERROR }
                            errors.forEach { Text(it.message, color = Color(0xFFFF5555), fontSize = 11.sp, fontFamily = Monocraft) }
                            CubiqMinecraftButton(
                                onClick = { vm.updateSettings { it.copy(defaultJvmArgs = args) } },
                                style = MinecraftButtonStyle.GREEN,
                                text = "Save defaults",
                                fontSize = 12.sp,
                                enabled = errors.isEmpty(),
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                            CubiqMinecraftButton(
                                onClick = { onOpen(Overlay.Accounts) },
                                style = MinecraftButtonStyle.STONE,
                                text = "Manage default account",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.APPEARANCE -> {
                            ChoiceRow("Theme", ThemeChoice.entries, s.theme, label = { when (it) { ThemeChoice.MINECRAFT_LAUNCHER -> "Minecraft Launcher"; ThemeChoice.PIXEL -> "Pixel"; ThemeChoice.DARK_GRAPHITE -> "Dark Graphite"; ThemeChoice.GLASSMORPHISM -> "Glassmorphism" } }) { t -> vm.updateSettings { it.copy(theme = t) } }
                            if (s.theme == ThemeChoice.PIXEL) SwitchRow("Animated background", "Moving pixel landscape behind the launcher", s.animatedBackground, enabled = !s.reduceMotion) { v -> vm.updateSettings { it.copy(animatedBackground = v) } }
                            SwitchRow("Reduce transparency", "Use opaque surfaces instead of glass", s.reduceTransparency) { v -> vm.updateSettings { it.copy(reduceTransparency = v) } }
                            SwitchRow("Reduce motion", "Turn off non-essential transitions and effects", s.reduceMotion) { v -> vm.updateSettings { it.copy(reduceMotion = v) } }
                            SwitchRow("Minecraft button sound", "Uses authentic UI click sound on touches", s.uiClickSounds) { v -> vm.updateSettings { it.copy(uiClickSounds = v) } }
                            SwitchRow("Full screen", "Hide status and navigation bars in the launcher", s.fullscreenLauncher) { v -> vm.updateSettings { it.copy(fullscreenLauncher = v) } }
                        }

                        Category.PERFORMANCE -> {
                            ChoiceRow("Preset", PerformancePreset.entries, s.performancePreset, label = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }) { v -> vm.updateSettings { it.copy(performancePreset = v) } }
                            PerformanceAdvisor.presetEffects(s.performancePreset).forEach { e -> Text("• ${e.setting}: ${e.value}", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft) }
                            Text("Presets optimize options.txt and the render scale.", color = Color(0xFF777777), fontSize = 11.sp, fontFamily = Monocraft)
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
                            SwitchRow("Hide touch controls with keyboard/mouse", null, t.hideOverlayWithPhysicalInput) { v -> up { it.copy(hideOverlayWithPhysicalInput = v) } }
                            SwitchRow("Vibrate on button press", null, t.vibrateOnPress) { v -> up { it.copy(vibrateOnPress = v) } }
                            SliderRow("Safe-area padding", t.safeAreaPaddingDp, 0f..32f, format = { "${it.toInt()} dp" }) { v -> up { it.copy(safeAreaPaddingDp = v) } }
                            SliderRow("Controls opacity", t.controlsOpacity, 0.1f..1.5f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(controlsOpacity = v) } }
                            SliderRow("Controls size", t.controlsScale, 0.6f..1.6f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(controlsScale = v) } }
                            SwitchRow("Game menu tab handle", "Top edge tab opens menu in every layout", t.showMenuHandle) { v -> up { it.copy(showMenuHandle = v) } }
                            SwitchRow("FPS and memory read-out", "Shown in top-left corner while playing", t.showFpsHud) { v -> up { it.copy(showFpsHud = v) } }
                            CubiqMinecraftButton(
                                onClick = { ControlEditorActivity.open(vm.getApplication(), vm.ui.value.selected?.controlLayoutId ?: "default-touch") },
                                style = MinecraftButtonStyle.STONE,
                                text = "Open control layout editor",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.CURSOR -> {
                            val c = s.cursor
                            fun up(f: (CursorSettings) -> CursorSettings) = vm.updateSettings { it.copy(cursor = f(it.cursor)) }
                            SwitchRow("Virtual mouse", "Touchpad drag anywhere to move pointer & tap to click", c.mode == CursorMode.TOUCHPAD) { v -> up { it.withVirtualMouse(v) } }
                            SwitchRow("Show pointer on touch", "Direct touch: draw pointer where finger is", c.pointerOnTouch, enabled = c.mode != CursorMode.TOUCHPAD) { v -> up { it.withPointerOnTouch(v) } }
                            ChoiceRow("Mode", CursorMode.entries, c.mode, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> up { it.copy(mode = v) } }
                            ChoiceRow("Shape", CursorShape.entries, c.shape, label = {
                                when (it) {
                                    CursorShape.ARROW -> "Arrow"
                                    CursorShape.CIRCLE -> "Circle"
                                    CursorShape.CROSSHAIR -> "Crosshair"
                                    CursorShape.DOT -> "Dot"
                                    CursorShape.HAND -> "Hand (Link)"
                                    CursorShape.IBEAM -> "I-Beam (Text)"
                                    CursorShape.RESIZE_NS -> "Resize Vertical"
                                    CursorShape.RESIZE_EW -> "Resize Horizontal"
                                    CursorShape.RESIZE_ALL -> "Move / Resize All"
                                    CursorShape.NOT_ALLOWED -> "Not Allowed"
                                }
                            }) { v -> up { it.copy(shape = v) } }
                            SliderRow("Size", c.sizeDp, 12f..56f, format = { "${it.toInt()} dp" }) { v -> up { it.copy(sizeDp = v) } }
                            SliderRow("Opacity", c.opacity, 0.3f..1f, format = { "${(it * 100).toInt()}%" }) { v -> up { it.copy(opacity = v) } }
                            SliderRow("Sensitivity", c.sensitivity, 0.3f..3f, format = { "%.1f×".format(it) }) { v -> up { it.copy(sensitivity = v) } }
                            SliderRow("Smoothing", c.smoothing, 0f..0.8f, format = { "%.0f%%".format(it * 100) }) { v -> up { it.copy(smoothing = v) } }
                            SwitchRow("Click animation", "Show animated ripple on tap/click", c.clickAnimation) { v -> up { it.copy(clickAnimation = v) } }
                            SwitchRow("Pointer trail", "Smooth motion tail behind the cursor", c.trail) { v -> up { it.copy(trail = v) } }
                            SwitchRow("Use external mouse when connected", null, c.externalMouse) { v -> up { it.copy(externalMouse = v) } }
                        }

                        Category.RECORDING -> RecordingSettingsSection(vm, s.recording)
                        Category.RUNTIMES -> RuntimesSection(vm)

                        Category.DOWNLOADS -> {
                            ChoiceRow("Attempts per file", (1..6).toList(), s.downloadAttempts, label = { "$it" }) { v -> vm.updateSettings { it.copy(downloadAttempts = v) } }
                            SliderRow("Parallel downloads", s.downloadConcurrency.toFloat(), 1f..12f, steps = 10, format = { it.toInt().toString() }) { v -> vm.updateSettings { it.copy(downloadConcurrency = v.toInt()) } }
                            SwitchRow("Show snapshots", null, s.showSnapshots) { v -> vm.updateSettings { it.copy(showSnapshots = v) } }
                            SwitchRow("Show old alpha/beta versions", null, s.showHistorical) { v -> vm.updateSettings { it.copy(showHistorical = v) } }
                            var key by remember { mutableStateOf(Services.secrets.get("curseforge.apiKey").orEmpty()) }
                            AppTextField(key, { key = it }, "CurseForge API key (optional)")
                            CubiqMinecraftButton(
                                onClick = {
                                    if (key.isBlank()) Services.secrets.remove("curseforge.apiKey")
                                    else Services.secrets.put("curseforge.apiKey", key.trim())
                                    vm.toast("Saved")
                                },
                                style = MinecraftButtonStyle.STONE,
                                text = "Save key",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.ACCOUNTS -> {
                            Text("Microsoft sign-in opens Microsoft's official login page in-app and keeps session tokens securely encrypted on this device.", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
                            CubiqMinecraftButton(
                                onClick = { onOpen(Overlay.Accounts) },
                                style = MinecraftButtonStyle.GREEN,
                                text = "Manage accounts",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.STORAGE -> StorageSection(vm)

                        Category.PRIVACY -> {
                            SwitchRow("Recent activity", "Show launches, installs and recordings on the home screen", s.recentActivityEnabled) { v -> vm.updateSettings { it.copy(recentActivityEnabled = v) } }
                            CubiqMinecraftButton(
                                onClick = { vm.clearActivity() },
                                style = MinecraftButtonStyle.RED,
                                text = "Clear recent activity",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                            Text("Tokens are stored only in the Android Keystore and are never written to logs.", color = Color(0xFF777777), fontSize = 11.sp, fontFamily = Monocraft)
                        }

                        Category.DIAGNOSTICS -> {
                            CubiqMinecraftButton(
                                onClick = { onOpen(Overlay.Logs) },
                                style = MinecraftButtonStyle.STONE,
                                text = "Open logs & diagnostics",
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().height(38.dp)
                            )
                        }

                        Category.ABOUT -> {
                            InfoRow("Version", BuildConfig.VERSION_NAME)
                            InfoRow("Device ABI", Services.abi)
                            InfoRow("Android API", android.os.Build.VERSION.SDK_INT.toString())
                            Text("CS Launcher Pro is an independent launcher and is not affiliated with Mojang or Microsoft.", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
                            Text("Open-source components: Kotlin, Jetpack Compose (Apache-2.0).", color = Color(0xFF777777), fontSize = 11.sp, fontFamily = Monocraft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSaveableCategory(): MutableState<Category> = remember { mutableStateOf(Category.GENERAL) }

@Composable
private fun RecordingSettingsSection(vm: LauncherViewModel, r: RecordingSettings) {
    val p = LocalPalette.current
    fun up(f: (RecordingSettings) -> RecordingSettings) = vm.updateSettings { it.copy(recording = f(it.recording)) }
    val encoders = remember { runCatching { Services.device.videoEncoders() }.getOrDefault(emptyList()) }
    SwitchRow("Clean video (hide controls)", "Records only the game picture straight from the renderer", r.hideControls) { v -> up { it.copy(hideControls = v) } }
    ChoiceRow("Resolution", RecordingResolution.entries, r.resolution, label = { when (it) { RecordingResolution.P480 -> "480p"; RecordingResolution.P720 -> "720p"; RecordingResolution.P1080 -> "1080p"; RecordingResolution.NATIVE -> "Native" } }) { v -> up { it.copy(resolution = v) } }
    ChoiceRow("Frame rate", listOf(24, 30, 60), r.fps, label = { "$it FPS" }) { v -> up { it.copy(fps = v) } }
    ChoiceRow("Quality", RecordingQuality.entries, r.quality, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> up { it.copy(quality = v) } }
    if (r.quality == RecordingQuality.CUSTOM) SliderRow("Bitrate", r.customBitrateKbps.toFloat(), 1000f..40000f, format = { "${it.toInt() / 1000} Mbps" }) { v -> up { it.copy(customBitrateKbps = v.toInt()) } }
    val hevc = encoders.any { it.mime == "video/hevc" && it.hardware }
    SwitchRow("Prefer HEVC (smaller files)", if (hevc) "Hardware HEVC encoder available" else "No hardware HEVC encoder on this device", r.preferHevc && hevc, enabled = hevc) { v -> up { it.copy(preferHevc = v) } }
    ChoiceRow("Audio", AudioSourceChoice.entries, r.audio, label = { when (it) { AudioSourceChoice.NONE -> "None"; AudioSourceChoice.MICROPHONE -> "Microphone"; AudioSourceChoice.GAME_AUDIO -> "Game"; AudioSourceChoice.GAME_AND_MICROPHONE -> "Game + mic" } },
        enabled = { android.os.Build.VERSION.SDK_INT >= 29 || (it != AudioSourceChoice.GAME_AUDIO && it != AudioSourceChoice.GAME_AND_MICROPHONE) }) { v -> up { it.copy(audio = v) } }
    if (android.os.Build.VERSION.SDK_INT < 29) Text("Game audio capture needs Android 10 or newer.", color = Color(0xFF777777), fontSize = 11.sp, fontFamily = Monocraft)
    SwitchRow("Also save to the gallery (Movies)", null, r.saveToGallery) { v -> up { it.copy(saveToGallery = v) } }
    SwitchRow("Show recording indicator in game", null, r.showOverlay) { v -> up { it.copy(showOverlay = v) } }
    SectionTitle("Encoders on this device")
    if (encoders.isEmpty()) Text("No AVC/HEVC encoder reported — recording is unavailable.", color = Color(0xFFFF5555), fontSize = 12.sp, fontFamily = Monocraft)
    encoders.forEach { e -> Text("${e.name} · ${e.mime.removePrefix("video/")} · up to ${e.maxWidth}×${e.maxHeight}${if (e.hardware) " · hardware" else " · software"}", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft) }
}

@Composable
private fun RuntimesSection(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val runtimes = remember(refresh) { runCatching { Services.runtimes.list() }.getOrDefault(emptyList()) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    ChoiceRow("Default Java for new instances", listOf<String?>(null) + runtimes.map { it.id }, settings.defaultRuntimeId, label = { it ?: "Automatic" }) { v -> vm.updateSettings { it.copy(defaultRuntimeId = v) } }
    val components = remember(refresh) { runCatching { Services.components.list() }.getOrDefault(emptyList()) }
    var pendingRuntime by remember { mutableStateOf<java.io.File?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun work(block: () -> Unit) { busy = true; scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { vm.toast(it.message) } }; busy = false; refresh++ } }
    val pickRuntime = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) work { pendingRuntime = copyToCache(ctx, u) } }
    val pickComponent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) work { val f = copyToCache(ctx, u); try { val c = Services.components.import(f.toPath(), Services.abi); vm.toast("Installed ${c.manifest.name} ${c.manifest.version}") } finally { f.delete() } }
    }
    if (busy) AppProgress()
    Text("The game needs a Java runtime built for Android (${Services.abi}) and component packs for LWJGL and a renderer.", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = Monocraft)
    SectionTitle("Java runtimes")
    if (runtimes.isEmpty()) Text("None installed.", color = Color(0xFFFF5555), fontFamily = Monocraft)
    runtimes.forEach { r ->
        CsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("${r.id} · Java ${r.major}", color = Color.White, fontSize = 12.sp, fontFamily = Monocraft)
            Text("${r.version} · ${r.source.name.lowercase()}${if (r.libjvm() == null) " · libjvm.so missing" else ""}", color = if (r.libjvm() == null) Color(0xFFFF5555) else Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
            CubiqMinecraftButton(
                onClick = { work { Services.runtimes.remove(r.id, Services.profiles.load().profiles.filter { it.runtimeId == r.id }.map { it.name }) } },
                style = MinecraftButtonStyle.RED,
                text = "Remove",
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
        }
    }
    CubiqMinecraftButton(
        onClick = { pickRuntime.launch(arrayOf("*/*")) },
        style = MinecraftButtonStyle.STONE,
        text = "Import runtime (.tar.gz / .tar.xz / .zip)",
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().height(38.dp)
    )
    SectionTitle("Component packs")
    if (components.isEmpty()) Text("None installed. LWJGL for Android is required to start the game.", color = Color(0xFFFF5555), fontFamily = Monocraft)
    components.forEach { c ->
        CsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("${c.manifest.name} ${c.manifest.version}", color = Color.White, fontSize = 12.sp, fontFamily = Monocraft)
            Text("${c.manifest.kind} · ${c.manifest.abi} · ${c.manifest.license}", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = Monocraft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CubiqMinecraftButton(
                    onClick = { work { val bad = Services.components.verify(c); vm.toast(if (bad.isEmpty()) "All files verified" else "Damaged: ${bad.joinToString()}") } },
                    style = MinecraftButtonStyle.STONE,
                    text = "Verify",
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).height(32.dp)
                )
                CubiqMinecraftButton(
                    onClick = { work { Services.components.remove(c.manifest.id) } },
                    style = MinecraftButtonStyle.RED,
                    text = "Remove",
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).height(32.dp)
                )
            }
        }
    }
    CubiqMinecraftButton(
        onClick = { pickComponent.launch(arrayOf("application/zip", "*/*")) },
        style = MinecraftButtonStyle.STONE,
        text = "Import component pack (.zip with cs-component.json)",
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().height(38.dp)
    )

    pendingRuntime?.let { f ->
        var id by remember { mutableStateOf(f.name.substringBefore('.').lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(32)) }
        var sha by remember { mutableStateOf("") }
        PixelDialog({ f.delete(); pendingRuntime = null }, "Import Java runtime", widthFraction = 0.85f, buttons = {
            CubiqMinecraftButton(
                onClick = { f.delete(); pendingRuntime = null },
                style = MinecraftButtonStyle.STONE,
                text = "Cancel",
                fontSize = 11.sp,
                modifier = Modifier.height(34.dp)
            )
            CubiqMinecraftButton(
                onClick = {
                    pendingRuntime = null
                    work {
                        try {
                            val r = Services.runtimes.install(f.toPath(), id, sha.trim().ifBlank { null }, "imported:${f.name}", true)
                            vm.toast("Installed Java ${r.major} (${r.id})")
                        } finally {
                            f.delete()
                        }
                    }
                },
                style = MinecraftButtonStyle.GREEN,
                text = "Install",
                fontSize = 11.sp,
                modifier = Modifier.height(34.dp)
            )
        }) {
            Text(f.name, color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = Monocraft)
            Spacer(Modifier.height(6.dp))
            AppTextField(id, { id = it }, "Runtime id")
            Spacer(Modifier.height(6.dp))
            AppTextField(sha, { sha = it }, "Expected SHA-256 (optional)")
        }
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
    if (entries == null) AppProgress()
    entries?.sortedByDescending { it.bytes }?.forEach { e ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.category.title, color = Color.White, fontFamily = Monocraft, fontSize = 13.sp)
                Text(formatBytes(e.bytes) + if (e.category.regenerable) " · can be re-downloaded" else " · your data", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = Monocraft)
            }
            if (e.category.regenerable && e.bytes > 0) {
                CubiqMinecraftButton(
                    onClick = { clearing = e },
                    style = MinecraftButtonStyle.STONE,
                    text = "Clear",
                    fontSize = 11.sp,
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
    clearing?.let { e ->
        ConfirmDialog("Clear ${e.category.title}?", "Frees about ${formatBytes(e.bytes)}. It will be downloaded again when needed.", "Clear", false,
            { scope.launch { val freed = withContext(Dispatchers.IO) { runCatching { StorageAnalyzer(Services.dirs).clear(e.category) }.getOrDefault(0L) }; vm.toast("Freed ${formatBytes(freed)}"); refresh++ } }, { clearing = null })
    }
}
