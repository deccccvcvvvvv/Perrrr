package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.isOptiFine
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.content.*
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.FsUtil

private val KINDS = listOf(ProjectKind.MOD, ProjectKind.MODPACK, ProjectKind.RESOURCE_PACK, ProjectKind.SHADER)
private fun kindLabel(k: ProjectKind) = when (k) { ProjectKind.MOD -> "Mods"; ProjectKind.MODPACK -> "Modpacks"; ProjectKind.RESOURCE_PACK -> "Resource packs"; ProjectKind.SHADER -> "Shaders"; else -> k.name }
private fun kindShort(k: ProjectKind) = when (k) { ProjectKind.RESOURCE_PACK -> "Textures"; else -> kindLabel(k) }
private fun kindIcon(k: ProjectKind) = when (k) { ProjectKind.MOD -> "pickaxe"; ProjectKind.MODPACK -> "chest"; ProjectKind.RESOURCE_PACK -> "painting"; ProjectKind.SHADER -> "sun"; else -> "folder" }
private enum class PackLoader(val id: String?, val label: String) { FABRIC("fabric", "Fabric"), QUILT("quilt", "Quilt"), ANY(null, "Any") }

fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
fun BrowseScreen(vm: LauncherViewModel, onOpen: (Overlay) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.selected
    val scope = rememberCoroutineScope()
    val cfKey = remember { Services.secrets.get("curseforge.apiKey") }
    val catalogs = remember(cfKey) { listOf<ContentCatalog>(ModrinthCatalog(Services.http), CurseForgeCatalog(Services.http, cfKey)) }
    var catalog by remember { mutableStateOf(catalogs.first()) }
    var kind by rememberSaveable { mutableStateOf(ProjectKind.MOD) }
    var query by rememberSaveable { mutableStateOf("") }
    var packLoader by remember { mutableStateOf(PackLoader.FABRIC) }
    var packMatchVersion by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CatalogProject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<CatalogProject?>(null) }
    var pickProfile by remember { mutableStateOf(false) }
    var installedTick by remember { mutableIntStateOf(0) }
    var searchGen by remember { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()

    val vanillaForMods = kind == ProjectKind.MOD && profile != null && (profile.loader?.type ?: LoaderType.VANILLA) == LoaderType.VANILLA
    val gameVersion = when { kind == ProjectKind.MODPACK && !packMatchVersion -> null; else -> profile?.versionId }
    val loader = when {
        kind == ProjectKind.MODPACK -> packLoader.id
        vanillaForMods -> "fabric"   // show what would work after switching to a Fabric profile
        profile == null -> null
        else -> vm.profileLoaderId(profile, kind)
    }
    val installed by produceState(emptyMap<String, IndexedContent>(), profile?.id, kind, installedTick) {
        value = if (profile == null) emptyMap() else withContext(Dispatchers.IO) { vm.installedContent(profile, kind) }
    }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        val gen = if (reset) ++searchGen else searchGen
        val offset = if (reset) 0 else results.size
        loading = true; error = null
        if (reset) { endReached = false }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { catalog.search(query.trim(), kind, gameVersion, loader, offset) } }
            if (gen != searchGen) return@launch   // a newer search replaced this one
            r.onSuccess { page ->
                val merged = if (reset) page else (results + page).distinctBy { it.source + it.id }
                endReached = page.isEmpty() || merged.size == results.size && !reset
                results = merged
                if (reset) grid.scrollToItem(0)
            }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(kind, catalog, profile?.id, profile?.versionId, loader, gameVersion) { if (catalog.available) load(true) }
    LaunchedEffect(grid, results.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (results.isNotEmpty() && last >= results.size - 4 && !loading && !endReached && error == null) load(false)
        }
    }

    var showCategories by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CONTENT & MODS HUB", color = Color.White, fontFamily = Monocraft, fontSize = 15.sp, maxLines = 1)
                Text(profile?.let { "Active installation: ${it.name}" } ?: "Select an installation for compatible content", color = p.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CubiqMinecraftButton(
                onClick = { showCategories = !showCategories },
                style = if (showCategories) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                text = if (showCategories) "Browser" else "Categories",
                fontSize = 10.5.sp,
                modifier = Modifier.height(32.dp)
            )
            Spacer(Modifier.width(6.dp))
            CubiqMinecraftButton(
                onClick = { onOpen(Overlay.Content(ContentSection.MODS)) },
                style = MinecraftButtonStyle.STONE,
                enabled = profile != null,
                text = "Installed",
                fontSize = 10.5.sp,
                modifier = Modifier.height(32.dp)
            )
        }

        if (showCategories) {
            // Cubiq 1:1 Content Categories Grid
            val categories = listOf(
                Triple("Modpacks", "Browse & install CurseForge & Modrinth modpacks", pro.cslauncher.app.R.drawable.ic_package_2_outlined) to {
                    kind = ProjectKind.MODPACK
                    showCategories = false
                },
                Triple("Mods", "Search, download and auto-install Fabric mods", pro.cslauncher.app.R.drawable.ic_extension_outlined) to {
                    kind = ProjectKind.MOD
                    showCategories = false
                },
                Triple("Shader Packs", "Enhance visuals with Iris, BSL, Complementary & more", pro.cslauncher.app.R.drawable.ic_lightbulb) to {
                    kind = ProjectKind.SHADER
                    showCategories = false
                },
                Triple("Resource Packs", "Transform block textures, models, fonts and UI sounds", pro.cslauncher.app.R.drawable.ic_format_paint_outlined) to {
                    kind = ProjectKind.RESOURCE_PACK
                    showCategories = false
                },
                Triple("Game Versions", "Install official releases, snapshots & loaders", pro.cslauncher.app.R.drawable.ic_sports_esports_outlined) to {
                    onOpen(Overlay.OptiFine(profile?.id))
                },
                Triple("Worlds & Saves", "Manage local saves, backups and custom maps", pro.cslauncher.app.R.drawable.ic_public) to {
                    onOpen(Overlay.Content(ContentSection.WORLDS))
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "DOWNLOAD CATEGORIES",
                    color = Color(0xFFAAAAAA),
                    fontFamily = Monocraft,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                categories.forEach { (cat, action) ->
                    val (title, subtitle, iconRes) = cat
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E20))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .clickable(onClick = action)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .background(Color(0xFF28282B))
                                    .border(androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Icon(
                                    painter = androidx.compose.ui.res.painterResource(iconRes),
                                    contentDescription = title,
                                    tint = Color(0xFF55FF55),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = Color.White,
                                    fontFamily = Monocraft,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    color = Color(0xFF888888),
                                    fontFamily = Monocraft,
                                    fontSize = 10.5.sp
                                )
                            }

                            Spacer(Modifier.width(6.dp))

                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(pro.cslauncher.app.R.drawable.ic_keyboard_arrow_right),
                                contentDescription = null,
                                tint = Color(0xFF666666),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            return@Column
        }
        // ---------------------------------------------- kind tabs + search (one row; icon-only tabs on narrow phones)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val iconOnly = maxWidth < 640.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KINDS.forEach { k ->
                    PixelButton(if (iconOnly) null else kindShort(k), { kind = k }, style = if (k == kind) PxStyle.SELECTED else PxStyle.NORMAL, icon = kindIcon(k),
                        height = 36.dp, fontSize = 11.sp, contentDescription = kindLabel(k), modifier = if (iconOnly) Modifier.widthIn(min = 36.dp) else Modifier)
                }
                Spacer(Modifier.width(3.dp))
                PixelTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Search ${kindLabel(kind).lowercase()}", onDone = { load(true) })
                PixelIconButton("search", "Search", { load(true) }, style = PxStyle.PRIMARY, size = 38.dp)
            }
        }
        // ---------------------------------------------- context: target profile / pack loader, and source
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind == ProjectKind.MODPACK) {
                Text("New profile with", color = p.textSecondary, fontSize = 11.sp)
                PixelTabs(PackLoader.entries, packLoader, { it.label }, { packLoader = it }, height = 30.dp)
                if (profile != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("Only ${profile.versionId}", color = p.text, fontSize = 11.sp); PixelToggle(packMatchVersion, { packMatchVersion = it })
                }
            } else {
                Text("For", color = p.textSecondary, fontSize = 11.sp)
                PixelButton(profile?.name ?: "Select a profile", { pickProfile = true }, icon = profile?.let(::profileIcon) ?: "list", height = 30.dp, fontSize = 11.sp)
                Text(profile?.let { "${it.versionId}${if (loader != null && !vanillaForMods) " · $loader" else ""} · filtered automatically" } ?: "",
                    color = p.textMuted, fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            PixelTabs(catalogs, catalog, { if (it.available) it.name else "${it.name} (needs key)" }, { catalog = it }, enabled = { it.available }, height = 30.dp)
        }

        // ---------------------------------------------- results
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (vanillaForMods) PixelPanel(Modifier.fillMaxWidth().padding(top = 6.dp), padding = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon("info", size = 20.dp); Spacer(Modifier.width(8.dp))
                    Text("“${profile!!.name}” is vanilla. Mods need a Fabric/Quilt profile; showing Fabric mods for ${profile.versionId}.", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
                    PixelButton("Create Fabric", { vm.createProfile("${profile.versionId} Fabric", profile.versionId, LoaderType.FABRIC) }, style = PxStyle.PRIMARY, height = 32.dp, fontSize = 11.sp)
                }
            }
            if (kind == ProjectKind.SHADER && profile != null && !profile.isOptiFine && profile.loader == null) PixelPanel(Modifier.fillMaxWidth().padding(top = 6.dp), padding = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelIcon("info", size = 20.dp); Spacer(Modifier.width(8.dp))
                    Text("Shaders need OptiFine, or Iris on a Fabric profile.", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PixelButton("Get OptiFine", { onOpen(Overlay.OptiFine(profile.id)) }, icon = "bolt", height = 32.dp, fontSize = 11.sp)
                }
            }
            if (loading && results.isEmpty()) PixelProgress(null, Modifier.padding(top = 10.dp))
            error?.let {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Could not load results: $it", color = p.danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PixelButton("Retry", { load(results.isEmpty()) }, icon = "refresh", height = 32.dp, fontSize = 11.sp)
                }
            }
            if (!loading && error == null && results.isEmpty() && catalog.available)
                Text("Nothing found${gameVersion?.let { " for $it" } ?: ""}.", color = p.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            LazyVerticalGrid(GridCells.Adaptive(270.dp), Modifier.fillMaxSize(), grid, PaddingValues(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(results, key = { _, r -> r.source + r.id }) { i, r ->
                    val isInstalled = installed.containsKey(ContentIndex.key(r.source, r.id))
                    PixelPanel(Modifier.fillMaxWidth().staggerIn(i % 12).clickable { opened = r }, padding = 7.dp) {
                        Row {
                            PixelSlot(size = 48.dp) { RemoteImage(r.iconUrl, 38.dp, fallbackIcon = kindIcon(kind)) }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.title, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (isInstalled) { Spacer(Modifier.width(4.dp)); Badge("INSTALLED", icon = "check") }
                                }
                                Text("${r.author?.let { "by $it · " } ?: ""}${compactCount(r.downloads)} downloads", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.description, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
                if (loading && results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { PixelProgress(null, Modifier.padding(8.dp)) }
            }
        }
    }

    if (pickProfile) PixelDialog({ pickProfile = false }, "Browse for…", widthFraction = 0.6f, buttons = { PixelButton("Close", { pickProfile = false }) }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.profiles.profiles.forEach { pr ->
                PixelButton("${pr.name}  ·  ${profileSubtitle(pr)}", { vm.select(pr.id); pickProfile = false }, Modifier.fillMaxWidth(), height = 36.dp, fontSize = 11.sp,
                    style = if (pr.id == profile?.id) PxStyle.SELECTED else PxStyle.NORMAL, icon = profileIcon(pr))
            }
        }
    }

    opened?.let { proj ->
        val cat = catalog
        var files by remember(proj) { mutableStateOf<List<CatalogFile>?>(null) }
        var filesError by remember(proj) { mutableStateOf<String?>(null) }
        var busy by remember(proj) { mutableStateOf<String?>(null) }
        LaunchedEffect(proj) {
            val r = withContext(Dispatchers.IO) { runCatching { cat.files(proj.id, gameVersion, loader) } }
            files = r.getOrDefault(emptyList()); filesError = r.exceptionOrNull()?.message
        }
        val have = installed[ContentIndex.key(proj.source, proj.id)]
        PixelDialog({ if (busy == null) opened = null }, proj.title, widthFraction = 0.82f, buttons = {
            if (busy != null) { PixelProgress(null, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)) }
            PixelButton("Close", { opened = null }, enabled = busy == null)
        }) {
            Row {
                PixelSlot(size = 56.dp) { RemoteImage(proj.iconUrl, 46.dp, fallbackIcon = kindIcon(kind)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${proj.author?.let { "by $it · " } ?: ""}${compactCount(proj.downloads)} downloads · ${proj.source}", color = p.textMuted, fontSize = 11.sp)
                    Text(proj.description, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (proj.categories.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { FlowRowCompat { proj.categories.take(6).forEach { Chip(it) } } }
                    if (have != null) Box(Modifier.padding(top = 6.dp)) { Badge("INSTALLED · ${have.fileName}", icon = "check") }
                }
            }
            Spacer(Modifier.height(10.dp))
            busy?.let { Text(it, color = p.text, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            when {
                files == null -> PixelProgress(null)
                files!!.isEmpty() -> Text(filesError?.let { "Could not load files: $it" } ?: "No file is compatible with ${gameVersion ?: "this"}${loader?.let { " · $it" } ?: ""}.", color = p.warning, fontSize = 12.sp)
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    files!!.take(15).forEach { f ->
                        val thisInstalled = have?.fileName == FsUtil.safeFileName(f.fileName)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.versionName, color = Color.White, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(f.fileName, f.size?.let(::formatBytes), f.gameVersions.take(3).joinToString().ifBlank { null },
                                    if (f.requiredDependencies.isNotEmpty()) "+${f.requiredDependencies.size} required" else null).joinToString(" · "),
                                    color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                thisInstalled -> Badge("INSTALLED", icon = "check")
                                kind == ProjectKind.MOD && vanillaForMods -> Badge("NEEDS FABRIC", p.warning)
                                else -> PixelButton(if (kind == ProjectKind.MODPACK) "Install pack" else if (have != null) "Switch" else "Install", {
                                    if (kind == ProjectKind.MODPACK) {
                                        if (vm.installModpackFromCatalog(f, proj.title)) { vm.toast("Installing ${proj.title}. Progress is in Downloads."); opened = null }
                                    } else if (profile != null) {
                                        busy = if (f.requiredDependencies.isNotEmpty()) "Installing with required dependencies…" else "Installing…"
                                        scope.launch {
                                            val r = vm.installCatalogFile(cat, proj.title, f, kind, profile)
                                            busy = null; installedTick++
                                            r.onSuccess { vm.toast(it.summary(proj.title)); opened = null }.onFailure { vm.toast("Install failed: ${it.message}") }
                                        }
                                    } else vm.toast("Select a profile first")
                                }, style = PxStyle.PRIMARY, enabled = busy == null, icon = "download", height = 32.dp, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
