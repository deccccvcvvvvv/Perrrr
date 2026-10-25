package pro.cslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import pro.cslauncher.app.ui.ControlEditorActivity
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.isOptiFine
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.controls.ControlLayoutStore
import pro.cslauncher.core.meta.VersionType
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.renderer.RendererState
import pro.cslauncher.core.renderer.OptionType

@Composable
fun ProfilesScreen(vm: LauncherViewModel, onEdit: (String) -> Unit, onOpen: (Overlay) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Profile?>(null) }
    val mrpackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { copyToCache(ctx, uri) } }
            f.onSuccess { vm.installModpack(it) }.onFailure { vm.toast(it.message) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Profiles") {
            PixelButton("Modpack", { mrpackPicker.launch(arrayOf("*/*")) }, icon = "chest", height = 40.dp)
            PixelButton("OptiFine", { onOpen(Overlay.OptiFine(ui.selected?.id)) }, icon = "bolt", height = 40.dp)
            PixelIconButton("upload", "Import profile JSON", { importing = true })
            PixelButton("New", { creating = true }, icon = "plus", style = PxStyle.PRIMARY, height = 40.dp)
        }
        if (ui.profiles.profiles.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PixelIcon("grass", size = 64.dp)
                Spacer(Modifier.height(12.dp))
                PixelTitle("No profiles yet", fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                PixelButton("Create your first profile", { creating = true }, style = PxStyle.PRIMARY, icon = "plus")
            }
        } else LazyVerticalGrid(GridCells.Adaptive(270.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(ui.profiles.profiles, key = { _, it -> it.id }) { i, pr ->
                val sel = pr.id == ui.selected?.id
                val installed = pr.effectiveVersionId in ui.installed
                PixelPanel(Modifier.fillMaxWidth().staggerIn(i).clickable { vm.select(pr.id) }, selected = sel, padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelSlot(size = 48.dp) { PixelIcon(profileIcon(pr), size = 32.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            PixelTitle(pr.name, fontSize = 14.sp)
                            Text(profileSubtitle(pr), color = p.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (installed) Badge("READY", icon = "check") else Badge("NOT INSTALLED", Color(0xFFB0B0B0))
                                if (pr.effectiveVersionId in ui.needsRepair) Badge("REPAIR", p.danger)
                                if (sel) Badge("SELECTED", Color(0xFF8FD3FF))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pr.lastPlayedAtMs?.let { "Played ${relativeTime(it)}" } ?: "Never played", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        PixelIconButton("edit", "Edit ${pr.name}", { onEdit(pr.id) }, size = 36.dp)
                        PixelIconButton("more", "More options", { menuFor = pr }, size = 36.dp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Tap a card to select it. Modpack imports a Modrinth .mrpack file; OptiFine installs from the official OptiFine jar.",
                    color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (creating) CreateProfileDialog(vm, onDismiss = { creating = false }, onOptiFine = { v -> creating = false; onOpen(Overlay.OptiFine(null, v)) })

    menuFor?.let { pr ->
        PixelDialog({ menuFor = null }, pr.name, widthFraction = 0.45f, buttons = { PixelButton("Close", { menuFor = null }) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelButton("Edit", { menuFor = null; onEdit(pr.id) }, Modifier.fillMaxWidth(), icon = "edit")
                PixelButton("Mods & content", { menuFor = null; vm.select(pr.id); onOpen(Overlay.Content(ContentSection.MODS)) }, Modifier.fillMaxWidth(), icon = "pickaxe")
                PixelButton("Duplicate", { menuFor = null; vm.duplicate(pr.id, pr.name + " copy") }, Modifier.fillMaxWidth(), icon = "plus")
                PixelButton("Copy export JSON", {
                    menuFor = null
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS profile", vm.exportProfile(pr.id)))
                    vm.toast("Profile copied to clipboard (no account data included)")
                }, Modifier.fillMaxWidth(), icon = "upload")
                PixelButton("Delete", { menuFor = null; deleting = pr.id }, Modifier.fillMaxWidth(), style = PxStyle.DANGER, icon = "trash")
            }
        }
    }

    deleting?.let { id ->
        val plan = remember(id) { runCatching { vm.deletionPlan(id) }.getOrNull() }
        var alsoFiles by remember { mutableStateOf(false) }
        PixelDialog({ deleting = null }, "Delete profile?", widthFraction = 0.6f, buttons = {
            PixelButton("Cancel", { deleting = null })
            PixelButton(if (alsoFiles) "Delete profile + files" else "Delete profile", { vm.delete(id, alsoFiles); deleting = null }, style = PxStyle.DANGER)
        }) {
            Text("The profile configuration is removed. Worlds, mods and settings are kept unless you choose otherwise.", color = p.textSecondary, fontSize = 13.sp)
            if (plan?.gameDir != null && !plan.sharedWithOthers) {
                Spacer(Modifier.height(8.dp))
                SwitchRow("Also delete game folder", "${formatBytes(plan.gameDirBytes)} · includes worlds", alsoFiles) { alsoFiles = it }
            } else if (plan?.sharedWithOthers == true) Text("The game folder is shared with other profiles and will be kept.", color = p.textMuted, fontSize = 12.sp)
        }
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        PixelDialog({ importing = false }, "Import profile", buttons = {
            PixelButton("Cancel", { importing = false })
            PixelButton("Import", { vm.importProfile(text); importing = false }, style = PxStyle.PRIMARY, enabled = text.isNotBlank())
        }) { PixelTextField(text, { text = it }, placeholder = "Paste profile JSON", singleLine = false, minLines = 5) }
    }
}

private enum class NewKind(val label: String, val icon: String) { VANILLA("Vanilla", "grass"), FABRIC("Fabric", "pickaxe"), QUILT("Quilt", "pickaxe"), OPTIFINE("OptiFine", "bolt") }

@Composable
private fun CreateProfileDialog(vm: LauncherViewModel, onDismiss: () -> Unit, onOptiFine: (String) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf(settings.showSnapshots) }
    var version by remember { mutableStateOf(ui.manifest?.latestRelease ?: "") }
    var kind by remember { mutableStateOf(NewKind.VANILLA) }
    val types = buildSet { add(VersionType.RELEASE); if (snapshots) add(VersionType.SNAPSHOT); if (settings.showHistorical) { add(VersionType.OLD_BETA); add(VersionType.OLD_ALPHA) } }
    val versions = remember(ui.manifest, query, snapshots) { ui.manifest?.filter(types, query)?.take(120).orEmpty() }
    PixelDialog(onDismiss, "New profile", widthFraction = 0.86f, buttons = {
        PixelButton("Cancel", onDismiss)
        PixelButton(if (kind == NewKind.OPTIFINE) "Continue" else "Create", {
            when (kind) {
                NewKind.OPTIFINE -> onOptiFine(version)
                else -> { vm.createProfile(name, version, LoaderType.valueOf(kind.name)); onDismiss() }
            }
        }, style = PxStyle.PRIMARY, enabled = version.isNotBlank(), icon = "check")
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                PixelTextField(query, { query = it }, placeholder = "Search versions (e.g. 1.21)")
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Snapshots", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f)); PixelToggle(snapshots, { snapshots = it })
                }
                if (ui.manifest == null) Text(ui.manifestError ?: "Loading version list…", color = p.textSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp).background(Color(0x55000000))) {
                    items(versions, key = { it.id }) { v ->
                        val sel = v.id == version
                        Row(Modifier.fillMaxWidth().background(if (sel) Color(0xFF3C8527) else Color.Transparent).clickable { version = v.id }.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(v.id, color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(v.type.id, color = if (sel) Color.White else p.textMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.85f)) {
                PixelTextField(name, { name = it }, placeholder = version.ifBlank { "Profile name" }, label = "Name")
                Spacer(Modifier.height(10.dp))
                Text("Type", color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                PixelTabs(NewKind.entries, kind, { it.label }, { kind = it }, icon = { it.icon })
                Spacer(Modifier.height(8.dp))
                Text(when (kind) {
                    NewKind.VANILLA -> "Plain Minecraft $version."
                    NewKind.FABRIC -> "Installs the newest stable Fabric loader for $version. Browse adds mods for it automatically."
                    NewKind.QUILT -> "Installs the newest Quilt loader for $version."
                    NewKind.OPTIFINE -> "Next you pick the OptiFine jar for $version from optifine.net (it opens inside the app). It is installed and patched locally."
                }, color = p.textSecondary, fontSize = 12.sp)
                Text("Forge/NeoForge need a desktop installer process and are not supported on Android yet.", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
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
        ScreenHeader("Edit profile", onBack) { PixelButton("Save", { vm.saveProfile(e); onBack() }, style = PxStyle.PRIMARY, icon = "check", height = 40.dp) }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelTextField(e.name, { e = e.copy(name = it) }, label = "Name")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Minecraft", e.versionId)
                    e.loader?.let { InfoRow("Loader", "${it.type.name.lowercase()} ${it.version}") }
                    if (e.isOptiFine) InfoRow("OptiFine", e.launchVersionId!!.substringAfter("-OptiFine_"))
                    SectionTitle("Icon")
                    FlowRowCompat {
                        PROFILE_ICON_CHOICES.forEach { ic ->
                            PixelIconButton(ic, ic, { e = e.copy(iconKey = ic) }, style = if (profileIcon(e) == ic) PxStyle.SELECTED else PxStyle.NORMAL, size = 40.dp)
                        }
                    }
                }
                SectionTitle("Memory")
                PixelPanel(Modifier.fillMaxWidth()) {
                    val maxHeap = vm.maxHeapMb()
                    SwitchRow("Automatic", "Recommended ${vm.recommendedHeapMb()} MB for this device", e.memoryMb == null) { e = e.copy(memoryMb = if (it) null else vm.recommendedHeapMb()) }
                    e.memoryMb?.let { m -> SliderRow("Allocated", m.toFloat(), 512f..maxHeap.toFloat().coerceAtLeast(513f), format = { "${it.toInt()} MB" }) { v -> e = e.copy(memoryMb = (v / 64).toInt() * 64) } }
                    Text("Upper limit $maxHeap MB keeps enough memory for Android and the renderer.", color = p.textMuted, fontSize = 11.sp)
                }
                SectionTitle("Renderer")
                PixelPanel(Modifier.fillMaxWidth()) {
                    ChoiceRow("Backend", listOf("auto") + renderers.map { it.id }, e.rendererId, label = { rid -> if (rid == "auto") "Automatic" else renderers.first { it.id == rid }.displayName }) { e = e.copy(rendererId = it, rendererMemo = null) }
                    Text("Automatic picks the first renderer that is installed and supported by this device and version, and remembers what worked.", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    renderers.forEach { r ->
                        val st = remember(r.id) { runCatching { r.status(pro.cslauncher.core.renderer.RendererEnvironment(Services.platform, android.os.Build.VERSION.SDK_INT, Services.device.glesVersion, Services.device.vulkanApi, Services.components.availableLibraries(Services.abi), e.versionId, true, false)) }.getOrNull() }
                        if (st != null) Text("${r.displayName}: ${if (st.state == RendererState.AVAILABLE) "available" else st.reason}", color = if (st.state == RendererState.AVAILABLE) p.success else p.textSecondary, fontSize = 11.sp)
                    }
                    SliderRow("Render scale", e.renderScale, 0.3f..1f, format = { "${(it * 100).toInt()}%" }) { e = e.copy(renderScale = (it * 20).toInt() / 20f) }
                    // Options of the selected backend (Automatic: MobileGlues, which every modern version uses).
                    val ob = renderers.firstOrNull { it.id == (if (e.rendererId == "auto") "mobileglues" else e.rendererId) }
                    if (ob != null && ob.options.isNotEmpty()) {
                        Text("${ob.displayName} options", color = p.text, fontSize = 12.sp, fontFamily = Monocraft, modifier = Modifier.padding(top = 8.dp))
                        ob.options.forEach { o ->
                            val cur = e.rendererOptions[o.key] ?: o.default
                            val title = o.label + if (o.experimental) " (experimental)" else ""
                            fun set(v: String) { e = e.copy(rendererOptions = if (v == o.default) e.rendererOptions - o.key else e.rendererOptions + (o.key to v)) }
                            when (o.type) {
                                OptionType.BOOL -> SwitchRow(title, o.description, cur == "1" || cur == "true") { set(if (it) "1" else "0") }
                                OptionType.CHOICE -> {
                                    ChoiceRow(title, o.choices, if (cur in o.choices) cur else o.default,
                                        label = { c -> if (c.length == 2 && c.all(Char::isDigit)) "${c[0]}.${c[1]}" else c.replaceFirstChar(Char::uppercase) }) { set(it) }
                                    Text(o.description, color = p.textMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PixelPanel(Modifier.fillMaxWidth()) {
                    ChoiceRow("Java runtime", listOf<String?>(null) + runtimes.map { it.id }, e.runtimeId, label = { it ?: "Automatic" }) { e = e.copy(runtimeId = it) }
                    if (runtimes.isEmpty()) Text("Runtimes are unpacked from the app on first launch.", color = p.textMuted, fontSize = 11.sp)
                }
                SectionTitle("Controls")
                PixelPanel(Modifier.fillMaxWidth()) {
                    ChoiceRow("Layout", layouts.map { it.id }, e.controlLayoutId, label = { lid -> layouts.first { it.id == lid }.name }) { e = e.copy(controlLayoutId = it) }
                    Spacer(Modifier.height(8.dp))
                    PixelButton("Open control editor", { ControlEditorActivity.open(vm.getApplication(), e.controlLayoutId) }, icon = "gamepad")
                }
                SectionTitle("Game folder")
                PixelPanel(Modifier.fillMaxWidth()) {
                    ChoiceRow("Location", GameDirMode.entries.filter { it != GameDirMode.CUSTOM }, e.gameDirMode, label = { if (it == GameDirMode.ISOLATED) "Separate" else "Shared" }) { e = e.copy(gameDirMode = it) }
                }
                SectionTitle("Advanced")
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelTextField(e.jvmArgs, { e = e.copy(jvmArgs = it) }, label = "JVM arguments", singleLine = false)
                    jvmFindings.forEach { f -> Text("${f.arg}: ${f.message}", color = if (f.severity == JvmArgs.Severity.ERROR) p.danger else p.warning, fontSize = 11.sp) }
                    Spacer(Modifier.height(8.dp))
                    PixelTextField(e.gameArgs, { e = e.copy(gameArgs = it) }, label = "Game arguments", singleLine = false)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
