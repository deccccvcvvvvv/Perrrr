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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.screens.*
import pro.cslauncher.app.ui.theme.CsBackground
import pro.cslauncher.app.ui.theme.CsTheme
import pro.cslauncher.app.ui.theme.LocalPalette

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home), PROFILES("Profiles", Icons.AutoMirrored.Filled.List), BROWSE("Browse", Icons.Filled.Search),
    DOWNLOADS("Downloads", Icons.Filled.KeyboardArrowDown), SETTINGS("Settings", Icons.Filled.Settings)
}

sealed class Overlay {
    data object Accounts : Overlay()
    data object Logs : Overlay()
    data class EditProfile(val id: String) : Overlay()
    data class Content(val section: ContentSection) : Overlay()
}

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            CsTheme(settings.theme, settings.reduceTransparency, settings.reduceMotion) { LauncherApp(vm) }
        }
    }

    override fun onResume() { super.onResume(); vm.reload() }   // picks up results written by the game process
}

@Composable
fun LauncherApp(vm: LauncherViewModel) {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(ui.message) { ui.message?.let { snack.showSnackbar(it); vm.toast(null) } }
    BackHandler(overlay != null) { overlay = null }

    CsBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (overlay == null) NavigationBar(containerColor = p.surfaceSecondary.copy(alpha = if (p.glass) 0.85f else 1f)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = p.text, selectedTextColor = p.text, indicatorColor = p.selected,
                                unselectedIconColor = p.textMuted, unselectedTextColor = p.textMuted))
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (val o = overlay) {
                    null -> when (tab) {
                        Tab.HOME -> HomeScreen(vm, onOpen = { overlay = it }, onTab = { tab = it })
                        Tab.PROFILES -> ProfilesScreen(vm, onEdit = { overlay = Overlay.EditProfile(it) })
                        Tab.BROWSE -> BrowseScreen(vm)
                        Tab.DOWNLOADS -> DownloadsScreen(vm)
                        Tab.SETTINGS -> SettingsScreen(vm, onOpen = { overlay = it })
                    }
                    Overlay.Accounts -> AccountsScreen(vm, onBack = { overlay = null })
                    Overlay.Logs -> LogsScreen(vm, onBack = { overlay = null })
                    is Overlay.EditProfile -> ProfileEditorScreen(vm, o.id, onBack = { overlay = null })
                    is Overlay.Content -> ContentScreen(vm, o.section, onBack = { overlay = null })
                }
            }
        }
    }
}
