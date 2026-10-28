package pro.cslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.R
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.PlayState
import pro.cslauncher.app.ui.Tab
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.launch.FixAction
import pro.cslauncher.core.profile.Profile
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val launcherGreen = Color(0xFF38C916)
private val launcherGreenShade = Color(0xFF25960D)

/** Launcher-style home with Mojang's live Java patch-note feed and official version artwork. */
@Composable
fun MinecraftLauncherHome(vm: LauncherViewModel, onOpen: (Overlay) -> Unit, onTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected = ui.selected
    val state = vm.playState(ui, jobs)
    val currentJob = selected?.let { (jobs["task:profile-${it.id}"] ?: jobs[it.effectiveVersionId])?.takeIf { job -> job.active } }
    val isBusy = state in setOf(PlayState.DOWNLOADING, PlayState.VERIFYING, PlayState.UPDATING, PlayState.PREPARING, PlayState.LAUNCHING, PlayState.PLAYING)
    val canPlay = selected != null && selected.effectiveVersionId in ui.installed && selected.installationState !in setOf("pending", "installing", "incomplete")
    val actionText = when (state) {
        PlayState.NO_PROFILE -> "CREATE INSTALLATION"
        PlayState.INSTALL -> "INSTALL"
        PlayState.LAUNCH_FAILED -> "TRY AGAIN"
        PlayState.REPAIR_REQUIRED -> "REPAIR INSTALLATION"
        PlayState.DOWNLOADING, PlayState.UPDATING -> "${state.label.uppercase()}${currentJob?.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()}"
        PlayState.PREPARING, PlayState.VERIFYING, PlayState.LAUNCHING -> "${state.label.uppercase()}…"
        else -> "PLAY"
    }
    var feed by remember { mutableStateOf<List<OfficialPatchNote>>(emptyList()) }
    var feedLoading by remember { mutableStateOf(true) }
    var feedError by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var newsDialog by remember { mutableStateOf<OfficialPatchNote?>(null) }
    var showFailure by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refreshFeed: () -> Unit = {
        scope.launch {
            feedLoading = true
            val result = withContext(Dispatchers.IO) { runCatching { OfficialMinecraftFeed.load(context.applicationContext, forceRefresh = true) } }
            feed = result.getOrDefault(emptyList())
            feedError = result.isFailure || feed.isEmpty()
            feedLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { OfficialMinecraftFeed.load(context.applicationContext) } }
        feed = result.getOrDefault(emptyList())
        feedLoading = false
        feedError = result.isFailure || feed.isEmpty()
    }

    val recentNews = feed.take(12)

    if (showFailure) ui.failure?.let { failure ->
        val fix = failure.issues.firstOrNull { it.fix != FixAction.NONE }?.fix
        AlertDialog(onDismissRequest = { showFailure = false }, containerColor = Color(0xFF171D24),
            titleContentColor = Color.White, textContentColor = Color.White,
            title = { Text(failure.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                failure.issues.forEach { Text("• ${it.message}", modifier = Modifier.padding(bottom = 5.dp)) }
                failure.diagnoses.forEach { Text("${it.kind.title}: ${it.explanation}", modifier = Modifier.padding(bottom = 5.dp)) }
            } },
            confirmButton = { TextButton(onClick = {
                showFailure = false
                when (fix) {
                    FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> selected?.let { vm.repair(it.effectiveVersionId) }
                    FixAction.SIGN_IN -> onOpen(Overlay.Accounts)
                    FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT, FixAction.FREE_STORAGE -> onTab(Tab.SETTINGS)
                    FixAction.CHOOSE_RENDERER, FixAction.EDIT_PROFILE, FixAction.INSTALL_LOADER, FixAction.FIX_GAME_DIRECTORY -> selected?.let { onOpen(Overlay.EditProfile(it.id)) }
                    else -> onOpen(Overlay.Logs)
                }
            }) { Text(when (fix) {
                FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> "Repair"
                FixAction.SIGN_IN -> "Sign in"
                FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT, FixAction.FREE_STORAGE -> "Settings"
                FixAction.CHOOSE_RENDERER, FixAction.EDIT_PROFILE, FixAction.INSTALL_LOADER, FixAction.FIX_GAME_DIRECTORY -> "Edit profile"
                else -> "Open logs"
            }, color = launcherGreen) } },
            dismissButton = { TextButton(onClick = { showFailure = false; vm.dismissFailure() }) { Text("Dismiss") } })
    }

    if (config.screenWidthDp < 600) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFF0C1116))) {
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                Image(painterResource(R.drawable.launcher_reference_hero), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x18000000), Color(0xD9000000)))))
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    AccountButton(ui.activeAccount?.username ?: "Add account", ui.activeAccount?.type == AccountType.OFFLINE) { onOpen(Overlay.Accounts) }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text("MINECRAFT", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp)
                    Text("JAVA EDITION  ·  ${selected?.versionId ?: ui.manifest?.latestRelease ?: "Choose a version"}", color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ProfileChooser(selected, ui.profiles.profiles, ui.manifest?.latestRelease, profileMenu, { profileMenu = it }, vm, onTab)
                PlayAction(actionText, canPlay, !isBusy && !ui.launching && !ui.gameRunning, state, { if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction() })
                if (currentJob != null) InstallProgress(currentJob.fraction, "${currentJob.stage} · ${currentJob.finishedFiles}/${currentJob.totalFiles}")
                ui.failure?.let { failure -> Text(failure.title + "  ·  Details", color = Color(0xFFFFA5A0), modifier = Modifier.clickable { showFailure = true }.padding(5.dp)) }
                QuickActions(onTab, onOpen)
                BottomStatus(selected, state, vm.recommendedHeapMb(), selected?.memoryMb ?: settings.defaultMemoryMb ?: vm.recommendedHeapMb(), onOpen, onTab)
                NewsSection(recentNews, feedLoading, feedError, refreshFeed, { newsDialog = it })
            }
        }
        newsDialog?.let { NoteDialog(it, { newsDialog = null }, { openOfficialArticle(context, it) }) }
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F13))) {
        Image(painterResource(R.drawable.launcher_reference_hero), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x65000000), Color(0x26000000), Color(0x95000000)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x16000000), Color(0x12000000), Color(0x75000000)))))
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                AccountButton(ui.activeAccount?.username ?: "Add account", ui.activeAccount?.type == AccountType.OFFLINE) { onOpen(Overlay.Accounts) }
                Spacer(Modifier.width(6.dp))
                IconSquare("head", "Accounts") { onOpen(Overlay.Accounts) }
                Spacer(Modifier.width(6.dp))
                IconSquare("settings", "Settings") { onTab(Tab.SETTINGS) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1.65f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Top) {
                        Text("MINECRAFT", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 35.sp, lineHeight = 38.sp, maxLines = 1,
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(4f, 5f), 3f)))
                        Text("JAVA EDITION", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(start = 44.dp), style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(3f, 4f), 3f)))
                    }
                    Box(Modifier.fillMaxWidth(0.76f)) {
                        ProfileChooser(selected, ui.profiles.profiles, ui.manifest?.latestRelease, profileMenu, { profileMenu = it }, vm, onTab)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayAction(actionText, canPlay, !isBusy && !ui.launching && !ui.gameRunning, state,
                            { if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction() }, Modifier.weight(1f).height(55.dp))
                        IconSquare("list", "Choose installation") { profileMenu = true }
                    }
                    if (currentJob != null) InstallProgress(currentJob.fraction, "${currentJob.stage} · ${currentJob.finishedFiles}/${currentJob.totalFiles}")
                    ui.failure?.let { failure ->
                        Text(failure.title + "  ·  Details", color = Color(0xFFFFA5A0), fontSize = 10.sp, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xD91A2026)).clickable { showFailure = true }.padding(5.dp))
                    }
                    QuickActions(onTab, onOpen)
                }
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xED0B1016))
                    .border(1.dp, Color(0xFF303943), RoundedCornerShape(10.dp)).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Latest News", color = Color.White, fontFamily = Monocraft, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("View All  →", color = Color.White, fontSize = 10.sp, modifier = Modifier.clickable { openMinecraftNews(context) }.padding(4.dp))
                    }
                    if (feedLoading && recentNews.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = launcherGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else if (recentNews.isEmpty()) {
                        NewsEmpty(feedError, refreshFeed)
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 2.dp)) {
                            items(recentNews.take(5)) { note -> NewsCard(note, { newsDialog = note }) }
                        }
                    }
                }
            }
            BottomStatus(selected, state, vm.recommendedHeapMb(), selected?.memoryMb ?: settings.defaultMemoryMb ?: vm.recommendedHeapMb(), onOpen, onTab)
        }
    }

    newsDialog?.let { NoteDialog(it, { newsDialog = null }, { openOfficialArticle(context, it) }) }
}

