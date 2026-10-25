package pro.cslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.ControlEditorActivity
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.controls.ControlLayoutStore
import pro.cslauncher.core.meta.VersionType
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.renderer.RendererState

@Composable
fun ProfilesScreen(vm: LauncherViewModel, onEdit: (String) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Profiles") {
            IconButton(onClick = { importing = true }) { Icon(Icons.Filled.ArrowDropDown, "Import profile", tint = p.textSecondary) }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New profile", tint = p.text) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ui.profiles.profiles, key = { it.id }) { pr ->
                val sel = pr.id == ui.selected?.id
                CsCard(Modifier.fillMaxWidth().clickable { vm.select(pr.id) }, selected = sel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(listOfNotNull(pr.versionId, pr.loader?.type?.name?.lowercase(), if (pr.effectiveVersionId in ui.installed) "installed" else "not installed").joinToString(" · "),
                                color = p.textSecondary, fontSize = 13.sp)
                            pr.lastPlayedAtMs?.let { Text("Played ${relativeTime(it)}", color = p.textMuted, fontSize = 12.sp) }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options", tint = p.textSecondary) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(pr.id) })
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(pr.id, pr.name + " copy") })
                                DropdownMenuItem(text = { Text("Copy export JSON") }, onClick = {
                                    menu = false
                                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS profile", vm.exportProfile(pr.id)))
                                    vm.toast("Profile copied to clipboard (no account data included)")
                                })
                                DropdownMenuItem(text = { Text("Delete", color = p.danger) }, onClick = { menu = false; deleting = pr.id })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (creating) CreateProfileDialog(vm, onDismiss = { creating = false })
    deleting?.let { id ->
        val plan = remember(id) { runCatching { vm.deletionPlan(id) }.getOrNull() }
        var alsoFiles by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = p.elevated, titleContentColor = p.text, textContentColor = p.textSecondary,
            title = { Text("Delete profile?") },
            text = {
                Column {
                    Text("The profile configuration is removed. Worlds, mods and settings are kept unless you choose otherwise.")
                    if (plan?.gameDir != null && !plan.sharedWithOthers) {
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("Also delete game folder", "${plan.gameDir} · ${formatBytes(plan.gameDirBytes)} — includes worlds", alsoFiles) { alsoFiles = it }
                    } else if (plan?.sharedWithOthers == true) Text("The game folder is shared with other profiles and will be kept.", color = p.textMuted)
                }
            },
            confirmButton = { TextButton(onClick = { vm.delete(id, alsoFiles); deleting = null }) { Text(if (alsoFiles) "Delete profile and files" else "Delete profile", color = p.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { importing = false }, containerColor = p.elevated, titleContentColor = p.text,
            title = { Text("Import profile") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Paste profile JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) },
            confirmButton = { TextButton(onClick = { vm.importProfile(text); importing = false }, enabled = text.isNotBlank()) { Text("Import") } },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } })
    }
}

