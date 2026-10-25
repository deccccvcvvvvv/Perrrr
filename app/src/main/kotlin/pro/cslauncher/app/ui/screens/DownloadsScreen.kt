package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        ScreenHeader("Downloads") { if (list.any { !it.active }) TextButton(onClick = { InstallManager.clearFinished() }) { Text("Clear finished", color = p.textSecondary) } }
        if (list.isEmpty() && batches.isEmpty()) Text("No downloads. Installs, repairs and content downloads appear here.", color = p.textMuted, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.versionId }) { j ->
                CsCard(Modifier.fillMaxWidth()) {
                    Text("${if (j.kind == JobKind.INSTALL) "Install" else "Verify & repair"} ${j.versionId}", color = p.text, fontWeight = FontWeight.Medium)
                    val color = when (j.status) { JobStatus.DONE -> p.success; JobStatus.FAILED -> p.danger; JobStatus.CANCELLED -> p.warning; else -> p.textSecondary }
                    Text("${j.status.name.lowercase().replaceFirstChar(Char::uppercase)} · ${j.stage}", color = color, fontSize = 13.sp)
                    if (j.active) {
                        Spacer(Modifier.height(6.dp))
                        j.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.secondarySurface) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.secondarySurface)
                    }
                    Text("${j.finishedFiles}/${j.totalFiles} files · ${formatBytes(j.bytes)}${if (j.failedFiles > 0) " · ${j.failedFiles} failed" else ""}", color = p.textMuted, fontSize = 12.sp)
                    if (j.message.isNotBlank()) Text(j.message, color = p.textSecondary, fontSize = 12.sp, maxLines = 3)
                    Row {
                        if (j.active) TextButton(onClick = { vm.cancelJob(j.versionId) }) { Text("Cancel", color = p.danger) }
                        if (j.status == JobStatus.FAILED || j.status == JobStatus.CANCELLED) TextButton(onClick = { if (j.kind == JobKind.INSTALL) vm.install(j.versionId) else vm.repair(j.versionId) }) { Text("Retry (resumes)", color = p.accent) }
                    }
                }
            }
            val other = batches.filterKeys { k -> jobs.keys.none { k.contains(it) } }
            items(other.values.toList(), key = { "b:" + it.batchName }) { b ->
                CsCard(Modifier.fillMaxWidth()) {
                    Text(b.batchName, color = p.text)
                    Text("${b.finishedFiles}/${b.totalFiles} files · ${formatBytes(b.downloadedBytes)}", color = p.textMuted, fontSize = 12.sp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
