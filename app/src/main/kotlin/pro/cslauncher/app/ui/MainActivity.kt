package pro.cslauncher.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.screens.*
import pro.cslauncher.app.ui.theme.*

enum class Tab(val label: String, val icon: String) {
    HOME("Home", "home"), PROFILES("Profiles", "grass"), BROWSE("Browse", "search"), DOWNLOADS("Downloads", "download"), SETTINGS("Settings", "settings")
}

sealed class Overlay {
    data object Accounts : Overlay()
    data object Logs : Overlay()
    data class EditProfile(val id: String) : Overlay()
    data class Content(val section: ContentSection) : Overlay()
    data class OptiFine(val profileId: String?, val versionHint: String? = null) : Overlay()
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Used by the install notification so a tap lands on the real download queue. */
        const val EXTRA_OPEN_DOWNLOADS = "cs.openDownloads"
    }
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applySystemBars()
        val startTab = if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) Tab.DOWNLOADS else Tab.HOME
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.fullscreenLauncher) { applySystemBars() }
            CsTheme(settings.theme, settings.reduceTransparency, settings.reduceMotion) { LauncherApp(vm, settings.animatedBackground, initialTab = startTab) }
        }
    }

    /** Immersive-sticky: the launcher uses the whole phone screen; bars come back briefly with an edge swipe. */
    private fun applySystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (Services.settings.state.value.fullscreenLauncher) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applySystemBars() }

    override fun onResume() { super.onResume(); Services.settings.reload(); vm.reload() }   // picks up results and settings written by the game process
}

private sealed interface Screen { data class T(val tab: Tab) : Screen; data class O(val overlay: Overlay) : Screen }

@Composable
fun LauncherApp(vm: LauncherViewModel, animatedBackground: Boolean = true, initialTab: Tab = Tab.HOME, initialOverlay: Overlay? = null) {
    var tab by rememberSaveable { mutableStateOf(if (initialTab == Tab.HOME) runCatching { Tab.valueOf(vm.settings.value.startupScreen) }.getOrDefault(Tab.HOME) else initialTab) }
    var overlay by remember { mutableStateOf(initialOverlay) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val p = LocalPalette.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    BackHandler(overlay != null) { overlay = null }
    BackHandler(overlay == null && tab != Tab.HOME) { tab = Tab.HOME }
    val activeJobs = jobs.values.count { it.active }

    if (overlay == Overlay.Logs) {
        Box(Modifier.fillMaxSize().background(p.background)) {
            LogsScreen(vm, onBack = { overlay = null })
            PixelToast(ui.message) { vm.toast(null) }
        }
        return
    }

    @Composable
    fun ScreenArea(modifier: Modifier) {
        val screen: Screen = overlay?.let { Screen.O(it) } ?: Screen.T(tab)
        AnimatedContent(screen, modifier, label = "screen", transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else when {
                targetState is Screen.O && initialState is Screen.T ->
                    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120))
                targetState is Screen.T && initialState is Screen.O ->
                    fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(180)))
                else -> {
                    val down = (targetState as? Screen.T)?.tab?.ordinal ?: 0
                    val from = (initialState as? Screen.T)?.tab?.ordinal ?: 0
                    val dir = if (down >= from) 1 else -1
                    (slideInVertically(tween(240, easing = FastOutSlowInEasing)) { dir * it / 8 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(180)) { -dir * it / 10 } + fadeOut(tween(140)))
                }
            }.using(SizeTransform(clip = false))
        }) { s ->
            when (s) {
                is Screen.T -> when (s.tab) {
                    Tab.HOME -> HomeScreen(vm, onOpen = { overlay = it }, onTab = { tab = it })
                    Tab.PROFILES -> ProfilesScreen(vm, onEdit = { overlay = Overlay.EditProfile(it) }, onOpen = { overlay = it })
                    Tab.BROWSE -> BrowseScreen(vm, onOpen = { overlay = it })
                    Tab.DOWNLOADS -> DownloadsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm, onOpen = { overlay = it })
                }
                is Screen.O -> when (val o = s.overlay) {
                    Overlay.Accounts -> AccountsScreen(vm, onBack = { overlay = null })
                    Overlay.Logs -> LogsScreen(vm, onBack = { overlay = null })
                    is Overlay.EditProfile -> ProfileEditorScreen(vm, o.id, onBack = { overlay = null })
                    is Overlay.Content -> ContentScreen(vm, o.section, onBack = { overlay = null })
                    is Overlay.OptiFine -> OptiFineScreen(vm, o.profileId, o.versionHint, onBack = { overlay = null })
                }
            }
        }
    }

    val compact = !p.pixel && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    CsBackground(animated = animatedBackground) {
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                ScreenArea(Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)))
                NavBottomBar(tab, activeJobs) { t -> overlay = null; tab = t }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavRail(tab, overlay, activeJobs, onSelect = { t -> overlay = null; tab = t }, onOpen = { overlay = it })
                // The launcher is immersive and intentionally draws edge-to-edge under the landscape camera cutout.
                ScreenArea(Modifier.weight(1f).fillMaxHeight())
            }
        }
        PixelToast(ui.message) { vm.toast(null) }
    }
}

