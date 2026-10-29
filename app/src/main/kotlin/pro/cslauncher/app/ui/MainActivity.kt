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
    data class News(val patchNotes: Boolean = false) : Overlay()
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
                    Tab.HOME -> MinecraftLauncherHome(vm, onOpen = { overlay = it }, onTab = { tab = it })
                    Tab.PROFILES -> ProfilesScreen(vm, onEdit = { overlay = Overlay.EditProfile(it) }, onOpen = { overlay = it })
                    Tab.BROWSE -> BrowseScreen(vm, onOpen = { overlay = it })
                    Tab.DOWNLOADS -> DownloadsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm, onOpen = { overlay = it })
                }
                is Screen.O -> when (val o = s.overlay) {
                    Overlay.Accounts -> AccountsScreen(vm, onBack = { overlay = null })
                    Overlay.Logs -> LogsScreen(vm, onBack = { overlay = null })
                    is Overlay.News -> OfficialNewsScreen(o.patchNotes, onBack = { overlay = null })
                    is Overlay.EditProfile -> ProfileEditorScreen(vm, o.id, onBack = { overlay = null })
                    is Overlay.Content -> ContentScreen(vm, o.section, onBack = { overlay = null })
                    is Overlay.OptiFine -> OptiFineScreen(vm, o.profileId, o.versionHint, onBack = { overlay = null })
                }
            }
        }
    }

    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    CsBackground(animated = animatedBackground) {
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                ScreenArea(Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)))
                NavBottomBar(tab, activeJobs) { t -> overlay = null; tab = t }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavRail(tab, overlay, activeJobs, ui.activeAccount?.username, ui.activeAccount?.type?.name, onSelect = { t -> overlay = null; tab = t }, onOpen = { overlay = it })
                // The launcher is immersive and intentionally draws edge-to-edge under the landscape camera cutout.
                ScreenArea(Modifier.weight(1f).fillMaxHeight())
            }
        }
        PixelToast(ui.message) { vm.toast(null) }
    }
}

/** Landscape navigation inspired by the reference launcher, wired to this launcher's screens. */
@Composable
internal fun NavRail(selected: Tab, activeOverlay: Overlay?, activeJobs: Int, accountName: String?, accountType: String?, onSelect: (Tab) -> Unit, onOpen: (Overlay) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    data class Destination(val label: String, val icon: String, val tab: Tab? = null, val overlay: Overlay? = null, val skins: Boolean = false)
    val destinations = listOf(
        Destination("Play", "home", tab = Tab.HOME),
        Destination("Installations", "grass", tab = Tab.PROFILES),
        Destination("Skins", "head", skins = true),
        Destination("Patch Notes", "book", overlay = Overlay.News(true)),
        Destination("News", "image", overlay = Overlay.News(false)),
        Destination("Mods", "pickaxe", tab = Tab.BROWSE),
        Destination("Profile", "head", overlay = Overlay.Accounts),
        Destination("Downloads", "download", tab = Tab.DOWNLOADS),
        Destination("Settings", "settings", tab = Tab.SETTINGS),
    )
    BoxWithConstraints(Modifier.fillMaxHeight().width(160.dp).background(Color(0xFF141517))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { onOpen(Overlay.Accounts) }.padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("head", size = 30.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(accountName ?: "Add account", color = Color.White, fontFamily = Monocraft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountType?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Not signed in", color = Color(0xFFAAAAAA), fontSize = 9.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(5.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                destinations.forEach { destination ->
                    val isSelected = when {
                        destination.tab != null && activeOverlay == null -> selected == destination.tab
                        destination.tab == Tab.PROFILES && activeOverlay is Overlay.EditProfile -> true
                        destination.overlay != null -> destination.overlay == activeOverlay
                        else -> false
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                        .background(if (isSelected) Color(0xFF28352B) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .drawBehind { if (isSelected) drawRect(Color(0xFF4BC82A), size = Size(3.dp.toPx(), size.height)) }
                        .clickable {
                            MinecraftUiClickSound.play(context)
                            when {
                                destination.tab != null -> onSelect(destination.tab)
                                destination.overlay != null -> onOpen(destination.overlay)
                                destination.skins -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.minecraft.net/en-us/msaprofile/mygames/editskin"))) }
                            }
                        }.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon(destination.icon, size = 19.dp, tint = Color(0xFFE4E6E8))
                        Spacer(Modifier.width(9.dp))
                        Text(destination.label, color = if (isSelected) Color.White else Color(0xFFCACDD0), fontFamily = Monocraft, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        if (destination.tab == Tab.DOWNLOADS && activeJobs > 0) Text(activeJobs.toString(), color = Color(0xFF83E25B), fontSize = 9.sp)
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
