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
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            CsTheme(settings.theme, settings.reduceTransparency, settings.reduceMotion) { LauncherApp(vm, settings.animatedBackground) }
        }
    }

    override fun onResume() { super.onResume(); Services.settings.reload(); vm.reload() }   // picks up results and settings written by the game process
}

private sealed interface Screen { data class T(val tab: Tab) : Screen; data class O(val overlay: Overlay) : Screen }

@Composable
fun LauncherApp(vm: LauncherViewModel, animatedBackground: Boolean = true) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    BackHandler(overlay != null) { overlay = null }
    BackHandler(overlay == null && tab != Tab.HOME) { tab = Tab.HOME }
    val activeJobs = jobs.values.count { it.active }

    CsBackground(animated = animatedBackground) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))) {
            NavRail(tab, overlay == null, activeJobs) { t -> overlay = null; tab = t }
            val screen: Screen = overlay?.let { Screen.O(it) } ?: Screen.T(tab)
            AnimatedContent(screen, Modifier.weight(1f).fillMaxHeight(), label = "screen", transitionSpec = {
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
        PixelToast(ui.message) { vm.toast(null) }
    }
}

/** Left navigation rail: pixel icons, an animated selection slab and a badge for running downloads. */
@Composable
internal fun NavRail(selected: Tab, highlight: Boolean, activeJobs: Int, onSelect: (Tab) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxHeight().width(84.dp).background(Color(0xE61B1B1C)).drawBehind { drawRect(Color(0xFF111112), Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PixelIcon("grass", size = 34.dp)
        Text("CS", color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, style = TextStyle(shadow = PixelTextShadow))
        Spacer(Modifier.height(6.dp))
        Tab.entries.forEach { t ->
            val sel = t == selected && highlight
            val lift by animateFloatAsState(if (sel) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "rail")
            Box(
                Modifier.size(72.dp, 62.dp)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(t) }
                    .drawBehind {
                        if (lift > 0.01f) {
                            val b = 2.dp.toPx()
                            val inset = (1f - lift) * size.width / 2
                            drawRect(Color(0xFF1E1E1F).copy(alpha = lift), Offset(inset, 0f), Size(size.width - 2 * inset, size.height))
                            drawRect(Color(0xFF3C8527).copy(alpha = lift), Offset(inset + b, b), Size(size.width - 2 * inset - 2 * b, size.height - 2 * b))
                            drawRect(Color(0xFF52A535).copy(alpha = lift), Offset(inset + b, b), Size(size.width - 2 * inset - 2 * b, b))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { translationY = -lift * 2.dp.toPx(); scaleX = 1f + lift * 0.08f; scaleY = 1f + lift * 0.08f }) {
                    PixelIcon(t.icon, size = 26.dp, tint = if (sel) Color.White else Color(0xFFBDBEC0))
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, color = if (sel) Color.White else Color(0xFFA8A9AB), fontFamily = Monocraft, fontSize = 9.sp, maxLines = 1)
                }
                if (t == Tab.DOWNLOADS && activeJobs > 0) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0xFF1E1E1F)).padding(1.dp).background(Color(0xFFE0B020)).padding(horizontal = 4.dp)) {
                    Text("$activeJobs", color = Color.Black, fontFamily = Monocraft, fontSize = 9.sp)
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
    AnimatedVisibility(shown != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 100.dp, end = 16.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        var last by remember { mutableStateOf("") }
        shown?.let { last = it }
        PixelPanel(Modifier.widthIn(max = 560.dp).clickable { shown = null }, padding = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("info", size = 18.dp); Spacer(Modifier.width(8.dp))
                Text(last, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
