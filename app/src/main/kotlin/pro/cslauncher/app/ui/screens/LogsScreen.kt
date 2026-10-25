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
import pro.cslauncher.app.ui.theme.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import pro.cslauncher.core.diagnostics.RepairAction
import pro.cslauncher.core.diagnostics.RepairEngine
import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.launch.SessionRecord
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.Redactor
import java.io.File

@Composable
fun LogsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    // Recent game sessions, newest first, so the log of an earlier launch (for example a different version) stays reachable.
    val sessions = remember(ui.failure) {
        Services.dirs.sessions.toFile().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.take(10)
            ?.map { d -> d to runCatching { CsJson.decodeFromString(SessionRecord.serializer(), File(d, "session.json").readText()) }.getOrNull() }.orEmpty()
    }
    var picked by remember(ui.failure) { mutableStateOf<File?>(null) }
    val sessionDir = picked ?: ui.failure?.sessionDir?.let(::File) ?: sessions.firstOrNull()?.first
    val record = sessions.firstOrNull { it.first == sessionDir }?.second
    val gameLog = remember(sessionDir) { sessionDir?.let { File(it, "game-output.log") }?.takeIf { it.isFile }?.let { val x = Log4jXml(); tail(it, 400).lines().flatMap { l -> x.feed(l) }.joinToString("\n") }.orEmpty() }
    val launcherLog = remember { tail(Services.launcherLogFile, 300) }
    var repairing by remember { mutableStateOf<String?>(null) }
    var lastCrash by remember { mutableStateOf(Services.lastCrashFile.takeIf { it.isFile }?.readText()?.take(6000)) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Logs & diagnostics", onBack) {
            HeaderAction("Share report", "upload", {
                val report = buildString {
                    appendLine("CS Launcher Pro diagnostic report"); appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${Services.abi}")
                    appendLine("GLES ${Services.device.glesVersion}, Vulkan ${Services.device.vulkanApi}")
                    ui.failure?.let { f -> appendLine("\n== ${f.title} =="); f.issues.forEach { appendLine("- ${it.stage}: ${it.message}") }; f.diagnoses.forEach { d -> appendLine("* ${d.kind.title}: ${d.explanation}"); d.evidence.forEach { appendLine("    $it") } } }
                    record?.let { r -> appendLine("\n== Session ${sessionDir?.name} ==")
                        appendLine("Version ${r.versionId}, renderer ${r.rendererId}, state ${r.state}" + (r.failedAt?.let { ", failed at $it" } ?: "") + (r.exitCode?.let { ", exit $it" } ?: ""))
                        appendLine("Milestones: ${r.milestones.joinToString()}"); r.message?.let { appendLine("Message: $it") }; r.crashLine?.let { appendLine("Crash line: $it") } }
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
            })
        }
        lastCrash?.let { c ->
            CsCard(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("The launcher crashed last time", color = p.danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    LinkButton("Dismiss", { Services.lastCrashFile.delete(); lastCrash = null })
                }
                Text(c.lines().take(6).joinToString("\n"), color = p.textSecondary, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 6)
            }
        }
        ui.failure?.let { f ->
            CsCard(Modifier.fillMaxWidth().heightIn(max = 150.dp).padding(horizontal = 10.dp, vertical = 3.dp)) {
              Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(f.title, color = p.danger, fontSize = 12.sp)
                f.issues.forEach { Text("• ${it.message}", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp) }
                f.diagnoses.forEach { d ->
                    Text("${d.kind.title} — ${(d.confidence * 100).toInt()}% confidence", color = p.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(d.explanation, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp)
                    d.repairs.forEach { r ->
                        val safeAuto = r is RepairAction.RedownloadFiles || r === RepairAction.RebuildNatives || r === RepairAction.RemoveTempFiles
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(r.description, color = p.textMuted, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f))
                            if (safeAuto) LinkButton("Apply", {
                                val v = ui.selected?.effectiveVersionId ?: return@LinkButton
                                repairing = r.description
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { runCatching { RepairEngine(Services.dirs, Services.installer, Services.downloads, Services.platform).execute(r, v, runCatching { Services.manifests.load() }.getOrNull()) } }
                                    repairing = null
                                    vm.toast(res.getOrNull()?.let { "${if (it.success) "Done" else "Not fixed"}: ${it.verification}" } ?: res.exceptionOrNull()?.message)
                                }
                            })
                        }
                    }
                }
                repairing?.let { Text("Working: $it", color = p.textSecondary, fontSize = 11.sp); AppProgress() }
              }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PixelTabs(listOf(0, 1), tab, { if (it == 0) "Game" else "Launcher" }, { tab = it }, icon = { if (it == 0) "grass" else "terminal" }, height = 30.dp)
            if (sessions.size > 1) {
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val fmt = remember { java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()) }
                    sessions.forEach { (d, r) ->
                        val label = if (r == null) d.name else "${r.versionId} · ${fmt.format(java.util.Date(r.startedAtMs))}" + if (r.state.name == "EXITED") "" else " · ${r.state.name.lowercase()}"
                        PixelButton(label, { picked = d }, style = if (d == sessionDir) PxStyle.SELECTED else PxStyle.NORMAL, height = 30.dp, fontSize = 10.sp)
                    }
                }
            }
        }
        record?.message?.let { m -> if (ui.failure?.sessionDir != sessionDir?.absolutePath) Text(m, color = p.danger, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp)) }
        val text = Redactor.redact(if (tab == 0) gameLog.ifBlank { "No game log yet. Start the game once and its output appears here." } else launcherLog.ifBlank { "Empty." })
        Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp).drawBehind { pixelInset(2.dp.toPx()) }.padding(4.dp)) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(6.dp)) {
                Text(text, color = Color(0xFFC8C9CB), fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

private fun tail(f: File, lines: Int): String = runCatching {
    if (!f.isFile) return ""
    val raf = java.io.RandomAccessFile(f, "r"); val len = raf.length(); val start = (len - 256 * 1024).coerceAtLeast(0)
    raf.seek(start); val bytes = ByteArray((len - start).toInt()); raf.readFully(bytes); raf.close()
    String(bytes, Charsets.UTF_8).lines().takeLast(lines).joinToString("\n")
}.getOrDefault("")
