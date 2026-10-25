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
import androidx.compose.ui.draw.drawBehind
import pro.cslauncher.core.profile.RunOutcome
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
            HeaderAction("Modpack", "chest", { mrpackPicker.launch(arrayOf("*/*")) })
            HeaderAction("OptiFine", "bolt", { onOpen(Overlay.OptiFine(ui.selected?.id)) })
            HeaderAction("Import profile JSON", "upload", { importing = true }, compact = true)
            HeaderAction("New", "plus", { creating = true }, style = PxStyle.PRIMARY)
        }
        if (ui.profiles.profiles.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PixelIcon("grass", size = 64.dp)
                Spacer(Modifier.height(12.dp))
                PixelTitle("No profiles yet", fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                PixelButton("Create your first profile", { creating = true }, style = PxStyle.PRIMARY, icon = "plus")
            }
        } else LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
            itemsIndexed(ui.profiles.profiles, key = { _, it -> it.id }) { i, pr ->
                val sel = pr.id == ui.selected?.id
                val installed = pr.effectiveVersionId in ui.installed
                PixelPanel(Modifier.fillMaxWidth().staggerIn(i).clickable { vm.select(pr.id) }, selected = sel, padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelSlot(size = 42.dp) { PixelIcon(profileIcon(pr), size = 28.dp) }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            PixelTitle(pr.name, fontSize = 13.sp)
                            Text(profileSubtitle(pr), color = p.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pr.lastPlayedAtMs?.let { "Played ${relativeTime(it)}" } ?: "Never played", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                pr.effectiveVersionId in ui.needsRepair -> Badge("REPAIR", p.danger, icon = "warning")
                                installed -> Badge("READY", icon = "check")
                                else -> Badge("NOT INSTALLED", Color(0xFFB0B0B0), icon = "download")
                            }
                            if (pr.lastRun?.outcome == RunOutcome.CRASHED) Badge("CRASHED", p.danger)
                        }
                        PixelIconButton("edit", "Edit ${pr.name}", { onEdit(pr.id) }, size = 30.dp)
                        Spacer(Modifier.width(4.dp))
                        PixelIconButton("more", "More options for ${pr.name}", { menuFor = pr }, size = 30.dp)
                    }
                }
            }
        }
    }

    if (creating) CreateProfileDialog(vm, onDismiss = { creating = false }, onOptiFine = { v -> creating = false; onOpen(Overlay.OptiFine(null, v)) })

    menuFor?.let { pr ->
        PixelDialog({ menuFor = null }, pr.name, widthFraction = 0.5f, buttons = { PixelButton("Close", { menuFor = null }, height = 34.dp) }) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PixelButton("Edit", { menuFor = null; onEdit(pr.id) }, Modifier.fillMaxWidth(), height = 36.dp, icon = "edit")
                PixelButton("Mods & content", { menuFor = null; vm.select(pr.id); onOpen(Overlay.Content(ContentSection.MODS)) }, Modifier.fillMaxWidth(), height = 36.dp, icon = "pickaxe")
                PixelButton("Duplicate", { menuFor = null; vm.duplicate(pr.id, pr.name + " copy") }, Modifier.fillMaxWidth(), height = 36.dp, icon = "plus")
                PixelButton("Copy export JSON", {
                    menuFor = null
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CS profile", vm.exportProfile(pr.id)))
                    vm.toast("Profile copied to clipboard (no account data included)")
                }, Modifier.fillMaxWidth(), height = 36.dp, icon = "upload")
                PixelButton("Delete", { menuFor = null; deleting = pr.id }, Modifier.fillMaxWidth(), style = PxStyle.DANGER, icon = "trash", height = 36.dp)
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
internal fun CreateProfileDialog(vm: LauncherViewModel, onDismiss: () -> Unit, onOptiFine: (String) -> Unit) {
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
    PixelDialog(onDismiss, "New profile", widthFraction = 0.9f, scroll = false, buttons = {
        PixelButton("Cancel", onDismiss)
        PixelButton(if (kind == NewKind.OPTIFINE) "Continue" else "Create", {
            when (kind) {
                NewKind.OPTIFINE -> onOptiFine(version)
                else -> { vm.createProfile(name, version, LoaderType.valueOf(kind.name)); onDismiss() }
            }
        }, style = PxStyle.PRIMARY, enabled = version.isNotBlank(), icon = "check")
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Search (e.g. 1.21)")
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Snapshots", color = p.textSecondary, fontSize = 9.sp, lineHeight = 10.sp)
                        PixelToggle(snapshots, { snapshots = it })
                    }
                }
                if (ui.manifest == null) Text(ui.manifestError ?: "Loading version list…", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 60.dp).drawBehind { pixelInset(2.dp.toPx()) }.padding(2.dp)) {
                    items(versions, key = { it.id }) { v ->
                        val sel = v.id == version
                        Row(Modifier.fillMaxWidth().background(if (sel) Color(0xFF3C8527) else Color.Transparent).clickable { version = v.id }.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(v.id, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
                            Text(v.type.id, color = if (sel) Color.White else p.textMuted, fontSize = 10.sp, lineHeight = 12.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.9f).verticalScroll(rememberScrollState())) {
                PixelTextField(name, { name = it }, placeholder = version.ifBlank { "Profile name" }, label = "Name")
                Spacer(Modifier.height(8.dp))
                Text("Type", color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                PixelTabs(NewKind.entries, kind, { it.label }, { kind = it }, icon = { it.icon }, height = 32.dp)
                Spacer(Modifier.height(6.dp))
                Text(when (kind) {
                    NewKind.VANILLA -> "Plain Minecraft $version."
                    NewKind.FABRIC -> "Installs the newest stable Fabric loader for $version. Browse adds mods for it automatically."
                    NewKind.QUILT -> "Installs the newest Quilt loader for $version."
                    NewKind.OPTIFINE -> "Next you pick the OptiFine jar for $version from optifine.net (it opens inside the app). It is installed and patched locally."
                }, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp)
                Text("Forge/NeoForge need a desktop installer process and are not supported on Android yet.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 6.dp))
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
        ScreenHeader("Edit profile", onBack) { HeaderAction("Save", "check", { vm.saveProfile(e); onBack() }, style = PxStyle.PRIMARY) }
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelSection("General", "grass") {
                    PixelTextField(e.name, { e = e.copy(name = it) }, label = "Name")
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Minecraft", e.versionId)
                    e.loader?.let { InfoRow("Loader", "${it.type.name.lowercase()} ${it.version}") }
                    if (e.isOptiFine) InfoRow("OptiFine", e.launchVersionId!!.substringAfter("-OptiFine_"))
                    Text("Icon", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    FlowRowCompat {
                        PROFILE_ICON_CHOICES.forEach { ic ->
                            PixelIconButton(ic, ic, { e = e.copy(iconKey = ic) }, style = if (profileIcon(e) == ic) PxStyle.SELECTED else PxStyle.NORMAL, size = 34.dp)
                        }
                    }
                }
                PanelSection("Memory", "chart") {
                    val maxHeap = vm.maxHeapMb()
                    SwitchRow("Automatic", "Recommended ${vm.recommendedHeapMb()} MB for this device", e.memoryMb == null) { e = e.copy(memoryMb = if (it) null else vm.recommendedHeapMb()) }
                    e.memoryMb?.let { m -> SliderRow("Allocated", m.toFloat(), 512f..maxHeap.toFloat().coerceAtLeast(513f), format = { "${it.toInt()} MB" }) { v -> e = e.copy(memoryMb = (v / 64).toInt() * 64) } }
                    Text("Upper limit $maxHeap MB keeps enough memory for Android and the renderer.", color = p.textMuted, fontSize = 11.sp)
                }
                PanelSection("Renderer", "bolt") {
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
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelSection("Java runtime", "terminal") {
                    ChoiceRow("Runtime", listOf<String?>(null) + runtimes.map { it.id }, e.runtimeId, label = { it ?: "Automatic" }) { e = e.copy(runtimeId = it) }
                    if (runtimes.isEmpty()) Text("Runtimes are unpacked from the app on first launch.", color = p.textMuted, fontSize = 11.sp)
                }
                PanelSection("Controls", "gamepad") {
                    ChoiceRow("Layout", layouts.map { it.id }, e.controlLayoutId, label = { lid -> layouts.first { it.id == lid }.name }) { e = e.copy(controlLayoutId = it) }
                    Spacer(Modifier.height(6.dp))
                    LinkButton("Open control editor", { ControlEditorActivity.open(vm.getApplication(), e.controlLayoutId) }, icon = "edit")
                }
                PanelSection("Game folder", "folder") {
                    ChoiceRow("Location", GameDirMode.entries.filter { it != GameDirMode.CUSTOM }, e.gameDirMode, label = { if (it == GameDirMode.ISOLATED) "Separate" else "Shared" }) { e = e.copy(gameDirMode = it) }
                }
                PanelSection("Advanced", "settings") {
                    PixelTextField(e.jvmArgs, { e = e.copy(jvmArgs = it) }, label = "JVM arguments", singleLine = false)
                    jvmFindings.forEach { f -> Text("${f.arg}: ${f.message}", color = if (f.severity == JvmArgs.Severity.ERROR) p.danger else p.warning, fontSize = 11.sp) }
                    Spacer(Modifier.height(8.dp))
                    PixelTextField(e.gameArgs, { e = e.copy(gameArgs = it) }, label = "Game arguments", singleLine = false)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
