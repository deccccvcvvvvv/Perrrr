package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.activity.ActivityItem
import pro.cslauncher.core.activity.ActivityKind
import pro.cslauncher.core.content.*

@Composable
fun BrowseScreen(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.selected
    val scope = rememberCoroutineScope()
    val cfKey = remember { Services.secrets.get("curseforge.apiKey") }
    val catalogs = remember(cfKey) { listOf<ContentCatalog>(ModrinthCatalog(Services.http), CurseForgeCatalog(Services.http, cfKey)) }
    var catalog by remember { mutableStateOf(catalogs.first()) }
    var kind by remember { mutableStateOf(ProjectKind.MOD) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogProject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<CatalogProject?>(null) }
    val loader = profile?.loader?.type?.name?.lowercase()

    fun search() {
        loading = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { catalog.search(query, kind, profile?.versionId, if (kind == ProjectKind.MOD) loader else null) } }
            results = r.getOrDefault(emptyList()); error = r.exceptionOrNull()?.message; loading = false
        }
    }
    LaunchedEffect(kind, catalog) { if (catalog.available) search() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Browse resources")
        Column(Modifier.padding(horizontal = 16.dp)) {
            ChoiceRow("Type", listOf(ProjectKind.MOD, ProjectKind.RESOURCE_PACK, ProjectKind.SHADER), kind,
                label = { when (it) { ProjectKind.MOD -> "Mods"; ProjectKind.RESOURCE_PACK -> "Resource packs"; ProjectKind.SHADER -> "Shaders"; else -> it.name } }) { kind = it }
            ChoiceRow("Source", catalogs, catalog, label = { it.name + if (!it.available) " (needs API key)" else "" }, enabled = { it.available }) { catalog = it }
            OutlinedTextField(query, { query = it }, label = { Text("Search ${catalog.name}") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { search() }) { Text("Search", color = p.accent) } })
            when {
                profile == null -> Text("Select a profile to see compatible content.", color = p.textMuted, modifier = Modifier.padding(top = 8.dp))
                kind == ProjectKind.MOD && profile.loader == null -> Text("This profile is vanilla. Mods need a Fabric or Quilt profile.", color = p.warning, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                else -> Text("Filtered for ${profile.versionId}${loader?.let { " · $it" } ?: ""}", color = p.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = p.accent, trackColor = p.secondarySurface)
            error?.let { Text("Could not load results: $it", color = p.danger, fontSize = 13.sp) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(results, key = { it.source + it.id }) { r ->
                CsCard(Modifier.fillMaxWidth().clickable { opened = r }) {
                    Text(r.title, color = p.text, fontWeight = FontWeight.Medium)
                    Text(r.description, color = p.textSecondary, fontSize = 13.sp, maxLines = 2)
                    Text("${r.author ?: ""} · ${r.downloads} downloads", color = p.textMuted, fontSize = 12.sp)
                }
            }
        }
    }

    opened?.let { proj ->
        var files by remember(proj) { mutableStateOf<List<CatalogFile>?>(null) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(proj) { files = withContext(Dispatchers.IO) { runCatching { catalog.files(proj.id, profile?.versionId, if (kind == ProjectKind.MOD) loader else null) }.getOrDefault(emptyList()) } }
        AlertDialog(onDismissRequest = { opened = null }, containerColor = p.elevated, titleContentColor = p.text, textContentColor = p.textSecondary,
            title = { Text(proj.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(proj.description, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    when {
                        files == null -> LinearProgressIndicator(color = p.accent)
                        files!!.isEmpty() -> Text("No file is compatible with ${profile?.versionId ?: "this profile"}.", color = p.warning)
                        else -> files!!.take(8).forEach { f ->
                            ClickRow(f.versionName, "${f.fileName} · ${f.size?.let(::formatBytes) ?: "?"}" + if (f.requiredDependencies.isNotEmpty()) " · needs ${f.requiredDependencies.size} dependency(ies)" else "", onClick = {
                                if (busy || profile == null) return@ClickRow
                                busy = true
                                scope.launch {
                                    val gameDir = profile.gameDir(Services.dirs)
                                    val dir = gameDir.resolve(when (kind) { ProjectKind.MOD -> "mods"; ProjectKind.SHADER -> "shaderpacks"; else -> "resourcepacks" })
                                    val res = withContext(Dispatchers.IO) { runCatching { java.nio.file.Files.createDirectories(dir); Services.downloads.downloadAll("content", listOf(f.task(dir))) } }
                                    busy = false; opened = null
                                    val ok = res.getOrNull()?.ok == true
                                    vm.toast(if (ok) "Installed ${f.fileName} (checksum verified)" else "Download failed: ${res.exceptionOrNull()?.message ?: res.getOrNull()?.summary()}")
                                    if (ok && kind == ProjectKind.MOD) Services.activity.add(ActivityItem(ActivityKind.MOD_IMPORTED, "Added ${proj.title}", "${profile.name} · ${f.versionName}", profileId = profile.id), Services.settings.state.value.recentActivityEnabled)
                                }
                            })
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                }
            },
            confirmButton = { TextButton(onClick = { opened = null }) { Text("Close") } })
    }
}