/**
 * Launcher sidebar with the Home/Installations/content destinations. Pixel theme keeps its compact icon rail.
 */
@Composable
internal fun NavRail(selected: Tab, activeOverlay: Overlay?, activeJobs: Int, onSelect: (Tab) -> Unit, onOpen: (Overlay) -> Unit) {
    val p = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!p.pixel) {
        data class Destination(val label: String, val icon: String, val tab: Tab? = null, val section: ContentSection? = null)
        val destinations = listOf(
            Destination("Home", "home", tab = Tab.HOME),
            Destination("Installations", "grass", tab = Tab.PROFILES),
            Destination("Mods", "pickaxe", section = ContentSection.MODS),
            Destination("Resource Packs", "painting", section = ContentSection.RESOURCE_PACKS),
            Destination("Worlds", "world", section = ContentSection.WORLDS),
            Destination("Downloads", "download", tab = Tab.DOWNLOADS),
            Destination("Settings", "settings", tab = Tab.SETTINGS),
        )
        Column(Modifier.fillMaxHeight().width(128.dp).background(Color(0xFF10151A))
            .padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("grass", size = 31.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("CS LAUNCHER", color = p.text, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                    Text("PRO · JAVA EDITION", color = p.textMuted, fontSize = 8.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            destinations.forEach { destination ->
                val isSelected = when {
                    destination.tab != null && activeOverlay == null -> selected == destination.tab
                    destination.tab == Tab.PROFILES && activeOverlay is Overlay.EditProfile -> true
                    destination.section != null && activeOverlay is Overlay.Content -> destination.section == activeOverlay.section
                    else -> false
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .background(if (isSelected) Color(0xFF263C2B) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable {
                        pro.cslauncher.app.ui.theme.MinecraftUiClickSound.play(context)
                        if (destination.tab != null) onSelect(destination.tab)
                        else destination.section?.let { onOpen(Overlay.Content(it)) }
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon(destination.icon, size = 18.dp, tint = if (isSelected) Color(0xFF73D348) else Color(0xFFD0D4D8))
                    Spacer(Modifier.width(8.dp))
                    Text(destination.label, color = if (isSelected) Color.White else Color(0xFFE0E3E6), fontSize = 10.sp, lineHeight = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
                    if (destination.tab == Tab.DOWNLOADS && activeJobs > 0) Text(activeJobs.toString(), color = Color(0xFF83E25B), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Official Minecraft Java news", color = Color(0xFF8C979F), fontSize = 8.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            Text("CS Launcher · Android", color = Color(0xFF8C979F), fontSize = 8.sp, modifier = Modifier.padding(start = 8.dp, bottom = 3.dp))
        }
        return
    }
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(
        Modifier.fillMaxHeight().background(Color(0xE61B1B1C))
            .drawBehind { drawRect(Color(0xFF111112), Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(64.dp),
    ) {
        val logo = 34.dp
        val itemH = ((maxHeight - logo - 12.dp) / Tab.entries.size).coerceIn(42.dp, 56.dp)
        val fits = logo + 12.dp + itemH * Tab.entries.size <= maxHeight
        Column(
            Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (fits) Arrangement.SpaceBetween else Arrangement.Top,
        ) {
            Box(Modifier.height(logo), contentAlignment = Alignment.Center) { PixelIcon("grass", size = 26.dp) }
            Tab.entries.forEach { t ->
                val sel = t == selected && activeOverlay == null
                val lift by animateFloatAsState(if (sel) 1f else 0f, if (reduce) snap() else spring(dampingRatio = 0.55f, stiffness = 500f), label = "rail")
                val busy = t == Tab.DOWNLOADS && activeJobs > 0
                val bob = if (busy && !reduce) rememberInfiniteTransition(label = "dl").animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "bob").value else 0f
                Box(
                    Modifier.size(58.dp, itemH)
                        .semantics { contentDescription = t.label + if (busy) ", $activeJobs running" else "" }
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { MinecraftUiClickSound.play(context); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(t) }
                        .drawBehind {
                            if (lift > 0.01f) {
                                val b = 2.dp.toPx()
                                val inset = (1f - lift) * size.width / 2
                                drawRect(Color(0xFF1E1E1F).copy(alpha = lift), Offset(inset, 2.dp.toPx()), Size(size.width - 2 * inset, size.height - 4.dp.toPx()))
                                drawRect(Color(0xFF3C8527).copy(alpha = lift), Offset(inset + b, 2.dp.toPx() + b), Size(size.width - 2 * inset - 2 * b, size.height - 4.dp.toPx() - 2 * b))
                                drawRect(Color(0xFF52A535).copy(alpha = lift), Offset(inset + b, 2.dp.toPx() + b), Size(size.width - 2 * inset - 2 * b, b))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { translationY = -lift * 1.dp.toPx() }) {
                        // The download arrow drops into its tray in steps while something is downloading.
                        val step = (bob * 4).toInt().coerceAtMost(3)
                        PixelIcon(t.icon, Modifier.graphicsLayer { translationY = if (busy) (step - 1) * 1.5f.dp.toPx() else 0f },
                            size = if (itemH < 48.dp) 20.dp else 22.dp, tint = if (sel) Color.White else Color(0xFFBDBEC0))
                        Spacer(Modifier.height(2.dp))
                        Text(t.label, color = if (sel) Color.White else Color(0xFFA8A9AB), fontFamily = Monocraft, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1)
                    }
                    if (busy) Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 2.dp).background(Color(0xFF1E1E1F)).padding(1.dp).background(Color(0xFFE0B020)).padding(horizontal = 3.dp)) {
                        Text("$activeJobs", color = Color.Black, fontFamily = Monocraft, fontSize = 8.sp, lineHeight = 10.sp)
                    }
                }
            }
        }
    }
}

/** Toast that slides up from the bottom in the pixel style, replacing the Material snackbar. */
@Composable
private fun BoxScope.PixelToast(message: String?, onShown: () -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) { shown = message; onShown(); delay(3200L + message.length * 25L); if (shown == message) shown = null }
    }
    AnimatedVisibility(shown != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 76.dp, end = 12.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        var last by remember { mutableStateOf("") }
        shown?.let { last = it }
        PixelPanel(Modifier.widthIn(max = 560.dp).clickable { shown = null }, padding = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("info", size = 18.dp); Spacer(Modifier.width(8.dp))
                Text(last, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
