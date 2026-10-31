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
import androidx.compose.material3.*
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
    var search by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Profile?>(null) }
    val mrpackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { copyToCache(ctx, uri) } }
            f.onSuccess { vm.installModpack(it) }.onFailure { vm.toast(it.message) }
        }
    }

    var filterReleases by remember { mutableStateOf(true) }
    var filterSnapshots by remember { mutableStateOf(true) }
    var filterModded by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Installations") {
            HeaderAction("Modpack", "chest", { mrpackPicker.launch(arrayOf("*/*")) })
            HeaderAction("OptiFine", "bolt", { onOpen(Overlay.OptiFine(ui.selected?.id)) })
            HeaderAction("Import JSON", "upload", { importing = true }, compact = true)
            HeaderAction("New...", "plus", { creating = true }, style = PxStyle.PRIMARY)
        }

        // Top Action Bar: + New... | + Modpack | Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CubiqMinecraftButton(
                onClick = { creating = true },
                style = MinecraftButtonStyle.STONE,
                text = "+ New...",
                fontSize = 12.sp,
                modifier = Modifier.height(36.dp)
            )

            CubiqMinecraftButton(
                onClick = { mrpackPicker.launch(arrayOf("*/*")) },
                style = MinecraftButtonStyle.STONE,
                text = "+ Modpack",
                fontSize = 12.sp,
                modifier = Modifier.height(36.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(Color(0xFF1E1E20))
                    .border(
                        androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (search.isNotEmpty()) Color(0xFF55FF55) else Color(0xFF383838)
                        ),
                        androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontFamily = Monocraft,
                            fontSize = 12.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF55FF55)),
                        decorationBox = { innerTextField ->
                            if (search.isEmpty()) {
                                Text(
                                    text = "Search installations...",
                                    color = Color(0xFF777777),
                                    fontFamily = Monocraft,
                                    fontSize = 12.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (search.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = Color(0xFF888888),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { search = "" }
                                .padding(start = 6.dp)
                        )
                    }
                }
            }
        }

        // Filters Row: Checkboxes for Releases, Snapshots, Modded
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VERSIONS:",
                color = Color(0xFF888888),
                fontFamily = Monocraft,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterReleases = !filterReleases }) {
                Checkbox(
                    checked = filterReleases,
                    onCheckedChange = { filterReleases = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF3C8527), checkmarkColor = Color.White, uncheckedColor = Color(0xFF666666)),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Releases", color = if (filterReleases) Color.White else Color(0xFF888888), fontFamily = Monocraft, fontSize = 11.sp)
            }

            Spacer(Modifier.width(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterSnapshots = !filterSnapshots }) {
                Checkbox(
                    checked = filterSnapshots,
                    onCheckedChange = { filterSnapshots = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF3C8527), checkmarkColor = Color.White, uncheckedColor = Color(0xFF666666)),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Snapshots", color = if (filterSnapshots) Color.White else Color(0xFF888888), fontFamily = Monocraft, fontSize = 11.sp)
            }

            Spacer(Modifier.width(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterModded = !filterModded }) {
                Checkbox(
                    checked = filterModded,
                    onCheckedChange = { filterModded = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF3C8527), checkmarkColor = Color.White, uncheckedColor = Color(0xFF666666)),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Modded", color = if (filterModded) Color.White else Color(0xFF888888), fontFamily = Monocraft, fontSize = 11.sp)
            }
        }

        val filtered = ui.profiles.profiles.filter { pr ->
            val isModded = pr.loader != null || pr.isOptiFine
            val isSnapshot = pr.versionId.contains("snapshot", ignoreCase = true) || pr.versionId.contains("w", ignoreCase = true) || pr.name.contains("snapshot", ignoreCase = true)
            val isRelease = !isModded && !isSnapshot

            val matchesFilter = (filterModded && isModded) ||
                    (filterSnapshots && isSnapshot) ||
                    (filterReleases && isRelease) ||
                    (!filterModded && !filterSnapshots && !filterReleases)

            val matchesSearch = search.isBlank() || pr.name.contains(search.trim(), ignoreCase = true) || pr.versionId.contains(search.trim(), ignoreCase = true)
            matchesFilter && matchesSearch
        }

        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PixelIcon("grass", size = 55.dp)
                Spacer(Modifier.height(10.dp))
                PixelTitle(if (ui.profiles.profiles.isEmpty()) "No installations yet" else "No installations found", fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                CubiqMinecraftButton(
                    onClick = { creating = true },
                    style = MinecraftButtonStyle.GREEN,
                    text = "Create installation",
                    fontSize = 12.sp,
                    modifier = Modifier.height(38.dp)
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp)
        ) {
            items(filtered, key = { it.id }) { pr ->
                val sel = pr.id == ui.selected?.id
                val installed = pr.effectiveVersionId in ui.installed && pr.installationState !in listOf("pending", "installing", "incomplete")
                val isModded = pr.loader != null || pr.isOptiFine
                val blockIconRes = when {
                    isModded -> pro.cslauncher.app.R.drawable.img_diamond_block
                    pr.versionId.contains("snapshot", ignoreCase = true) || pr.name.contains("snapshot", ignoreCase = true) -> pro.cslauncher.app.R.drawable.img_command_block
                    else -> pro.cslauncher.app.R.drawable.img_old_grass_block
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(if (sel) Color(0xFF222428) else Color(0xFF1B1C1E))
                        .border(
                            androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (sel) Color(0xFF3C8527) else Color(0xFF2E3033)
                            ),
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .clickable { vm.select(pr.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pr.customIconId != null) {
                        ProfileIconImage(pr, 38.dp)
                    } else {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(blockIconRes),
                            contentDescription = pr.name,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pr.name,
                                color = Color.White,
                                fontFamily = Monocraft,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (sel) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                        .background(Color(0xFF1E3A1E))
                                        .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3C8527)), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        color = Color(0xFF55FF55),
                                        fontFamily = Monocraft,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = profileSubtitle(pr),
                            color = Color(0xFF888888),
                            fontFamily = Monocraft,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Green 3D PLAY Button
                    CubiqMinecraftButton(
                        onClick = {
                            vm.select(pr.id)
                            vm.playProfile(pr.id)
                        },
                        style = MinecraftButtonStyle.GREEN,
                        text = if (installed) "Play" else "Install",
                        fontSize = 12.sp,
                        enabled = !ui.launching && !ui.gameRunning,
                        modifier = Modifier
                            .width(68.dp)
                            .height(34.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    // Edit Button
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(Color(0xFF28282A))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3C3C3E)), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .clickable { onEdit(pr.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        PixelIcon("edit", size = 16.dp)
                    }

                    Spacer(Modifier.width(4.dp))

                    // Three Dots Options Button
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(Color(0xFF28282A))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3C3C3E)), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .clickable { menuFor = pr },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "...",
                            color = Color.White,
                            fontFamily = Monocraft,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 14.sp
                        )
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
                PixelButton("Install / retry", { menuFor = null; vm.installProfile(pr.id) }, Modifier.fillMaxWidth(), height = 36.dp, icon = "download")
                PixelButton("Repair files", { menuFor = null; vm.repair(pr.effectiveVersionId) }, Modifier.fillMaxWidth(), height = 36.dp, icon = "refresh")
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

private enum class NewKind(val label: String, val icon: String, val supported: Boolean = true, val reason: String = "") {
    VANILLA("Vanilla", "grass"),
    FABRIC("Fabric", "pickaxe"),
    QUILT("Quilt", "book"),
    // Shown so users can see what exists, but never offered as a working choice: the core has no
    // installer-processor support and must not fake these loaders.
    FORGE("Forge", "bolt", false, "Forge needs installer processors that patch the client JAR. This launcher cannot run them on Android yet."),
    NEOFORGE("NeoForge", "star", false, "NeoForge needs installer processors that patch the client JAR. This launcher cannot run them on Android yet."),
    OPTIFINE("OptiFine", "bolt"),
}

@Composable
internal fun CreateProfileDialog(vm: LauncherViewModel, onDismiss: () -> Unit, onOptiFine: (String) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var customIconId by remember { mutableStateOf<String?>(null) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importProfileIcon(ctx, uri) } }
            result.onSuccess { customIconId = it; vm.toast("Profile icon updated") }.onFailure { vm.toast(it.message ?: "Could not read image") }
        }
    }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf(settings.showSnapshots) }
    var version by remember { mutableStateOf(ui.manifest?.latestRelease ?: "") }
    var kind by remember { mutableStateOf(NewKind.VANILLA) }
    var memory by remember { mutableStateOf(settings.defaultMemoryMb) }
    var runtime by remember { mutableStateOf(settings.defaultRuntimeId) }
    var renderer by remember { mutableStateOf(settings.defaultRenderer) }
    var icon by remember { mutableStateOf("grass") }
    var jvm by remember { mutableStateOf(settings.defaultJvmArgs) }
    var directory by remember { mutableStateOf(GameDirMode.ISOLATED) }
    var advanced by remember { mutableStateOf(false) }
    var loaderVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var latestLoader by remember { mutableStateOf<String?>(null) }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var loaderMenuExpanded by remember { mutableStateOf(false) }
    var loaderLoading by remember { mutableStateOf(false) }
    var loaderError by remember { mutableStateOf<String?>(null) }
    var runtimes by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { runtimes = withContext(Dispatchers.IO) { Services.runtimes.list().map { it.id } } }
    LaunchedEffect(version, kind) {
        loaderVersions = emptyList(); latestLoader = null; selectedLoader = null; loaderMenuExpanded = false; loaderError = null
        if (kind == NewKind.FABRIC || kind == NewKind.QUILT) {
            loaderLoading = true
            try {
                val available = withContext(Dispatchers.IO) {
                    pro.cslauncher.core.content.LoaderInstaller(Services.http, Services.dirs)
                        .versions(LoaderType.valueOf(kind.name), version)
                }
                loaderVersions = available.map { it.version }
                latestLoader = available.firstOrNull { it.stable }?.version ?: available.firstOrNull()?.version
                selectedLoader = latestLoader
                if (loaderVersions.isEmpty()) loaderError = "No compatible loader for $version"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { loaderError = e.message ?: "Loader metadata unavailable" }
            finally { loaderLoading = false }
        } else loaderLoading = false
    }
    val types = buildSet { add(VersionType.RELEASE); if (snapshots) add(VersionType.SNAPSHOT); if (settings.showHistorical) { add(VersionType.OLD_BETA); add(VersionType.OLD_ALPHA) } }
    val versions = remember(ui.manifest, query, snapshots) { ui.manifest?.filter(types, query).orEmpty() }
    val displayName = name.trim().ifBlank { "$version ${kind.label}".trim() }
    val duplicateName = ui.profiles.profiles.any { it.name.equals(displayName, ignoreCase = true) }
    PixelDialog(onDismiss, "New profile", widthFraction = 0.9f, scroll = false, buttons = {
        PixelButton("Cancel", onDismiss)
        PixelButton(if (kind == NewKind.OPTIFINE) "Continue" else "Create", {
            when (kind) {
                NewKind.OPTIFINE -> onOptiFine(version)
                else -> { vm.createProfile(name, version, LoaderType.valueOf(kind.name), memory, runtime, renderer, icon, jvm, directory, selectedLoader, customIconId); onDismiss() }
            }
        }, style = PxStyle.PRIMARY, enabled = version.isNotBlank() && !loaderLoading && loaderError == null && kind.supported && !duplicateName, icon = "check")
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
                PixelTextField(name, { name = it }, placeholder = "$version ${kind.label}".trim().ifBlank { "Profile name" }, label = "Name")
                if (duplicateName) Text("An instance named '$displayName' already exists. Choose a different name.", color = p.danger, fontSize = 10.sp, lineHeight = 12.sp)
                else Text("Internal ID is generated separately, so you can rename this instance later.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Type", color = Color(0xFFCFD0D2), fontFamily = Monocraft, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                PixelTabs(NewKind.entries, kind, { it.label }, { kind = it }, enabled = { it.supported }, icon = { it.icon }, height = 32.dp)
                Spacer(Modifier.height(6.dp))
                Text(when (kind) {
                    NewKind.VANILLA -> "Plain Minecraft $version."
                    NewKind.FABRIC -> "Installs the newest stable Fabric loader for $version. Browse adds mods for it automatically."
                    NewKind.QUILT -> "Installs the newest Quilt loader for $version."
                    NewKind.FORGE -> NewKind.FORGE.reason
                    NewKind.NEOFORGE -> NewKind.NEOFORGE.reason
                    NewKind.OPTIFINE -> "Next you pick the OptiFine jar for $version from optifine.net (it opens inside the app). It is installed and patched locally."
                }, color = if (kind.supported) p.textSecondary else p.warning, fontSize = 11.sp, lineHeight = 13.sp)
                if (!kind.supported) Text("Not installable on this launcher — the button stays disabled rather than pretending to work.", color = p.danger, fontSize = 10.sp, lineHeight = 12.sp)
                if (loaderLoading) PixelProgress(null)
                loaderError?.let { Text(it, color = p.danger, fontSize = 11.sp) }
                if (loaderVersions.isNotEmpty()) {
                    if (p.pixel) {
                        ChoiceRow("Loader version", loaderVersions, selectedLoader ?: loaderVersions.first(), label = { it }) { selectedLoader = it }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Loader version", color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (latestLoader != null) Badge("LATEST STABLE", p.success)
                            }
                            Spacer(Modifier.height(4.dp))
                            Box {
                                PixelButton("${selectedLoader ?: latestLoader ?: "Choose version"}  ▾", { loaderMenuExpanded = true }, Modifier.fillMaxWidth(), icon = "list", height = 40.dp)
                                DropdownMenu(expanded = loaderMenuExpanded, onDismissRequest = { loaderMenuExpanded = false }) {
                                    loaderVersions.forEach { loaderVersion ->
                                        DropdownMenuItem(
                                            text = { Text(loaderVersion + if (loaderVersion == latestLoader) "  ·  Latest stable" else "") },
                                            onClick = { selectedLoader = loaderVersion; loaderMenuExpanded = false },
                                            trailingIcon = if (loaderVersion == selectedLoader) ({ Text("✓", color = p.accent) }) else null,
                                        )
                                    }
                                }
                            }
                            Text("The newest stable loader is selected automatically. Tap to see every loader available for Minecraft $version.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp)
                        }
                    }
                }
                Text("Icon", color = p.textSecondary, fontSize = 11.sp)
                FlowRowCompat { PROFILE_ICON_CHOICES.forEach { ic -> PixelIconButton(ic, ic, { icon = ic; customIconId = null }, style = if (icon == ic && customIconId == null) PxStyle.SELECTED else PxStyle.NORMAL, size = 32.dp) } }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (customIconId != null) ProfileIconImage(Profile(name = displayName, versionId = version, iconKey = icon, customIconId = customIconId), 34.dp)
                    PixelButton(if (customIconId == null) "Choose image" else "Change image", { iconPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) }, height = 32.dp, icon = "image")
                    if (customIconId != null) PixelButton("Remove", { customIconId = null }, style = PxStyle.DANGER, height = 32.dp)
                }
                Text("PNG, JPG, WebP or animated GIF · up to 4 MB", color = p.textMuted, fontSize = 9.sp)
                SwitchRow("Automatic memory", "Recommended ${vm.recommendedHeapMb()} MB", memory == null) { memory = if (it) null else vm.recommendedHeapMb() }
                memory?.let { m ->
                    SliderRow("RAM", m.toFloat().coerceIn(512f, vm.maxHeapMb().toFloat().coerceAtLeast(513f)), 512f..vm.maxHeapMb().toFloat().coerceAtLeast(513f), format = { "${it.toInt()} MB" }) { memory = (it.toInt() / 64) * 64 }
                    FlowRowCompat { listOf(1024, 2048, 3072, 4096).filter { it <= vm.maxHeapMb() }.forEach { mb -> PixelButton("$mb MB", { memory = mb }, height = 30.dp) } }
                }
                ChoiceRow("Java", listOf<String?>(null) + runtimes, runtime, label = { it ?: "Automatic (version compatible)" }) { runtime = it }
                ChoiceRow("Renderer", listOf("auto") + Services.renderers.backends.map { it.id }, renderer, label = { it }) { renderer = it }
                ChoiceRow("Game directory", listOf(GameDirMode.ISOLATED, GameDirMode.SHARED), directory, label = { it.name.lowercase() }) { directory = it }
                Text(if (directory == GameDirMode.ISOLATED) "New isolated folder under ${Services.dirs.instances}" else Services.dirs.sharedGameDir.toString(), color = p.textMuted, fontSize = 10.sp)
                PixelButton(if (advanced) "Hide advanced" else "Advanced options", { advanced = !advanced }, height = 32.dp)
                if (advanced) PixelTextField(jvm, { jvm = it }, label = "JVM arguments", singleLine = false)
                Text("Create starts installation now. Downloads continue in the background; failed or interrupted installs can be retried from Downloads.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun ProfileEditorScreen(vm: LauncherViewModel, id: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = ui.profiles.profiles.firstOrNull { it.id == id } ?: run { onBack(); return }
    var e by remember(id) { mutableStateOf(original) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importProfileIcon(ctx, uri) } }
            result.onSuccess { e = e.copy(customIconId = it); vm.toast("Profile icon updated") }.onFailure { vm.toast(it.message ?: "Could not read image") }
        }
    }
    val layouts = remember { ControlLayoutStore(Services.dirs.config.resolve("controls")).list() }
    val renderers = remember { Services.renderers.backends }
    val runtimes = remember { runCatching { Services.runtimes.list() }.getOrDefault(emptyList()) }
    val jvmFindings = remember(e.jvmArgs) { JvmArgs.validate(JvmArgs.tokenize(e.jvmArgs)).filter { it.severity != JvmArgs.Severity.INFO } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Edit profile", onBack) { HeaderAction("Save", "check", { if (ProfileValidator.validate(e).isEmpty() && (e.memoryMb ?: 512) <= vm.maxHeapMb()) { vm.saveProfile(e); onBack() } else vm.toast("Correct the invalid profile settings before saving") }, style = PxStyle.PRIMARY) }
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
                            PixelIconButton(ic, ic, { e = e.copy(iconKey = ic, customIconId = null) }, style = if (e.customIconId == null && profileIcon(e) == ic) PxStyle.SELECTED else PxStyle.NORMAL, size = 34.dp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.customIconId != null) ProfileIconImage(e, 34.dp)
                        PixelButton(if (e.customIconId == null) "Choose image" else "Change image", { iconPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) }, height = 32.dp, icon = "image")
                        if (e.customIconId != null) PixelButton("Remove", { e = e.copy(customIconId = null) }, style = PxStyle.DANGER, height = 32.dp)
                    }
                    Text("PNG, JPG, WebP or animated GIF · up to 4 MB", color = p.textMuted, fontSize = 9.sp)
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
                        val st = remember(r.id, e.versionId) { runCatching { r.status(pro.cslauncher.core.renderer.RendererEnvironment(Services.platform, android.os.Build.VERSION.SDK_INT, Services.device.glesVersion, Services.device.vulkanApi, Services.components.availableLibraries(Services.abi), e.versionId, true, false)) }.getOrNull() }
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
                    Text("Changing location does not move or delete existing worlds or mods. The old folder is preserved.", color = p.warning, fontSize = 11.sp)
                    Text(runCatching { e.gameDir(Services.dirs).toString() }.getOrDefault("Invalid directory"), color = p.textMuted, fontSize = 10.sp)
                    ChoiceRow("Location", GameDirMode.entries.filter { it != GameDirMode.CUSTOM }, e.gameDirMode, label = { if (it == GameDirMode.ISOLATED) "Separate" else "Shared" }) { e = e.copy(gameDirMode = it) }
                }
                PanelSection("Advanced", "settings") {
                    SwitchRow("Fullscreen game", "Game window preference; Android stays landscape", e.fullscreen) { e = e.copy(fullscreen = it) }
                    ChoiceRow("Resolution", listOf("Device", "854×480", "1280×720", "1920×1080"), e.resolutionWidth?.let { "${it}×${e.resolutionHeight}" } ?: "Device", label = { it }) { value ->
                        val dims = value.split("×")
                        e = e.copy(resolutionWidth = dims.firstOrNull()?.toIntOrNull(), resolutionHeight = dims.getOrNull(1)?.toIntOrNull())
                    }

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
