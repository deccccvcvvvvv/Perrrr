package pro.cslauncher.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.*
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.launch.FixAction
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.profile.RunOutcome

private val SPLASHES = listOf(
    "Pixel perfect!", "Now in landscape!", "OptiFine ready!", "Modpacks in one tap!", "Made for touch!", "Java on Android!",
    "100% blocky!", "Shaders included*", "Fabric & Quilt!", "Craft anywhere!", "Also try a modpack!", "SDL3 inside!",
)

@Composable
fun HomeScreen(vm: LauncherViewModel, onOpen: (Overlay) -> Unit, onTab: (Tab) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state = vm.playState(ui, jobs)
    val profile = ui.selected
    var showFailure by remember { mutableStateOf(false) }
    var pickProfile by remember { mutableStateOf(false) }
    val splash = remember { SPLASHES.random() }

    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ------------------------------------------------ left: title, profile, play
        Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            ui.profiles.profiles.forEach { instance ->
                val job = jobs["task:profile-${instance.id}"]
                PixelPanel(Modifier.fillMaxWidth().padding(bottom = 5.dp), selected = instance.id == profile?.id, padding = 6.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon(profileIcon(instance), size = 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(instance.name, color = p.text, fontSize = 12.sp)
                            Text(profileSubtitle(instance), color = p.textMuted, fontSize = 10.sp)
                            Text(job?.takeIf { it.active }?.let { "${it.stage} · ${it.finishedFiles}/${it.totalFiles}" }
                                ?: if (instance.installationState == "existing") { if (instance.effectiveVersionId in ui.installed) "Ready" else "Not installed" } else instance.installationState,
                                color = p.textSecondary, fontSize = 10.sp)
                        }
                        PixelButton(if (instance.effectiveVersionId in ui.installed && instance.installationState !in listOf("pending", "installing", "incomplete")) "Play" else "Install", { vm.playProfile(instance.id) }, height = 30.dp, fontSize = 10.sp, enabled = job?.active != true && !ui.launching && !ui.gameRunning)
                        PixelIconButton("edit", "Edit instance", { onOpen(Overlay.EditProfile(instance.id)) }, size = 30.dp)
                        PixelIconButton("more", "Instance actions", { onTab(Tab.PROFILES) }, size = 30.dp)
                    }
                    if (job?.active == true) PixelProgress(job.fraction)
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
                Column {
                    PixelTitle("CS LAUNCHER", fontSize = 22.sp)
                    Text("PRO · Java Edition", color = Color(0xFFBFC0C2), fontFamily = Monocraft, fontSize = 10.sp, lineHeight = 12.sp, style = TextStyle(shadow = PixelTextShadow))
                }
                SplashText(splash, Modifier.align(Alignment.BottomEnd).padding(end = 2.dp), fontSize = 11.sp)
            }

            PixelPanel(Modifier.fillMaxWidth().staggerIn(0)) {
                if (profile == null) {
                    PixelTitle("No profile yet", fontSize = 14.sp)
                    Text("Create a profile to choose a Minecraft version.", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    PixelButton("Create profile", { onTab(Tab.PROFILES) }, icon = "plus", style = PxStyle.PRIMARY)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelSlot(size = 44.dp) { PixelIcon(profileIcon(profile), size = 30.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            PixelTitle(profile.name, fontSize = 14.sp)
                            Text(profileSubtitle(profile), color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PixelIconButton("list", "Switch profile", { pickProfile = true }, size = 34.dp)
                        Spacer(Modifier.width(6.dp))
                        PixelIconButton("edit", "Edit profile", { onOpen(Overlay.EditProfile(profile.id)) }, size = 34.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRowCompat {
                        Chip("Renderer: ${profile.rendererMemo?.rendererId?.let { "$it ✓" } ?: profile.rendererId}")
                        Chip("RAM ${profile.memoryMb?.let { "$it MB" } ?: "auto ${vm.recommendedHeapMb()} MB"}")
                        if (profile.renderScale < 1f) Chip("Scale ${(profile.renderScale * 100).toInt()}%")
                    }
                    profile.lastRun?.let { r ->
                        val (txt, col) = when (r.outcome) {
                            RunOutcome.SUCCESS -> "Last session ended normally" to p.success
                            RunOutcome.CRASHED -> "Last session crashed" to p.danger
                            RunOutcome.FAILED_TO_START -> "Last launch failed${r.stage?.let { " at $it" } ?: ""}" to p.danger
                            RunOutcome.KILLED -> "Last session was stopped" to p.warning
                        }
                        Text("$txt · ${relativeTime(r.atMs)}", color = col, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val job = profile?.let { jobs[it.effectiveVersionId] }?.takeIf { it.active }
            val busy = state in setOf(PlayState.DOWNLOADING, PlayState.VERIFYING, PlayState.UPDATING, PlayState.PREPARING, PlayState.LAUNCHING, PlayState.PLAYING)
            val label = when (state) {
                PlayState.DOWNLOADING, PlayState.UPDATING -> "${state.label} ${job?.fraction?.let { "${(it * 100).toInt()}%" } ?: ""}"
                PlayState.PREPARING -> "Preparing…"
                PlayState.LAUNCH_FAILED -> "Retry"
                else -> state.label
            }
            val playStyle = when (state) { PlayState.LAUNCH_FAILED, PlayState.REPAIR_REQUIRED -> PxStyle.DANGER; else -> PxStyle.PRIMARY }
            val playIcon = when (state) { PlayState.INSTALL -> "download"; PlayState.REPAIR_REQUIRED -> "refresh"; PlayState.NO_PROFILE -> "plus"; else -> "play" }
            PixelButton(label.uppercase(), { if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction() }, Modifier.fillMaxWidth().staggerIn(1),
                style = playStyle, enabled = !busy, icon = playIcon, height = 52.dp, fontSize = 17.sp)
            AnimatedVisibility(job != null || state == PlayState.PREPARING, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (job != null) {
                        Text(job.stage, color = Color.White, fontFamily = Monocraft, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp))
                        JobProgress(job, compact = true)
                    } else {
                        MinecartProgress(null, DlPhase.ACTIVE, height = 28.dp)
                        Text(ui.launch?.state?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Preparing",
                            color = p.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            ui.failure?.let { f ->
                Spacer(Modifier.height(8.dp))
                PixelPanel(Modifier.fillMaxWidth().clickable { showFailure = true }, padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon("warning", size = 18.dp); Spacer(Modifier.width(8.dp))
                        Text(f.title, color = p.danger, fontFamily = Monocraft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(f.diagnoses.firstOrNull()?.explanation ?: f.issues.firstOrNull()?.message ?: "See details", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    Text("Tap for details and fixes", color = p.accent, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ------------------------------------------------ right: account, quick actions, info
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            val acc = ui.activeAccount
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (acc?.type == AccountType.OFFLINE) { Badge("OFFLINE", p.warning); Spacer(Modifier.width(8.dp)) }
                PixelButton(acc?.username ?: "Add account", { onOpen(Overlay.Accounts) }, icon = if (acc == null) "user_add" else "head", height = 34.dp, fontSize = 11.sp)
            }
            SectionTitle("Quick actions")
            val actions = listOf<Triple<String, String, () -> Unit>>(
                Triple("pickaxe", "Mods") { onOpen(Overlay.Content(ContentSection.MODS)) },
                Triple("painting", "Packs") { onOpen(Overlay.Content(ContentSection.RESOURCE_PACKS)) },
                Triple("world", "Worlds") { onOpen(Overlay.Content(ContentSection.WORLDS)) },
                Triple("chest", "Modpacks") { onTab(Tab.BROWSE) },
                Triple("gamepad", "Controls") { ControlEditorActivity.open(vm.getApplication(), profile?.controlLayoutId ?: "default-touch") },
                Triple("bolt", "OptiFine") { onOpen(Overlay.OptiFine(profile?.id)) },
                Triple("record", "Videos") { onOpen(Overlay.Content(ContentSection.RECORDINGS)) },
                Triple("image", "Shots") { onOpen(Overlay.Content(ContentSection.SCREENSHOTS)) },
                Triple("shield", "Verify") { profile?.let { vm.repair(it.effectiveVersionId) } ?: Unit },
                Triple("book", "Logs") { onOpen(Overlay.Logs) },
            )
            actions.chunked(5).forEachIndexed { row, chunk ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEachIndexed { i, (icon, lbl, act) -> PixelTile(icon, lbl, act, Modifier.weight(1f).staggerIn(2 + row * 5 + i)) }
                }
            }

            SectionTitle("Performance")
            PixelPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
                val f = vm.facts
                InfoRow("Preset", settings.performancePreset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
                InfoRow("Device memory", "${f.totalRamMb} MB${if (f.lowRamDevice) " (low-RAM)" else ""}")
                InfoRow("Game memory", "${profile?.memoryMb ?: vm.recommendedHeapMb()} MB (max ${vm.maxHeapMb()} MB)")
                InfoRow("Graphics", "GLES ${vm.device.glesVersion.takeIf { it > 0 }?.let { "${it / 10}.${it % 10}" } ?: "?"} · Vulkan ${vm.device.vulkanApi ?: "n/a"}")
                PerformanceAdvisor.recommendations(f, profile?.memoryMb ?: vm.recommendedHeapMb(), if (profile?.lastRun?.outcome == RunOutcome.CRASHED) 1 else 0, 0).take(2).forEach {
                    Text("• ${it.title}: ${it.detail}", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }

            SectionTitle("Recent activity")
            when {
                !settings.recentActivityEnabled -> Text("Recent activity is turned off (Settings › Privacy).", color = p.textMuted, fontSize = 12.sp)
                ui.activity.isEmpty() -> Text("Nothing yet.", color = p.textMuted, fontSize = 12.sp)
                else -> PixelPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
                    ui.activity.take(6).forEach { a ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) { Text(a.title, color = p.text, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(a.detail, color = p.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Text(relativeTime(a.atMs), color = p.textMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickProfile) PixelDialog({ pickProfile = false }, "Choose profile", widthFraction = 0.62f, buttons = {
        PixelButton("Manage", { pickProfile = false; onTab(Tab.PROFILES) }, icon = "list")
        PixelButton("Close", { pickProfile = false })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.profiles.profiles.forEach { pr ->
                PixelButton("${pr.name}  ·  ${profileSubtitle(pr)}", { vm.select(pr.id); pickProfile = false }, Modifier.fillMaxWidth(), height = 36.dp, fontSize = 11.sp,
                    style = if (pr.id == profile?.id) PxStyle.SELECTED else PxStyle.NORMAL, icon = profileIcon(pr))
            }
        }
    }

    if (showFailure) ui.failure?.let { f ->
        val fix = f.issues.firstOrNull { it.fix != FixAction.NONE }?.fix
        PixelDialog({ showFailure = false }, f.title, buttons = {
            PixelButton("Dismiss", { showFailure = false; vm.dismissFailure() })
            PixelButton(when (fix) { null, FixAction.NONE -> "Open logs"; FixAction.SIGN_IN -> "Sign in"; FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT -> "Runtimes"; FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> "Repair"; else -> "Edit profile" }, {
                showFailure = false
                when (fix) {
                    FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> ui.selected?.let { vm.repair(it.effectiveVersionId) }
                    FixAction.SIGN_IN -> onOpen(Overlay.Accounts)
                    FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT, FixAction.FREE_STORAGE -> onTab(Tab.SETTINGS)
                    FixAction.CHOOSE_RENDERER, FixAction.EDIT_PROFILE, FixAction.INSTALL_LOADER, FixAction.FIX_GAME_DIRECTORY -> ui.selected?.let { onOpen(Overlay.EditProfile(it.id)) }
                    else -> onOpen(Overlay.Logs)
                }
            }, style = PxStyle.PRIMARY)
        }) {
            Column {
                f.issues.forEach { i -> Text("• [${i.stage.name.lowercase()}] ${i.message}", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                f.diagnoses.forEach { d ->
                    Text("${d.kind.title} (${(d.confidence * 100).toInt()}%)", color = p.text, fontFamily = Monocraft, fontSize = 13.sp)
                    Text(d.explanation, color = p.textSecondary, fontSize = 13.sp)
                    d.evidence.take(3).forEach { Text("  › $it", fontSize = 11.sp, color = p.textMuted, maxLines = 2) }
                    d.repairs.forEach { r -> Text("Suggested: ${r.description}", fontSize = 13.sp, color = p.accent) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
