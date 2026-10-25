package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.*
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.launch.FixAction
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.profile.RunOutcome

@Composable
fun HomeScreen(vm: LauncherViewModel, onOpen: (Overlay) -> Unit, onTab: (Tab) -> Unit) {
    val p = LocalPalette.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state = vm.playState(ui, jobs)
    val profile = ui.selected
    var showFailure by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CS Launcher Pro", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Minecraft: Java Edition", color = p.textMuted, fontSize = 13.sp)
            }
            val acc = ui.activeAccount
            Surface(onClick = { onOpen(Overlay.Accounts) }, shape = RoundedCornerShape(50), color = p.secondarySurface) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountCircle, null, tint = p.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(acc?.username ?: "Add account", color = p.text, fontSize = 14.sp)
                    if (acc?.type == AccountType.OFFLINE) { Spacer(Modifier.width(6.dp)); Text("offline", color = p.warning, fontSize = 11.sp) }
                }
            }
        }

        // ---- profile card ----
        CsCard(Modifier.fillMaxWidth()) {
            if (profile == null) {
                Text("No profile yet", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Create a profile to choose a Minecraft version.", color = p.textSecondary)
                Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = { onTab(Tab.PROFILES) }) { Text("Create profile", color = p.text) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(profile.versionId, profile.loader?.let { "${it.type.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.version}" }).joinToString(" · "), color = p.textSecondary)
                    }
                    IconButton(onClick = { onOpen(Overlay.EditProfile(profile.id)) }) { Icon(Icons.Filled.Edit, "Edit profile", tint = p.textSecondary) }
                }
                Spacer(Modifier.height(8.dp))
                FlowRowCompat {
                    Chip("Renderer: ${profile.rendererMemo?.rendererId?.let { "$it (remembered)" } ?: profile.rendererId}")
                    Chip("Memory: ${profile.memoryMb?.let { "$it MB" } ?: "auto ${vm.recommendedHeapMb()} MB"}")
                    if (profile.renderScale < 1f) Chip("Scale ${(profile.renderScale * 100).toInt()}%")
                }
                profile.lastRun?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    val (txt, col) = when (r.outcome) {
                        RunOutcome.SUCCESS -> "Last session ended normally" to p.success
                        RunOutcome.CRASHED -> "Last session crashed" to p.danger
                        RunOutcome.FAILED_TO_START -> "Last launch failed${r.stage?.let { " at $it" } ?: ""}" to p.danger
                        RunOutcome.KILLED -> "Last session was stopped" to p.warning
                    }
                    Text("$txt · ${relativeTime(r.atMs)}", color = col, fontSize = 13.sp)
                }
            }
        }

        // ---- play button ----
        Spacer(Modifier.height(16.dp))
        val job = profile?.let { jobs[it.effectiveVersionId] }?.takeIf { it.active }
        val busy = state in setOf(PlayState.DOWNLOADING, PlayState.VERIFYING, PlayState.UPDATING, PlayState.PREPARING, PlayState.LAUNCHING, PlayState.PLAYING)
        Button(onClick = { if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction() }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state == PlayState.LAUNCH_FAILED || state == PlayState.REPAIR_REQUIRED) p.danger.copy(alpha = 0.85f) else p.accent,
                contentColor = p.background, disabledContainerColor = p.selected, disabledContentColor = p.text)) {
            val label = when (state) {
                PlayState.DOWNLOADING, PlayState.UPDATING -> "${state.label} ${job?.fraction?.let { "${(it * 100).toInt()}%" } ?: ""}"
                PlayState.PREPARING -> "Preparing · ${ui.launch?.state?.name?.lowercase()?.replace('_', ' ') ?: ""}"
                PlayState.LAUNCH_FAILED -> "Launch Failed · Retry"
                else -> state.label
            }
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (job != null) {
            Spacer(Modifier.height(8.dp))
            job.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.secondarySurface) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.secondarySurface)
            Text("${job.stage} · ${job.finishedFiles}/${job.totalFiles} files · ${formatBytes(job.bytes)}", color = p.textSecondary, fontSize = 13.sp)
        }
        ui.failure?.let { f ->
            Spacer(Modifier.height(8.dp))
            CsCard(Modifier.fillMaxWidth().clickable { showFailure = true }) {
                Text(f.title, color = p.danger, fontWeight = FontWeight.Medium)
                Text(f.diagnoses.firstOrNull()?.explanation ?: f.issues.firstOrNull()?.message ?: "See details", color = p.textSecondary, fontSize = 13.sp, maxLines = 3)
                Text("Tap for details and fixes", color = p.accent, fontSize = 13.sp)
            }
        }

        // ---- quick actions ----
        SectionTitle("Quick actions")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Icons.Filled.Build, "Mods", Modifier.weight(1f)) { onOpen(Overlay.Content(ContentSection.MODS)) }
            QuickAction(Icons.Filled.Star, "Packs", Modifier.weight(1f)) { onOpen(Overlay.Content(ContentSection.RESOURCE_PACKS)) }
            QuickAction(Icons.Filled.Place, "Worlds", Modifier.weight(1f)) { onOpen(Overlay.Content(ContentSection.WORLDS)) }
            QuickAction(Icons.Filled.Info, "Logs", Modifier.weight(1f)) { onOpen(Overlay.Logs) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Icons.Filled.Create, "Controls", Modifier.weight(1f)) { ControlEditorActivity.open(vm.getApplication(), profile?.controlLayoutId ?: "default-touch") }
            QuickAction(Icons.Filled.Refresh, "Verify", Modifier.weight(1f)) { profile?.let { vm.repair(it.effectiveVersionId) } }
            QuickAction(Icons.Filled.Face, "Recordings", Modifier.weight(1f)) { onOpen(Overlay.Content(ContentSection.RECORDINGS)) }
            QuickAction(Icons.Filled.Share, "Screens", Modifier.weight(1f)) { onOpen(Overlay.Content(ContentSection.SCREENSHOTS)) }
        }

        // ---- performance summary ----
        SectionTitle("Performance")
        CsCard(Modifier.fillMaxWidth()) {
            val f = vm.facts
            InfoRow("Preset", settings.performancePreset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
            InfoRow("Device memory", "${f.totalRamMb} MB${if (f.lowRamDevice) " (low-RAM device)" else ""}")
            InfoRow("Game memory", "${profile?.memoryMb ?: vm.recommendedHeapMb()} MB (safe max ${vm.maxHeapMb()} MB)")
            InfoRow("Graphics", "OpenGL ES ${vm.device.glesVersion / 10}.${vm.device.glesVersion % 10} · Vulkan ${vm.device.vulkanApi ?: "not available"}")
            PerformanceAdvisor.recommendations(f, profile?.memoryMb ?: vm.recommendedHeapMb(), if (profile?.lastRun?.outcome == RunOutcome.CRASHED) 1 else 0, 0).take(2).forEach {
                Text("• ${it.title}: ${it.detail}", color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ---- recent activity ----
        SectionTitle("Recent activity")
        if (!settings.recentActivityEnabled) Text("Recent activity is turned off (Settings › Privacy).", color = p.textMuted, fontSize = 13.sp)
        else if (ui.activity.isEmpty()) Text("Nothing yet.", color = p.textMuted, fontSize = 13.sp)
        else CsCard(Modifier.fillMaxWidth()) {
            ui.activity.take(6).forEach { a ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) { Text(a.title, color = p.text, fontSize = 14.sp); Text(a.detail, color = p.textSecondary, fontSize = 12.sp, maxLines = 1) }
                    Text(relativeTime(a.atMs), color = p.textMuted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showFailure) ui.failure?.let { f ->
        AlertDialog(onDismissRequest = { showFailure = false }, containerColor = p.elevated, titleContentColor = p.text, textContentColor = p.textSecondary,
            title = { Text(f.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    f.issues.forEach { i -> Text("• [${i.stage.name.lowercase()}] ${i.message}", modifier = Modifier.padding(bottom = 6.dp)) }
                    f.diagnoses.forEach { d ->
                        Text("${d.kind.title} (${(d.confidence * 100).toInt()}%)", color = p.text, fontWeight = FontWeight.Medium)
                        Text(d.explanation, fontSize = 13.sp)
                        d.evidence.take(3).forEach { Text("  › $it", fontSize = 11.sp, color = p.textMuted, maxLines = 2) }
                        d.repairs.forEach { r -> Text("Suggested: ${r.description}", fontSize = 13.sp, color = p.accent) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                val fix = f.issues.firstOrNull { it.fix != FixAction.NONE }?.fix
                TextButton(onClick = {
                    showFailure = false
                    when (fix) {
                        FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> ui.selected?.let { vm.repair(it.effectiveVersionId) }
                        FixAction.SIGN_IN -> onOpen(Overlay.Accounts)
                        FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT -> onTab(Tab.SETTINGS)
                        FixAction.CHOOSE_RENDERER, FixAction.EDIT_PROFILE, FixAction.INSTALL_LOADER, FixAction.FIX_GAME_DIRECTORY -> ui.selected?.let { onOpen(Overlay.EditProfile(it.id)) }
                        FixAction.FREE_STORAGE -> onTab(Tab.SETTINGS)
                        else -> onOpen(Overlay.Logs)
                    }
                }) { Text(when (fix) { null, FixAction.NONE -> "Open logs"; FixAction.SIGN_IN -> "Sign in"; FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT -> "Open runtimes & components"; FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> "Repair"; else -> "Edit profile" }, color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { showFailure = false; vm.dismissFailure() }) { Text("Dismiss", color = p.textSecondary) } })
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    CsCard(modifier.clickable(onClick = onClick)) {
        Icon(icon, null, tint = p.accent, modifier = Modifier.size(22.dp).align(Alignment.CenterHorizontally))
        Text(label, color = p.text, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), maxLines = 1)
    }
}
