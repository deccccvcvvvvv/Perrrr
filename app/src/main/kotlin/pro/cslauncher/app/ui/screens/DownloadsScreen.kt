package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.Services
import pro.cslauncher.app.install.InstallJob
import pro.cslauncher.app.install.InstallManager
import pro.cslauncher.app.install.JobKind
import pro.cslauncher.app.install.JobStatus
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*

private fun InstallJob.icon() = when {
    kind == JobKind.VERIFY_REPAIR -> "shield"; versionId.startsWith("task:modpack") -> "chest"; versionId.startsWith("task:optifine") -> "bolt"; else -> "download"
}
private fun InstallJob.heading() = when (kind) { JobKind.INSTALL -> "Install $title"; JobKind.VERIFY_REPAIR -> "Verify & repair $title"; JobKind.TASK -> title }

@Composable
fun DownloadsScreen(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val batches by Services.downloads.activeBatches.collectAsStateWithLifecycle()
    val active = jobs.values.filter { it.active }.sortedByDescending { it.startedAtMs }
    val finished = jobs.values.filter { !it.active }.sortedByDescending { it.endedAtMs ?: it.startedAtMs }
    val other = batches.filterKeys { k -> jobs.keys.none { k.contains(it) } }.values.toList()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads") {
            if (active.isNotEmpty() || finished.isNotEmpty())
                Text(listOfNotNull(active.size.takeIf { it > 0 }?.let { "$it running" }, finished.size.takeIf { it > 0 }?.let { "$it finished" }).joinToString(" · "),
                    color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            if (finished.isNotEmpty()) HeaderAction("Clear", "trash", { InstallManager.clearFinished() })
        }
        if (jobs.isEmpty() && other.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MinecartProgress(0f, DlPhase.IDLE, Modifier.widthIn(max = 260.dp))
                Spacer(Modifier.height(12.dp))
                PixelTitle("Nothing downloading", fontSize = 14.sp)
                Text("Installs, repairs, modpacks and OptiFine show their progress here.", color = p.textMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
            items(active, key = { it.versionId }, span = { GridItemSpan(maxLineSpan) }) { j -> ActiveJobCard(j, vm) }
            items(other, key = { "b:" + it.batchName }, span = { GridItemSpan(maxLineSpan) }) { b ->
                PixelPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
                    Text(b.batchName, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    MinecartProgress(if (b.totalFiles > 0) b.finishedFiles.toFloat() / b.totalFiles else null, DlPhase.ACTIVE, height = 28.dp)
                    Text("${b.finishedFiles}/${b.totalFiles} files · ${formatBytes(b.downloadedBytes)}", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (finished.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Finished", Modifier.padding(start = 2.dp)) }
            itemsIndexed(finished, key = { _, it -> it.versionId }) { i, j -> FinishedJobCard(j, vm, Modifier.staggerIn(i)) }
        }
    }
}

@Composable
private fun ActiveJobCard(j: InstallJob, vm: LauncherViewModel) {
    val p = LocalPalette.current
    PixelPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSlot(size = 36.dp) { PixelIcon(j.icon(), size = 20.dp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(j.heading(), color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = PixelTextShadow))
                Text(if (j.status == JobStatus.QUEUED) "Waiting to start…" else j.stage, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(j.fraction?.let { "${(it * 100).toInt()}%" } ?: "…", color = Color(0xFF9FE07F), fontFamily = Monocraft, fontSize = 16.sp,
                style = TextStyle(shadow = PixelTextShadow), modifier = Modifier.padding(horizontal = 8.dp))
            PixelButton("Cancel", { vm.cancelJob(j.versionId) }, style = PxStyle.DANGER, icon = "stop", height = 32.dp, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        JobProgress(j)
        if (j.message.isNotBlank()) Text(j.message, color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun FinishedJobCard(j: InstallJob, vm: LauncherViewModel, modifier: Modifier) {
    val p = LocalPalette.current
    val (label, color) = when (j.status) {
        JobStatus.DONE -> "DONE" to p.success; JobStatus.FAILED -> "FAILED" to p.danger; JobStatus.CANCELLED -> "STOPPED" to p.warning
        JobStatus.QUEUED -> "QUEUED" to p.textSecondary; JobStatus.RUNNING -> "RUNNING" to Color(0xFF8FD3FF)
    }
    PixelPanel(modifier.fillMaxWidth(), padding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSlot(size = 34.dp) { PixelIcon(if (j.status == JobStatus.DONE) "check" else if (j.status == JobStatus.FAILED) "warning" else j.icon(), size = 18.dp, tint = if (j.status == JobStatus.DONE) Color.White else color) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(j.heading(), color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp)); Badge(label, color)
                }
                val detail = j.message.ifBlank { listOfNotNull("${j.finishedFiles}/${j.totalFiles} files".takeIf { j.totalFiles > 0 }, j.bytes.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · ") }
                Text(detail.ifBlank { j.stage }, color = if (j.status == JobStatus.FAILED) p.danger.copy(alpha = 0.9f) else p.textMuted, fontSize = 10.sp, lineHeight = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            // Instance installs are TASK jobs keyed "task:profile-<uuid>": they can be retried too,
            // resuming from the partial files the downloader kept. Other TASK jobs (modpacks,
            // OptiFine) need their original source file, so no misleading retry is offered here.
            val instanceId = j.versionId.takeIf { it.startsWith("task:profile-") }?.removePrefix("task:profile-")
            if (j.status == JobStatus.FAILED || j.status == JobStatus.CANCELLED) {
                if (j.kind != JobKind.TASK || instanceId != null) {
                    Spacer(Modifier.width(6.dp))
                    PixelIconButton("refresh", "Retry (resumes)", {
                        when {
                            instanceId != null -> vm.installProfile(instanceId)
                            j.kind == JobKind.INSTALL -> vm.install(j.versionId)
                            else -> vm.repair(j.versionId)
                        }
                    }, size = 32.dp)
                }
            }
        }
    }
}