@Composable
private fun CreateProfileDialog(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf(settings.showSnapshots) }
    var version by remember { mutableStateOf(ui.manifest?.latestRelease ?: "") }
    var loader by remember { mutableStateOf(LoaderType.VANILLA) }
    val types = buildSet { add(VersionType.RELEASE); if (snapshots) add(VersionType.SNAPSHOT); if (settings.showHistorical) { add(VersionType.OLD_BETA); add(VersionType.OLD_ALPHA) } }
    val versions = remember(ui.manifest, query, snapshots) { ui.manifest?.filter(types, query)?.take(60).orEmpty() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = p.elevated, titleContentColor = p.text,
        title = { Text("New profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (ui.manifest == null) Text(ui.manifestError ?: "Loading version list…", color = p.textSecondary)
                OutlinedTextField(query, { query = it }, label = { Text("Search versions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("Show snapshots", null, snapshots) { snapshots = it }
                versions.forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable { version = v.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v.id == version, onClick = { version = v.id })
                        Text(v.id, color = p.text, modifier = Modifier.weight(1f)); Text(v.type.id, color = p.textMuted, fontSize = 12.sp)
                    }
                }
                ChoiceRow("Mod loader", listOf(LoaderType.VANILLA, LoaderType.FABRIC, LoaderType.QUILT, LoaderType.FORGE, LoaderType.NEOFORGE), loader,
                    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }, enabled = { it != LoaderType.FORGE && it != LoaderType.NEOFORGE }) { loader = it }
                Text("Forge and NeoForge need an installer process that is not supported on Android yet.", color = p.textMuted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.createProfile(name, version, loader); onDismiss() }, enabled = version.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ProfileEditorScreen(vm: LauncherViewModel, id: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val original = ui.profiles.profiles.firstOrNull { it.id == id } ?: run { onBack(); return }
    var e by remember(id) { mutableStateOf(original) }
    val layouts = remember { ControlLayoutStore(Services.dirs.config.resolve("controls")).list() }
    val renderers = remember { Services.renderers.backends }
    val runtimes = remember { runCatching { Services.runtimes.list() }.getOrDefault(emptyList()) }
    val jvmFindings = remember(e.jvmArgs) { JvmArgs.validate(JvmArgs.tokenize(e.jvmArgs)).filter { it.severity != JvmArgs.Severity.INFO } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Edit profile", onBack) { TextButton(onClick = { vm.saveProfile(e); onBack() }) { Text("Save", color = p.accent) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(e.name, { e = e.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle("Version")
            InfoRow("Minecraft", e.versionId); e.loader?.let { InfoRow("Loader", "${it.type} ${it.version}") }
            SectionTitle("Memory")
            val maxHeap = vm.maxHeapMb()
            SwitchRow("Automatic", "Recommended ${vm.recommendedHeapMb()} MB for this device", e.memoryMb == null) { e = e.copy(memoryMb = if (it) null else vm.recommendedHeapMb()) }
            e.memoryMb?.let { m -> SliderRow("Allocated", m.toFloat(), 512f..maxHeap.toFloat().coerceAtLeast(512f), format = { "${it.toInt()} MB" }) { v -> e = e.copy(memoryMb = (v / 64).toInt() * 64) } }
            Text("Upper limit $maxHeap MB keeps enough memory for Android and the renderer.", color = p.textMuted, fontSize = 12.sp)
            SectionTitle("Renderer")
            ChoiceRow("Backend", listOf("auto") + renderers.map { it.id }, e.rendererId, label = { id -> if (id == "auto") "Automatic" else renderers.first { it.id == id }.displayName }) { e = e.copy(rendererId = it, rendererMemo = null) }
            Text("Automatic picks the first renderer that is installed and supported by this device and version, and remembers what worked.", color = p.textMuted, fontSize = 12.sp)
            renderers.forEach { r ->
                val st = remember(r.id) { runCatching { r.status(pro.cslauncher.core.renderer.RendererEnvironment(Services.platform, android.os.Build.VERSION.SDK_INT, Services.device.glesVersion, Services.device.vulkanApi, Services.components.availableLibraries(Services.abi), e.versionId, true, false)) }.getOrNull() }
                if (st != null) Text("${r.displayName}: ${if (st.state == RendererState.AVAILABLE) "available" else st.reason}", color = if (st.state == RendererState.AVAILABLE) p.success else p.textSecondary, fontSize = 12.sp)
            }
            SliderRow("Render scale", e.renderScale, 0.3f..1f, format = { "${(it * 100).toInt()}%" }) { e = e.copy(renderScale = (it * 20).toInt() / 20f) }
            SectionTitle("Java runtime")
            ChoiceRow("Runtime", listOf<String?>(null) + runtimes.map { it.id }, e.runtimeId, label = { it ?: "Automatic" }) { e = e.copy(runtimeId = it) }
            if (runtimes.isEmpty()) Text("No Java runtime installed. Import one in Settings › Runtimes & components.", color = p.warning, fontSize = 12.sp)
            SectionTitle("Controls")
            ChoiceRow("Layout", layouts.map { it.id }, e.controlLayoutId, label = { lid -> layouts.first { it.id == lid }.name }) { e = e.copy(controlLayoutId = it) }
            TextButton(onClick = { ControlEditorActivity.open(vm.getApplication(), e.controlLayoutId) }) { Text("Open control editor", color = p.accent) }
            SectionTitle("Game folder")
            ChoiceRow("Location", GameDirMode.entries.filter { it != GameDirMode.CUSTOM }, e.gameDirMode, label = { if (it == GameDirMode.ISOLATED) "Separate for this profile" else "Shared" }) { e = e.copy(gameDirMode = it) }
            SectionTitle("Advanced")
            OutlinedTextField(e.jvmArgs, { e = e.copy(jvmArgs = it) }, label = { Text("JVM arguments") }, modifier = Modifier.fillMaxWidth())
            jvmFindings.forEach { f -> Text("${f.arg}: ${f.message}", color = if (f.severity == JvmArgs.Severity.ERROR) p.danger else p.warning, fontSize = 12.sp) }
            OutlinedTextField(e.gameArgs, { e = e.copy(gameArgs = it) }, label = { Text("Game arguments") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(32.dp))
        }
    }
}
