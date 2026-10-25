package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import pro.cslauncher.app.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.Services
import pro.cslauncher.app.install.InstallManager
import pro.cslauncher.app.install.JobKind
import pro.cslauncher.app.install.JobStatus
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette

@Composable
fun DownloadsScreen(vm: LauncherViewModel) {
    val p = LocalPalette.current
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val batches by Services.downloads.activeBatches.collectAsStateWithLifecycle()
    val list = jobs.values.sortedByDescending { it.startedAtMs }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads") { if (list.any { !it.active }) PixelButton("Clear finished", { InstallManager.clearFinished() }, icon = "trash", height = 40.dp) }
        if (list.isEmpty() && batches.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelIcon("download", size = 56.dp)
            Text("No downloads. Installs, repairs, modpacks and OptiFine appear here.", color = p.textMuted, modifier = Modifier.padding(16.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(380.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(list, key = { _, it -> it.versionId }) { i, j ->
                PixelPanel(Modifier.fillMaxWidth().staggerIn(i), padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon(when { j.kind == JobKind.VERIFY_REPAIR -> "shield"; j.versionId.startsWith("task:modpack") -> "chest"; j.versionId.startsWith("task:optifine") -> "bolt"; else -> "download" }, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(when (j.kind) { JobKind.INSTALL -> "Install ${j.title}"; JobKind.VERIFY_REPAIR -> "Verify & repair ${j.title}"; JobKind.TASK -> j.title },
                            color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val (label, color) = when (j.status) { JobStatus.DONE -> "DONE" to p.success; JobStatus.FAILED -> "FAILED" to p.danger; JobStatus.CANCELLED -> "CANCELLED" to p.warning; JobStatus.QUEUED -> "QUEUED" to p.textSecondary; JobStatus.RUNNING -> "RUNNING" to Color(0xFF8FD3FF) }
                        Badge(label, color)
                    }
                    Text(j.stage, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (j.active) { Spacer(Modifier.height(6.dp)); PixelProgress(j.fraction) }
                    Text("${j.finishedFiles}/${j.totalFiles} files · ${formatBytes(j.bytes)}${if (j.failedFiles > 0) " · ${j.failedFiles} failed" else ""}", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    if (j.message.isNotBlank()) Text(j.message, color = p.textSecondary, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (j.active) PixelButton("Cancel", { vm.cancelJob(j.versionId) }, style = PxStyle.DANGER, icon = "stop", height = 34.dp)
                        if (j.kind != JobKind.TASK && (j.status == JobStatus.FAILED || j.status == JobStatus.CANCELLED))
                            PixelButton("Retry (resumes)", { if (j.kind == JobKind.INSTALL) vm.install(j.versionId) else vm.repair(j.versionId) }, icon = "refresh", height = 34.dp)
                    }
                }
            }
            val other = batches.filterKeys { k -> jobs.keys.none { k.contains(it) } }
            items(other.values.toList(), key = { "b:" + it.batchName }) { b ->
                PixelPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
                    Text(b.batchName, color = Color.White, fontFamily = Monocraft, fontSize = 13.sp)
                    if (b.totalFiles > 0) PixelProgress(b.finishedFiles.toFloat() / b.totalFiles, Modifier.padding(vertical = 4.dp))
                    Text("${b.finishedFiles}/${b.totalFiles} files · ${formatBytes(b.downloadedBytes)}", color = p.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}
