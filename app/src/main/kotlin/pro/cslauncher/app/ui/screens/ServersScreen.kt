package pro.cslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.content.ServerEntry
import pro.cslauncher.core.content.ServerListStore
import pro.cslauncher.core.content.ServerPinger
import pro.cslauncher.core.content.ServerStatus

/** Read-only access to the selected installation's actual servers.dat; no LAN proxy is implied. */
@Composable
fun ServersScreen(vm: LauncherViewModel, onBack: () -> Unit, onProfiles: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.selected
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(profile?.id) { mutableStateOf<List<ServerEntry>>(emptyList()) }
    var results by remember(profile?.id) { mutableStateOf<Map<String, ServerStatus>>(emptyMap()) }
    var pinging by remember(profile?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(profile?.id) { mutableStateOf<String?>(null) }
    var refresh by remember(profile?.id) { mutableIntStateOf(0) }
    var loading by remember(profile?.id) { mutableStateOf(false) }
    LaunchedEffect(profile?.id, refresh) {
        if (profile == null) return@LaunchedEffect
        loading = true
        val read = withContext(Dispatchers.IO) { runCatching { ServerListStore(profile.gameDir(Services.dirs)).load() } }
        read.onSuccess { entries = it; error = null; results = emptyMap() }.onFailure { error = it.message ?: "Could not read the server list" }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF141416))) {
        ScreenHeader("Multiplayer", onBack) {
            HeaderAction("Refresh", "refresh", { refresh++ }, enabled = profile != null && !loading)
        }
        Text("Saved servers · ${profile?.name ?: "No installation selected"}", color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        Text("Server list is read-only here. Add or join servers in Minecraft; LAN hosting is not available in this screen.", color = Color(0xFFAAAAAA), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        if (profile == null) {
            PixelButton("Choose installation", onProfiles, Modifier.padding(12.dp), style = PxStyle.PRIMARY, icon = "grass")
        } else if (error != null) {
            Text("Could not read servers.dat: $error", color = Color(0xFFFF9292), fontSize = 11.sp, modifier = Modifier.padding(12.dp))
        } else if (loading) {
            PixelProgress(null, Modifier.padding(12.dp))
        } else if (entries.isEmpty()) {
            Text("No saved servers for this installation yet.", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(entries) { _, entry ->
                    val address = if (':' in entry.host) "[${entry.host}]${if (entry.port == 25565) "" else ":${entry.port}"}" else entry.address
                    val status = results[entry.address]
                    val busy = entry.address in pinging
                    PixelPanel(Modifier.fillMaxWidth(), padding = 7.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelIcon("world", size = 30.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(address, color = Color(0xFFAAAAAA), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (busy) Text("Checking…", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                                else if (status != null) Text(if (status.reachable) "${status.latencyMs} ms · ${status.online ?: "?"}/${status.max ?: "?"} players · ${status.versionName ?: "Unknown version"}" else "Offline or unreachable: ${status.error ?: "unknown error"}", color = if (status.reachable) Color(0xFF7FDA5F) else Color(0xFFFF9999), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            PixelButton("Ping", {
                                pinging = pinging + entry.address
                                scope.launch {
                                    val statusResult = withContext(Dispatchers.IO) { ServerPinger.ping(entry.host, entry.port) }
                                    results = results + (entry.address to statusResult)
                                    pinging = pinging - entry.address
                                }
                            }, enabled = !busy, height = 32.dp, fontSize = 10.sp)
                            Spacer(Modifier.width(5.dp))
                            PixelButton("Copy", {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Server address", address))
                                vm.toast("Server address copied")
                            }, height = 32.dp, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
