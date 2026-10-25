package pro.cslauncher.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import pro.cslauncher.core.diagnostics.RepairAction
import pro.cslauncher.core.diagnostics.RepairEngine
import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.util.Redactor
import java.io.File

@Composable
fun LogsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val sessionDir = remember(ui.failure) { ui.failure?.sessionDir?.let(::File) ?: Services.dirs.sessions.toFile().listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } }
    val gameLog = remember(sessionDir) { sessionDir?.let { File(it, "game-output.log") }?.takeIf { it.isFile }?.let { val x = Log4jXml(); tail(it, 400).lines().flatMap { l -> x.feed(l) }.joinToString("\n") }.orEmpty() }
    val launcherLog = remember { tail(Services.launcherLogFile, 300) }
    var repairing by remember { mutableStateOf<String?>(null) }
    var lastCrash by remember { mutableStateOf(Services.lastCrashFile.takeIf { it.isFile }?.readText()?.take(6000)) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Logs & diagnostics", onBack) {
            TextButton(onClick = {
                val report = buildString {
                    appendLine("CS Launcher Pro diagnostic report"); appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${Services.abi}")
                    appendLine("GLES ${Services.device.glesVersion}, Vulkan ${Services.device.vulkanApi}")
                    ui.failure?.let { f -> appendLine("\n== ${f.title} =="); f.issues.forEach { appendLine("- ${it.stage}: ${it.message}") }; f.diagnoses.forEach { d -> appendLine("* ${d.kind.title}: ${d.explanation}"); d.evidence.forEach { appendLine("    $it") } } }
                    sessionDir?.let { File(it, "command-line.txt") }?.takeIf { it.isFile }?.let { appendLine("\n== Command line (redacted) =="); appendLine(it.readText()) }
                    lastCrash?.let { appendLine("\n== Last launcher crash =="); appendLine(it) }
                    appendLine("\n== Game log (tail) =="); appendLine(gameLog)
                    appendLine("\n== Launcher log (tail) =="); appendLine(launcherLog)
                }
                val f = File(ctx.cacheDir, "exports").apply { mkdirs() }.resolve("cs-diagnostics.txt")
                f.writeText(Redactor.redact(report))   // tokens, sessions and keys are removed before anything leaves the app
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Share report", color = p.accent) }
        }
        lastCrash?.let { c ->
            CsCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("The launcher crashed last time", color = p.danger, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Services.lastCrashFile.delete(); lastCrash = null }) { Text("Dismiss", color = p.accent) }
                }
                Text(c.lines().take(12).joinToString("\n"), color = p.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        ui.failure?.let { f ->
            CsCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(f.title, color = p.danger, fontWeight = FontWeight.Medium)
                f.issues.forEach { Text("• ${it.message}", color = p.textSecondary, fontSize = 13.sp) }
                f.diagnoses.forEach { d ->
                    Text("${d.kind.title} — ${(d.confidence * 100).toInt()}% confidence", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(d.explanation, color = p.textSecondary, fontSize = 13.sp)
                    d.repairs.forEach { r ->
                        val safeAuto = r is RepairAction.RedownloadFiles || r === RepairAction.RebuildNatives || r === RepairAction.RemoveTempFiles
                        Row {
                            Text(r.description, color = p.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (safeAuto) TextButton(onClick = {
                                val v = ui.selected?.effectiveVersionId ?: return@TextButton
                                repairing = r.description
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { runCatching { RepairEngine(Services.dirs, Services.installer, Services.downloads, Services.platform).execute(r, v, runCatching { Services.manifests.load() }.getOrNull()) } }
                                    repairing = null
                                    vm.toast(res.getOrNull()?.let { "${if (it.success) "Done" else "Not fixed"}: ${it.verification}" } ?: res.exceptionOrNull()?.message)
                                }
                            }) { Text("Apply", color = p.accent) }
                        }
                    }
                }
                repairing?.let { Text("Working: $it", color = p.textSecondary, fontSize = 12.sp); LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = p.text, modifier = Modifier.padding(top = 8.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Game") }); Tab(tab == 1, { tab = 1 }, text = { Text("Launcher") })
        }
        val text = Redactor.redact(if (tab == 0) gameLog.ifBlank { "No game log yet." } else launcherLog.ifBlank { "Empty." })
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, color = p.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

private fun tail(f: File, lines: Int): String = runCatching {
    if (!f.isFile) return ""
    val raf = java.io.RandomAccessFile(f, "r"); val len = raf.length(); val start = (len - 256 * 1024).coerceAtLeast(0)
    raf.seek(start); val bytes = ByteArray((len - start).toInt()); raf.readFully(bytes); raf.close()
    String(bytes, Charsets.UTF_8).lines().takeLast(lines).joinToString("\n")
}.getOrDefault("")