@Composable
private fun AccountButton(name: String, offline: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xD91A2026)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PixelIcon("head", size = 24.dp)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.widthIn(max = 120.dp)) {
            Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (offline) Text("OFFLINE", color = Color(0xFFFFD477), fontSize = 8.sp, lineHeight = 9.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text("⌄", color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun IconSquare(icon: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xD91A2026)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        PixelIcon(icon, size = 22.dp, tint = Color.White, contentDescription = description)
    }
}

@Composable
private fun ProfileChooser(selected: Profile?, profiles: List<Profile>, latestRelease: String?, expanded: Boolean, setExpanded: (Boolean) -> Unit, vm: LauncherViewModel, onTab: (Tab) -> Unit) {
    val version = selected?.versionId
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xE9161B21))
            .clickable { if (profiles.isEmpty()) onTab(Tab.PROFILES) else setExpanded(true) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("grass", size = 26.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(if (version != null && version == latestRelease) "Latest Release ($version)" else version?.let { "Minecraft $it" } ?: "Choose a Minecraft version",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selected?.let { "${it.name} · ${profileSubtitle(it)}" } ?: "Create an installation to get started",
                    color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("⌄", color = Color.White, fontSize = 20.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Column {
                    Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Minecraft ${profile.versionId}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }, onClick = { vm.select(profile.id); setExpanded(false) })
            }
            DropdownMenuItem(text = { Text("Manage installations") }, onClick = { setExpanded(false); onTab(Tab.PROFILES) })
        }
    }
}

