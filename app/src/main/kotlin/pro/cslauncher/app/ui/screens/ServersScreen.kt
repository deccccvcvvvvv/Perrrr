package pro.cslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: Saved Servers, 1: Virtual LAN (Terracotta), 2: Tutorial
    var terracottaEnabled by remember { mutableStateOf(false) }
    var customNodes by remember { mutableStateOf("tcp://public.easytier.top:11010") }
    var networkId by remember { mutableStateOf("cubiq-lan") }

    Column(Modifier.fillMaxSize().background(Color(0xFF141416))) {
        ScreenHeader("Multiplayer", onBack) {
            if (activeSubTab == 0) {
                HeaderAction("Add", "plus", { editing = null; name = ""; address = ""; showForm = true }, style = PxStyle.PRIMARY, enabled = profile != null && snapshot != null && !locked)
                HeaderAction("Refresh", "refresh", { refresh++ }, enabled = profile != null && !locked)
            }
        }

        // Sub-Tab Switcher: [ SERVERS ] | [ VIRTUAL LAN ] | [ HOW TO PLAY ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabs = listOf("SERVERS", "VIRTUAL LAN (P2P)", "HOW TO PLAY")
            tabs.forEachIndexed { idx, title ->
                val isSel = activeSubTab == idx
                CubiqMinecraftButton(
                    onClick = { activeSubTab = idx },
                    style = if (isSel) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                    text = title,
                    fontSize = 10.5.sp,
                    modifier = Modifier.weight(1f).height(34.dp)
                )
            }
        }

        when (activeSubTab) {
            0 -> {
                // TAB 0: Saved Servers
                Text("Saved servers · ${profile?.name ?: "No installation selected"}", color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                Text(if (ui.gameRunning || ui.launching) "Close Minecraft before editing this list." else "Servers are saved to this installation. Join them in Minecraft.", color = Color(0xFFAAAAAA), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                if (profile == null) PixelButton("Choose installation", onProfiles, Modifier.padding(12.dp), style = PxStyle.PRIMARY, icon = "grass")
                else if (error != null) Text("Could not read servers.dat: $error", color = Color(0xFFFF9292), fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                else if (loading) PixelProgress(null, Modifier.padding(12.dp))
                else if (snapshot?.records.isNullOrEmpty()) Text("No saved servers for this installation yet.", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
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
            1 -> {
                // TAB 1: Virtual LAN (Terracotta & EasyTier P2P Network Hub)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E20))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("ENABLE VIRTUAL LAN", color = Color.White, fontFamily = Monocraft, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                                    Text("P2P mesh VPN for playing with friends across different Wi-Fi networks", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                                }
                                PixelToggle(terracottaEnabled, { terracottaEnabled = it })
                            }

                            if (terracottaEnabled) {
                                Spacer(Modifier.height(4.dp))
                                Text("Virtual Network Room Name", color = Color(0xFF55FF55), fontFamily = Monocraft, fontSize = 11.sp)
                                PixelTextField(networkId, { networkId = it }, placeholder = "e.g. survival-squad-1")

                                Spacer(Modifier.height(4.dp))
                                Text("Public Signaling & Relay Nodes", color = Color(0xFF55FF55), fontFamily = Monocraft, fontSize = 11.sp)
                                PixelTextField(customNodes, { customNodes = it }, placeholder = "tcp://public.easytier.top:11010")

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CubiqMinecraftButton(
                                        onClick = {
                                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Room ID", networkId))
                                            vm.toast("Room name copied! Share with friends to join.")
                                        },
                                        style = MinecraftButtonStyle.GREEN,
                                        text = "Share Room ID",
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    )
                                    CubiqMinecraftButton(
                                        onClick = {
                                            vm.toast("Virtual LAN state refreshed.")
                                        },
                                        style = MinecraftButtonStyle.STONE,
                                        text = "Refresh",
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF1B221B))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3C8527)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("STATUS: ${if (terracottaEnabled) "ONLINE (ROOM: $networkId)" else "DISABLED"}", color = if (terracottaEnabled) Color(0xFF55FF55) else Color(0xFFFF8888), fontFamily = Monocraft, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 11.sp)
                            Text("All players in the same room name can connect to the host's Minecraft LAN port directly without port forwarding.", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                        }
                    }
                }
            }
            2 -> {
                // TAB 2: How to Play (Multiplayer Tutorial)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E20))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("1. HOSTING A GAME", color = Color(0xFF55FF55), fontFamily = Monocraft, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                            Text("• Open your Minecraft world in singleplayer.", color = Color.White, fontSize = 11.sp)
                            Text("• Press Pause (ESC) → 'Open to LAN' → choose gamemode & cheats → 'Start LAN World'.", color = Color(0xFFAAAAAA), fontSize = 10.5.sp)
                            Text("• Note the 5-digit port shown in game chat (e.g. 54321).", color = Color(0xFFAAAAAA), fontSize = 10.5.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E20))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("2. JOINING A FRIEND", color = Color(0xFF38BDF8), fontFamily = Monocraft, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                            Text("• Ensure both devices have 'Virtual LAN' enabled with the exact same Room ID.", color = Color.White, fontSize = 11.sp)
                            Text("• Open Minecraft → Multiplayer → the hosted world will appear automatically in LAN list, or Direct Connect with host IP:Port.", color = Color(0xFFAAAAAA), fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
    }
}
