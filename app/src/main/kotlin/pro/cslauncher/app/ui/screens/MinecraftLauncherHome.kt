package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.R
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.PlayState
import pro.cslauncher.app.ui.Tab
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.profile.RunOutcome

/** Quiet, landscape-first launcher home: one large play destination and an installation list. */
@Composable
fun MinecraftLauncherHome(vm: LauncherViewModel, onOpen: (Overlay) -> Unit, onTab: (Tab) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val selected = ui.selected
    val state = vm.playState(ui, jobs)
    val currentJob = selected?.let { jobs["task:profile-${it.id}"]?.takeIf { j -> j.active } }
    val busy = state in setOf(PlayState.DOWNLOADING, PlayState.VERIFYING, PlayState.UPDATING, PlayState.PREPARING, PlayState.LAUNCHING, PlayState.PLAYING)
    val canPlay = selected != null && selected.effectiveVersionId in ui.installed && selected.installationState !in setOf("pending", "installing", "incomplete")
    val actionText = when (state) {
        PlayState.NO_PROFILE -> "Create installation"
        PlayState.INSTALL -> "Install"
        PlayState.LAUNCH_FAILED -> "Try again"
        PlayState.REPAIR_REQUIRED -> "Repair installation"
        PlayState.DOWNLOADING, PlayState.UPDATING -> state.label + currentJob?.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        PlayState.PREPARING, PlayState.VERIFYING, PlayState.LAUNCHING -> state.label + "…"
        else -> "Play"
    }
    val account = ui.activeAccount

    if (LocalConfiguration.current.screenWidthDp < 600) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MINECRAFT: JAVA EDITION", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("CS Launcher · Android", color = p.textMuted, fontSize = 10.sp)
                }
                TextButton(onClick = { MinecraftUiClickSound.play(context); onOpen(Overlay.Accounts) }) {
                    Text(account?.username ?: "Add account", color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Card(Modifier.fillMaxWidth().height(176.dp), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = p.card)) {
                Box(Modifier.fillMaxSize()) {
                    Image(painterResource(R.drawable.launcher_hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 30f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(15.dp)) {
                        Text(selected?.name ?: "No installation selected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(selected?.let(::profileSubtitle) ?: "Create an installation to get started", color = Color.White.copy(alpha = 0.86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(actionText, { if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction() }, Modifier.weight(1f).heightIn(min = 48.dp),
                    style = if (state == PlayState.REPAIR_REQUIRED || state == PlayState.LAUNCH_FAILED) PxStyle.DANGER else PxStyle.PRIMARY,
                    enabled = !busy && !ui.launching && !ui.gameRunning, icon = if (canPlay) "play" else "download")
                if (selected != null) AppButton("Edit", { onOpen(Overlay.EditProfile(selected.id)) }, Modifier.heightIn(min = 48.dp))
            }
            if (currentJob != null) {
                Text("${currentJob.stage} · ${currentJob.finishedFiles}/${currentJob.totalFiles}", color = p.textSecondary, fontSize = 11.sp)
                AppProgress(currentJob.fraction)
            } else if (ui.failure != null) {
                TextButton(onClick = { MinecraftUiClickSound.play(context); onOpen(Overlay.Logs) }) { Text("${ui.failure?.title ?: "Launch issue"} · View logs", color = p.danger) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Installations", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AppButton("＋ New", { onTab(Tab.PROFILES) }, Modifier.heightIn(min = 36.dp))
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = p.card)) {
                if (ui.profiles.profiles.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No installations yet", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Choose a Minecraft version and loader to start.", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
                        AppButton("Create installation", { onTab(Tab.PROFILES) }, style = PxStyle.PRIMARY)
                    }
                } else {
                    ui.profiles.profiles.forEach { profile ->
                        val active = profile.id == selected?.id
                        Row(Modifier.fillMaxWidth().background(if (active) p.selected else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { MinecraftUiClickSound.play(context); vm.select(profile.id) }
                            .padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProfileIconImage(profile, 28.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, color = p.text, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(profileSubtitle(profile), color = p.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (active) Text("Selected", color = p.accent, fontSize = 9.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (account?.type == AccountType.OFFLINE) Badge("OFFLINE", p.warning)
                Text(if (account == null) "Sign in to play online" else "Signed in as ${account.username}", color = p.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { MinecraftUiClickSound.play(context); onTab(Tab.SETTINGS) }) { Text("Settings", color = p.textSecondary, fontSize = 10.sp) }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MINECRAFT: JAVA EDITION", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("CS Launcher · Android", color = p.textMuted, fontSize = 10.sp)
                }
                TextButton(onClick = { MinecraftUiClickSound.play(context); onOpen(Overlay.Accounts) }) {
                    Text(account?.username ?: "Add account", color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = p.card)) {
                Box(Modifier.fillMaxSize()) {
                    Image(painterResource(R.drawable.launcher_hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 40f)))
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(selected?.name ?: "No installation selected", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(selected?.let(::profileSubtitle) ?: "Create an installation to get started", color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            selected?.lastRun?.let { last ->
                                val (label, tone) = when (last.outcome) {
                                    RunOutcome.SUCCESS -> "Last session ended normally" to Color(0xFFB8E9A5)
                                    RunOutcome.CRASHED, RunOutcome.FAILED_TO_START -> "Last launch needs attention" to Color(0xFFFFB5B5)
                                    RunOutcome.KILLED -> "Last session stopped" to Color(0xFFFFD994)
                                }
                                Text(label, color = tone, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                        selected?.let { PixelIcon(profileIcon(it), size = 36.dp, tint = Color.White) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(actionText, {
                    if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction()
                }, Modifier.weight(1f).heightIn(min = 48.dp), style = if (state == PlayState.REPAIR_REQUIRED || state == PlayState.LAUNCH_FAILED) PxStyle.DANGER else PxStyle.PRIMARY,
                    enabled = !busy && !ui.launching && !ui.gameRunning, icon = if (canPlay) "play" else "download")
                if (selected != null) AppButton("Edit", { onOpen(Overlay.EditProfile(selected.id)) }, Modifier.heightIn(min = 48.dp))
            }
            if (currentJob != null) {
                Text("${currentJob.stage} · ${currentJob.finishedFiles}/${currentJob.totalFiles}", color = p.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppProgress(currentJob.fraction)
            } else if (ui.failure != null) {
                TextButton(onClick = { MinecraftUiClickSound.play(context); onOpen(Overlay.Logs) }) {
                    Text("${ui.failure?.title ?: "Launch issue"} · View logs", color = p.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Column(Modifier.weight(0.9f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Installations", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AppButton("＋ New", { onTab(Tab.PROFILES) }, heightModifier())
            }
            Card(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = p.card)) {
                if (ui.profiles.profiles.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No installations yet", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Choose a Minecraft version and loader to start.", color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                        AppButton("Create installation", { onTab(Tab.PROFILES) }, style = PxStyle.PRIMARY)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(ui.profiles.profiles, key = { it.id }) { profile ->
                            val active = profile.id == selected?.id
                            val job = jobs["task:profile-${profile.id}"]?.takeIf { it.active }
                            Row(Modifier.fillMaxWidth().background(if (active) p.selected else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { MinecraftUiClickSound.play(context); vm.select(profile.id) }
                                .padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProfileIconImage(profile, 26.dp)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, color = p.text, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(profileSubtitle(profile), color = p.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (job != null) Text("${job.stage} · ${job.finishedFiles}/${job.totalFiles}", color = p.accent, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (active) Text("Selected", color = p.accent, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (account?.type == AccountType.OFFLINE) Badge("OFFLINE", p.warning)
                Text(if (account == null) "Sign in to play online" else "Signed in as ${account.username}", color = p.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { MinecraftUiClickSound.play(context); onTab(Tab.SETTINGS) }) { Text("Settings", color = p.textSecondary, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun heightModifier(): Modifier = Modifier.heightIn(min = 38.dp)