@Composable
private fun PlayAction(text: String, canPlay: Boolean, enabled: Boolean, state: PlayState, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val danger = state == PlayState.REPAIR_REQUIRED
    val bg = if (danger) Color(0xFFB93D32) else launcherGreen
    val shape = RoundedCornerShape(9.dp)
    Row(modifier.clip(shape).background(Brush.verticalGradient(listOf(bg, if (danger) Color(0xFF8F2A25) else launcherGreenShade)))
        .drawBehind {
            val cell = 14.dp.toPx()
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = 0
                while (x < size.width) {
                    if ((row + col) % 3 == 0) drawRect(Color.White.copy(alpha = 0.07f), Offset(x, y), Size(cell * 0.92f, cell * 0.92f))
                    x += cell
                    col++
                }
                y += cell
                row++
            }
        }
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        PixelIcon(if (canPlay) "play" else "download", size = 34.dp, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickActions(onTab: (Tab) -> Unit, onOpen: (Overlay) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("grass", "Installations", { onTab(Tab.PROFILES) }, Modifier.weight(1f))
        QuickAction("pickaxe", "Browse Mods", { onTab(Tab.BROWSE) }, Modifier.weight(1f))
        Box(Modifier.weight(1f)) {
            QuickAction("more", "More Options", { expanded = true }, Modifier.fillMaxWidth())
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Mods") }, onClick = { expanded = false; onOpen(Overlay.Content(ContentSection.MODS)) })
                DropdownMenuItem(text = { Text("Resource packs") }, onClick = { expanded = false; onOpen(Overlay.Content(ContentSection.RESOURCE_PACKS)) })
                DropdownMenuItem(text = { Text("Worlds") }, onClick = { expanded = false; onOpen(Overlay.Content(ContentSection.WORLDS)) })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { expanded = false; onTab(Tab.SETTINGS) })
            }
        }
    }
}

