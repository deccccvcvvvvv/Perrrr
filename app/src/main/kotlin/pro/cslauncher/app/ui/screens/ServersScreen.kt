package pro.cslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import pro.cslauncher.app.platform.ServerListEditor
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.content.ServerAddress
import pro.cslauncher.core.content.ServerPinger
import pro.cslauncher.core.content.ServerStatus

/** Manage the selected installation's real servers.dat, keeping unknown NBT data and backups. */
@Composable
fun ServersScreen(vm: LauncherViewModel, onBack: () -> Unit, onProfiles: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.selected
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember(profile?.id) { mutableStateOf<ServerListEditor.Snapshot?>(null) }
    var results by remember(profile?.id) { mutableStateOf<Map<String, ServerStatus>>(emptyMap()) }
    var pinging by remember(profile?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(profile?.id) { mutableStateOf<String?>(null) }
    var refresh by remember(profile?.id) { mutableIntStateOf(0) }
    var loading by remember(profile?.id) { mutableStateOf(false) }
    var saving by remember(profile?.id) { mutableStateOf(false) }
    var showForm by remember(profile?.id) { mutableStateOf(false) }
    var editing by remember(profile?.id) { mutableStateOf<ServerListEditor.Record?>(null) }
    var deleting by remember(profile?.id) { mutableStateOf<ServerListEditor.Record?>(null) }
    var name by remember(profile?.id) { mutableStateOf("") }
    var address by remember(profile?.id) { mutableStateOf("") }
    val locked = ui.gameRunning || ui.launching || loading || saving
    LaunchedEffect(profile?.id, refresh) {
        if (profile == null) return@LaunchedEffect
        loading = true
        val read = withContext(Dispatchers.IO) { runCatching { ServerListEditor.read(profile.gameDir(Services.dirs)) } }
        read.onSuccess { snapshot = it; error = null; results = emptyMap() }.onFailure { snapshot = null; error = it.message ?: "Could not read the server list" }
        loading = false
    }
    fun saveChange(action: (java.nio.file.Path, java.nio.file.Path, String) -> Unit, onSuccess: () -> Unit) {
        val current = snapshot ?: return
        val selected = profile ?: return
        if (locked) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                check(!vm.ui.value.gameRunning && !vm.ui.value.launching) { "Close Minecraft before editing servers" }
                action(selected.gameDir(Services.dirs), Services.dirs.backups.resolve("server-lists"), current.fingerprint)
            } }
            result.onSuccess { onSuccess(); refresh++ }.onFailure { vm.toast(it.message ?: "Could not update servers") }
            saving = false
        }
    }
    if (showForm) PixelDialog({ if (!saving) showForm = false }, if (editing == null) "Add server" else "Edit server", buttons = {
        PixelButton("Cancel", { showForm = false }, enabled = !saving, height = 34.dp)
        PixelButton("Save", {
            val record = editing
            saveChange({ game, backups, fingerprint ->
                if (record == null) ServerListEditor.add(game, backups, fingerprint, name, address)
                else ServerListEditor.update(game, backups, fingerprint, record.index, name, address)
            }, { showForm = false })
        }, enabled = !locked && name.isNotBlank() && ServerAddress.parse(address) != null, style = PxStyle.PRIMARY, height = 34.dp)
    }) {
        PixelTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Server name")
        Spacer(Modifier.height(6.dp))
        PixelTextField(address, { address = it }, Modifier.fillMaxWidth(), label = "Server address", placeholder = "example.org or example.org:25565")
        Text("Edit only while Minecraft is closed. Existing server data is backed up.", color = Color(0xFFAAAAAA), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
    deleting?.let { entry ->
        PixelDialog({ if (!saving) deleting = null }, "Remove server?", buttons = {
            PixelButton("Cancel", { deleting = null }, enabled = !saving, height = 34.dp)
            PixelButton("Remove", { saveChange({ game, backups, fingerprint -> ServerListEditor.remove(game, backups, fingerprint, entry.index) }, { deleting = null }) }, style = PxStyle.DANGER, enabled = !locked, height = 34.dp)
        }) { Text("Remove ${entry.name} from this installation's server list? A backup is kept.", color = Color.White, fontSize = 12.sp) }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF141416))) {
        ScreenHeader("Multiplayer", onBack) {
            HeaderAction("Add", "plus", { editing = null; name = ""; address = ""; showForm = true }, style = PxStyle.PRIMARY, enabled = profile != null && snapshot != null && !locked)
            HeaderAction("Refresh", "refresh", { refresh++ }, enabled = profile != null && !locked)
        }
        Text("Saved servers · ${profile?.name ?: "No installation selected"}", color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        Text(if (ui.gameRunning || ui.launching) "Close Minecraft before editing this list." else "Servers are saved to this installation. Join them in Minecraft. LAN proxy is not yet available.", color = Color(0xFFAAAAAA), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        if (profile == null) PixelButton("Choose installation", onProfiles, Modifier.padding(12.dp), style = PxStyle.PRIMARY, icon = "grass")
        else if (error != null) Text("Could not read servers.dat: $error", color = Color(0xFFFF9292), fontSize = 11.sp, modifier = Modifier.padding(12.dp))
        else if (loading) PixelProgress(null, Modifier.padding(12.dp))
        else if (snapshot?.records.isNullOrEmpty()) Text("No saved servers for this installation yet.", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(snapshot!!.records, key = { it.index }) { entry ->
                val status = results[entry.address]
                val parsed = ServerAddress.parse(entry.address)
                val busy = entry.address in pinging
                PixelPanel(Modifier.fillMaxWidth(), padding = 7.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon("world", size = 30.dp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.address, color = Color(0xFFAAAAAA), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (busy) Text("Checking…", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                            else if (status != null) Text(if (status.reachable) "${status.latencyMs} ms · ${status.online ?: "?"}/${status.max ?: "?"} players · ${status.versionName ?: "Unknown version"}" else "Offline or unreachable: ${status.error ?: "unknown error"}", color = if (status.reachable) Color(0xFF7FDA5F) else Color(0xFFFF9999), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        PixelButton("Ping", {
                            if (parsed != null) {
                                pinging = pinging + entry.address
                                scope.launch {
                                    val statusResult = withContext(Dispatchers.IO) { ServerPinger.ping(parsed.first, parsed.second) }
                                    results = results + (entry.address to statusResult)
                                    pinging = pinging - entry.address
                                }
                            }
                        }, enabled = parsed != null && !busy, height = 32.dp, fontSize = 10.sp)
                        Spacer(Modifier.width(4.dp))
                        PixelIconButton("edit", "Edit server", { editing = entry; name = entry.name; address = entry.address; showForm = true }, enabled = !locked, size = 32.dp)
                        Spacer(Modifier.width(4.dp))
                        PixelIconButton("trash", "Remove server", { deleting = entry }, enabled = !locked, style = PxStyle.DANGER, size = 32.dp)
                        Spacer(Modifier.width(4.dp))
                        PixelIconButton("upload", "Copy server address", {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Server address", entry.address))
                            vm.toast("Server address copied")
                        }, size = 32.dp)
                    }
                }
            }
        }
    }
}
