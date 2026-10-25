package pro.cslauncher.app.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.content.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

enum class ContentSection(val title: String) { MODS("Mods"), RESOURCE_PACKS("Resource packs"), SHADERS("Shader packs"), WORLDS("Worlds"), SCREENSHOTS("Screenshots"), RECORDINGS("Recordings") }

private data class Entry(val key: String, val title: String, val subtitle: String, val path: Path, val enabled: Boolean? = null, val warning: String? = null,
                         /** Icon inside the archive (mods: declared icon, packs: pack.png); worlds use <dir>/icon.png. */
                         val archiveIcon: String? = null)

@Composable
fun ContentScreen(vm: LauncherViewModel, section: ContentSection, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.selected
    val scope = rememberCoroutineScope()
    val gameDir = profile?.gameDir(Services.dirs)
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Entry?>(null) }
    val trash = Services.dirs.backups.resolve("trash")

    LaunchedEffect(section, refresh, gameDir) {
        entries = withContext(Dispatchers.IO) {
            runCatching {
                when (section) {
                    ContentSection.MODS -> gameDir?.let { gd ->
                        val mm = ModManager(gd)
                        val findings = profile.let { pr -> runCatching { mm.checkCompatibility(pr.versionId, pr.loader?.type, 17) }.getOrDefault(emptyList()) }
                        mm.list().map { m -> Entry(m.file.toString(), m.name, listOfNotNull(m.version, m.loaders.joinToString { it.name.lowercase() }, formatBytes(m.sizeBytes)).joinToString(" · "),
                            m.file, m.enabled, findings.filter { it.file == m.file }.joinToString("; ") { it.message }.ifBlank { m.metadataError }, m.iconPath) }
                    }
                    ContentSection.RESOURCE_PACKS -> gameDir?.let { gd ->
                        val rp = ResourcePackManager(gd); val enabled = rp.enabled().toSet(); val expected = profile.let { PackFormats.expectedFor(it.versionId) }
                        rp.list().map { pk -> Entry(pk.path.toString(), pk.name, "${PackFormats.describe(pk.packFormat)} · ${formatBytes(pk.sizeBytes)}", pk.path, pk.path.fileName.toString() in enabled,
                            pk.problem ?: if (expected != null && pk.packFormat != null && pk.packFormat != expected) "Made for a different version (expected format $expected)" else null, "pack.png") }
                    }
                    ContentSection.SHADERS -> gameDir?.let { gd ->
                        val sp = ShaderPackManager(gd); val loaderKind = sp.detectLoader(ModManager(gd).list()); val active = sp.active(loaderKind)
                        val warn = sp.warnings(profile.rendererId, loaderKind).joinToString("; ").ifBlank { null }
                        sp.list().map { pk -> Entry(pk.path.toString(), pk.name, formatBytes(pk.sizeBytes), pk.path, pk.path.fileName.toString() == active, warn) }
                    }
                    ContentSection.WORLDS -> gameDir?.let { gd ->
                        WorldManager(gd, Services.dirs.backups.resolve("worlds")).list(computeSize = true).map { w ->
                            Entry(w.dir.toString(), w.levelName ?: w.folderName, listOfNotNull(w.versionName, w.sizeBytes?.let(::formatBytes), w.lastPlayedMs?.let(::relativeTime)).joinToString(" · "), w.dir, warning = w.readError)
                        }
                    }
                    ContentSection.SCREENSHOTS -> gameDir?.resolve("screenshots")?.toFile()?.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }
                        ?.map { Entry(it.path, it.name, formatBytes(it.length()), it.toPath()) }
                    ContentSection.RECORDINGS -> Services.dirs.recordings.toFile().listFiles { f -> f.extension == "mp4" && !f.name.startsWith(".") }?.sortedByDescending { it.lastModified() }
                        ?.map { Entry(it.path, it.name, "${formatBytes(it.length())} · ${relativeTime(it.lastModified())}", it.toPath()) }
                }.orEmpty()
            }.getOrElse { vm.toast(it.message); emptyList() }
        }
    }

    fun act(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { vm.toast(it.message) } }; refresh++ }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || gameDir == null) return@rememberLauncherForActivityResult
        act {
            val f = copyToCache(ctx, uri).toPath()
            try {
                when (section) {
                    ContentSection.MODS -> ModManager(gameDir).import(f)
                    ContentSection.RESOURCE_PACKS -> ResourcePackManager(gameDir).import(f)
                    ContentSection.SHADERS -> ShaderPackManager(gameDir).import(f)
                    ContentSection.WORLDS -> WorldManager(gameDir, Services.dirs.backups.resolve("worlds")).import(f)   // never overwrites an existing world
                    else -> {}
                }
            } finally { Files.deleteIfExists(f) }
            vm.toast("Imported ${f.fileName}")
        }
    }

    fun share(path: Path, mime: String, view: Boolean = false) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", path.toFile())
        val i = if (view) Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime) else Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        ctx.startActivity(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(section.title + (profile?.let { " · ${it.name}" } ?: ""), onBack) {
            if (section in setOf(ContentSection.MODS, ContentSection.RESOURCE_PACKS, ContentSection.SHADERS, ContentSection.WORLDS))
                HeaderAction("Import file", "folder", { importer.launch(arrayOf("application/zip", "application/java-archive", "application/octet-stream", "*/*")) })
        }
        if (profile == null && section != ContentSection.RECORDINGS) { Text("Select a profile first.", color = p.textMuted, modifier = Modifier.padding(16.dp)); return@Column }
        if (entries.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelIcon(when (section) { ContentSection.MODS -> "pickaxe"; ContentSection.WORLDS -> "world"; ContentSection.SCREENSHOTS -> "image"; ContentSection.RECORDINGS -> "record"; else -> "painting" }, size = 48.dp)
            PixelTitle("Nothing here yet", Modifier.padding(top = 10.dp), fontSize = 14.sp)
            if (section != ContentSection.SCREENSHOTS && section != ContentSection.RECORDINGS && section != ContentSection.WORLDS)
                Text("Use Browse to find ${section.title.lowercase()} for this profile, or import a file.", color = p.textMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(if (section == ContentSection.SCREENSHOTS) 200.dp else 300.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
            itemsIndexed(entries, key = { _, it -> it.key }) { i, e ->
                val inline = section == ContentSection.MODS || section == ContentSection.RESOURCE_PACKS
                PixelPanel(Modifier.fillMaxWidth().staggerIn(i % 14), padding = 7.dp) {
                    if (section == ContentSection.SCREENSHOTS) {
                        val bmp = remember(e.key) { BitmapFactory.decodeFile(e.key, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
                        bmp?.let { Image(it, e.title, Modifier.fillMaxWidth().heightIn(max = 112.dp).padding(bottom = 4.dp)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (section in setOf(ContentSection.MODS, ContentSection.RESOURCE_PACKS, ContentSection.SHADERS, ContentSection.WORLDS)) {
                            PixelSlot(size = 40.dp) {
                                val fallback = when (section) { ContentSection.MODS -> "pickaxe"; ContentSection.WORLDS -> "world"; ContentSection.SHADERS -> "sun"; else -> "painting" }
                                BytesImage("icon:" + e.key + ":" + (e.archiveIcon ?: ""), {
                                    if (section == ContentSection.WORLDS) e.path.resolve("icon.png").toFile().takeIf { it.isFile && it.length() < (1 shl 20) }?.readBytes()
                                    else ModInspector.readIcon(e.path, e.archiveIcon)
                                }, 30.dp, fallbackIcon = fallback)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = Color.White, fontFamily = Monocraft, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.subtitle, color = p.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            e.warning?.let { Text(it, color = p.warning, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        if (e.enabled != null && section != ContentSection.SHADERS) PixelToggle(e.enabled, { on ->
                            act {
                                when (section) {
                                    ContentSection.MODS -> { val gd = gameDir!!; ModManager(gd).list().firstOrNull { it.file == e.path }?.let { ModManager(gd).setEnabled(it, on) } }
                                    ContentSection.RESOURCE_PACKS -> { val rp = ResourcePackManager(gameDir!!); val name = e.path.fileName.toString()
                                        rp.setEnabledOrder(if (on) rp.enabled() + name else rp.enabled() - name) }
                                    else -> {}
                                }
                            }
                        })
                        if (inline) { Spacer(Modifier.width(6.dp)); PixelIconButton("trash", if (section == ContentSection.MODS) "Remove" else "Delete", { confirm = e }, style = PxStyle.DANGER, size = 30.dp) }
                    }
                    if (!inline) Spacer(Modifier.height(6.dp))
                    if (!inline) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (section) {
                            ContentSection.SHADERS -> PixelButton(if (e.enabled == true) "Deactivate" else "Activate", { act { val sp = ShaderPackManager(gameDir!!); val l = sp.detectLoader(ModManager(gameDir).list()); sp.setActive(l, if (e.enabled == true) null else e.path.fileName.toString()) } },
                                style = if (e.enabled == true) PxStyle.SELECTED else PxStyle.NORMAL, height = 30.dp, fontSize = 11.sp)
                            ContentSection.WORLDS -> {
                                PixelButton("Back up", { act { val b = WorldManager(gameDir!!, Services.dirs.backups.resolve("worlds")).backup(e.path); vm.toast("Backup saved: ${b.fileName}") } }, icon = "shield", height = 30.dp, fontSize = 11.sp)
                                PixelButton("Export", { scope.launch {
                                    val out = File(ctx.cacheDir, "exports").apply { mkdirs() }.resolve("${e.path.fileName}.zip").toPath()
                                    val ok = withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(out); WorldManager(gameDir!!, Services.dirs.backups.resolve("worlds")).export(e.path, out) } }
                                    ok.exceptionOrNull()?.let { vm.toast(it.message) } ?: shareCache(ctx, out.toFile())
                                } }, icon = "upload", height = 30.dp, fontSize = 11.sp)
                            }
                            ContentSection.SCREENSHOTS -> PixelButton("Share", { share(e.path, "image/png") }, icon = "upload", height = 30.dp, fontSize = 11.sp)
                            ContentSection.RECORDINGS -> { PixelButton("Play", { share(e.path, "video/mp4", view = true) }, icon = "play", height = 30.dp, fontSize = 11.sp)
                                PixelButton("Share", { share(e.path, "video/mp4") }, icon = "upload", height = 30.dp, fontSize = 11.sp) }
                            else -> {}
                        }
                        Spacer(Modifier.weight(1f))
                        PixelIconButton("trash", if (section == ContentSection.MODS) "Remove" else "Delete", { confirm = e }, style = PxStyle.DANGER, size = 30.dp)
                    }
                }
            }
        }
    }

    confirm?.let { e ->
        val text = when (section) {
            ContentSection.WORLDS -> "\"${e.title}\" will be permanently deleted. Make a backup first if you may want it back."
            ContentSection.MODS -> "${e.title} is moved to the launcher trash (backups/trash) and can be restored from there."
            else -> "${e.title} will be deleted."
        }
        ConfirmDialog("Delete?", text, "Delete", destructive = true, onConfirm = {
            act {
                when (section) {
                    ContentSection.MODS -> { val gd = gameDir!!; ModManager(gd).list().firstOrNull { it.file == e.path }?.let { ModManager(gd).remove(it, trash) } }
                    ContentSection.WORLDS -> WorldManager(gameDir!!, Services.dirs.backups.resolve("worlds")).delete(e.path)
                    else -> pro.cslauncher.core.util.FsUtil.deleteTreeWithin(e.path.parent, e.path)
                }
            }
        }, onDismiss = { confirm = null })
    }
}

fun shareCache(ctx: android.content.Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