@Composable
private fun QuickAction(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 43.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xDD171D24)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        PixelIcon(icon, size = 18.dp, tint = Color.White.copy(alpha = 0.94f))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BottomStatus(profile: Profile?, state: PlayState, recommendedRam: Int, configuredRam: Int, onOpen: (Overlay) -> Unit, onTab: (Tab) -> Unit) {
    val canPlay = state == PlayState.READY
    val gameDir = profile?.let { runCatching { it.gameDir(Services.dirs).toString() }.getOrNull() } ?: "Game directory not set"
    Row(Modifier.fillMaxWidth().heightIn(min = 45.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1.15f).clip(RoundedCornerShape(8.dp)).background(Color(0xE91A2026)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (canPlay) Color(0xFF00E881) else Color(0xFFFFC451)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (canPlay) "Ready to play" else if (profile == null) "No installation selected" else state.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile?.let { "Minecraft ${it.versionId} · ${profileSubtitle(it)}" } ?: "Choose a version to get started", color = Color.White.copy(alpha = 0.67f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.weight(1.25f).clip(RoundedCornerShape(8.dp)).background(Color(0xE91A2026)).clickable { if (profile != null) onOpen(Overlay.EditProfile(profile.id)) else onTab(Tab.PROFILES) }.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("folder", size = 20.dp, tint = Color.White)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("Game Directory", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(gameDir, color = Color.White.copy(alpha = 0.68f), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Color.White, fontSize = 24.sp, lineHeight = 18.sp)
        }
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xE91A2026)).clickable { onTab(Tab.SETTINGS) }.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("info", size = 20.dp, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Column {
                Text("RAM ALLOCATION", color = Color.White, fontSize = 8.sp)
                Text("${if (configuredRam > 0) configuredRam else recommendedRam} MB", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun InstallProgress(fraction: Float?, label: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xB9000000)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        AppProgress(fraction)
    }
}

@Composable
private fun NewsSection(notes: List<OfficialPatchNote>, loading: Boolean, error: Boolean, refresh: () -> Unit, onSelect: (OfficialPatchNote) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Latest News", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { openMinecraftNews(context) }) { Text("View all  →", color = Color.White, fontSize = 10.sp) }
    }
    when {
        loading && notes.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = launcherGreen)
        notes.isEmpty() -> NewsEmpty(error, refresh)
        else -> notes.take(5).forEach { NewsCard(it, { onSelect(it) }) }
    }
}

@Composable
private fun NewsCard(note: OfficialPatchNote, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xCB10151A)).clickable(onClick = onClick).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(92.dp).height(76.dp).clip(RoundedCornerShape(6.dp))) { RemoteCover(note.imageUrl, Modifier.fillMaxSize(), "grass") }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note.type.replace('-', ' ').replaceFirstChar { it.uppercase() }, color = Color.White.copy(alpha = 0.83f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(note.shortText.ifBlank { "Open the official Minecraft Java patch notes." }, color = Color.White.copy(alpha = 0.68f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(formatNewsDate(note.date), color = Color.White.copy(alpha = 0.54f), fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("↗", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NewsEmpty(error: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (error) "Official news is unavailable right now." else "Loading Minecraft news…", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
        if (error) TextButton(onClick = onRefresh) { Text("Try again", color = launcherGreen) }
    }
}

@Composable
private fun NoteDialog(note: OfficialPatchNote, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF171D24), titleContentColor = Color.White, textContentColor = Color.White,
        title = { Text(note.title) }, text = { Column {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(8.dp))) { RemoteCover(note.imageUrl, Modifier.fillMaxSize()) }
            Text(note.shortText, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Text("${note.type.replaceFirstChar { it.uppercase() }} · ${formatNewsDate(note.date)}", color = Color.White.copy(alpha = 0.56f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        } }, confirmButton = { TextButton(onClick = onOpen) { Text("Read on Minecraft.net", color = launcherGreen) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White.copy(alpha = 0.7f)) } })
}

private fun formatNewsDate(value: String): String = runCatching {
    val instant = Instant.parse(value)
    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date.from(instant))
}.getOrDefault(value.take(10))

private fun openOfficialArticle(context: android.content.Context, note: OfficialPatchNote) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(note.articleUrl))) }
}

private fun openMinecraftNews(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/en-us/articles"))) }
}
