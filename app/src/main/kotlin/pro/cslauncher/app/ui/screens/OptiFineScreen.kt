package pro.cslauncher.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.content.OptiFineJarInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val OPTIFINE_START = "http://optifine.net/downloads"   // the site's HTTPS endpoint is unreliable (Cloudflare 525); HTTP is allowed for this host only
private const val MAX_JAR_BYTES = 64L * 1024 * 1024

private fun isOptiFineHost(url: String) = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.let { it == "optifine.net" || it.endsWith(".optifine.net") } == true

/**
 * Fetches a download the WebView started, with the page's cookies/user agent, following redirects across http/https.
 * Only optifine.net URLs are accepted, and the result must be a jar of sane size.
 */
private fun fetchOptiFineJar(startUrl: String, userAgent: String, referer: String?, contentDisposition: String?, mime: String?, dir: File): File {
    var url = startUrl
    repeat(6) {
        if (!isOptiFineHost(url)) throw IOException("Downloads are only accepted from optifine.net")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        referer?.let { c.setRequestProperty("Referer", it) }
        try {
            val code = c.responseCode
            if (code in 300..399) { url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("Redirect without location")).toString(); return@repeat }
            if (code != 200) throw IOException("optifine.net answered HTTP $code")
            val name = URLUtil.guessFileName(url, c.getHeaderField("Content-Disposition") ?: contentDisposition, c.contentType ?: mime)
                .replace(Regex("[^A-Za-z0-9._+-]"), "_").let { if (it.endsWith(".jar")) it else "$it.jar" }
            dir.mkdirs()
            val out = File(dir, name); val tmp = File(dir, "$name.part")
            c.inputStream.use { inp -> tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > MAX_JAR_BYTES) throw IOException("File is too large to be OptiFine"); o.write(buf, 0, n) }
            } }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return out
        } finally { c.disconnect() }
    }
    throw IOException("Too many redirects")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OptiFineScreen(vm: LauncherViewModel, profileId: String?, versionHint: String?, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val target = ui.profiles.profiles.firstOrNull { it.id == profileId }
    val wantVersion = versionHint ?: target?.versionId
    var jar by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<OptiFineJarInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var installKey by remember { mutableStateOf<String?>(null) }
    val job = installKey?.let { jobs[it] }

    fun accept(f: File) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { vm.inspectOptiFine(f) } }
            r.onSuccess { jar = f; info = it; status = null }.onFailure { status = it.message; jar = null; info = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { copyToCache(ctx, uri) } }
            f.onSuccess { accept(it) }.onFailure { status = it.message }
        }
    }
    BackHandler(webView?.canGoBack() == true) { webView?.goBack() }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("OptiFine", onBack) {
            HeaderAction("Pick jar file", "folder", { picker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*")) })
        }
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PixelPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
                    PixelTitle("How it works", fontSize = 13.sp)
                    Text("1. On optifine.net find ${wantVersion?.let { "Minecraft $it" } ?: "your Minecraft version"} and tap Download (or Mirror).\n" +
                        "2. Tap the download button on the next page. CS Launcher catches the jar automatically.\n" +
                        "3. Tap Install. Minecraft, OptiFine and its launch profile are set up locally.",
                        color = p.textSecondary, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("OptiFine is made by sp614x and may not be redistributed, so it is always downloaded from the official site. Already have the jar? Use “Pick jar file”.",
                        color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                PixelPanel(Modifier.fillMaxWidth(), selected = info != null, padding = 8.dp) {
                    val i = info
                    if (i == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelIcon("bolt", size = 20.dp); Spacer(Modifier.width(8.dp))
                            Text(status ?: "Waiting for an OptiFine jar…", color = if (status != null) p.danger else p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp)
                        }
                    } else {
                        PixelTitle(i.fullVersion.removePrefix("OptiFine_").replace('_', ' '), fontSize = 14.sp)
                        InfoRow("Minecraft", i.minecraftVersion)
                        InfoRow("Edition", i.edition + if (i.isPreview) " (preview)" else "")
                        InfoRow("Installs as", target?.takeIf { it.versionId == i.minecraftVersion && it.loader == null }?.let { "“${it.name}”" } ?: "new profile")
                        if (wantVersion != null && wantVersion != i.minecraftVersion) Text("This jar is for ${i.minecraftVersion}, not $wantVersion.", color = p.warning, fontSize = 11.sp)
                        if (i.isPreview) Text("Preview builds can be unstable.", color = p.textMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        PixelButton("Install OptiFine", {
                            val f = jar ?: return@PixelButton
                            if (vm.installOptiFine(f, profileId)) installKey = "task:optifine-${i.versionId}"
                        }, Modifier.fillMaxWidth(), style = PxStyle.PRIMARY, icon = "download", enabled = job?.active != true, height = 36.dp)
                    }
                    if (job != null) {
                        Spacer(Modifier.height(8.dp))
                        JobProgress(job, compact = true)
                        Text("${job.stage} ${job.message}".trim(), color = p.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        when (job.status) {
                            pro.cslauncher.app.install.JobStatus.DONE -> PixelButton("Done · back", onBack, Modifier.fillMaxWidth().padding(top = 6.dp), style = PxStyle.PRIMARY, icon = "check")
                            pro.cslauncher.app.install.JobStatus.FAILED -> Text("Failed: ${job.message}", color = p.danger, fontSize = 11.sp)
                            else -> {}
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("No OptiFine exists for Minecraft 26.3 yet; newest builds are previews for 26.2/26.1.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp)
            }
            PixelPanel(Modifier.weight(1f).fillMaxHeight(), padding = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelIconButton("back", "Back", { webView?.let { if (it.canGoBack()) it.goBack() } }, size = 32.dp)
                    PixelIconButton("refresh", "Reload", { webView?.reload() }, size = 32.dp)
                    PixelIconButton("home", "OptiFine downloads", { webView?.loadUrl(OPTIFINE_START) }, size = 32.dp)
                    Box(Modifier.weight(1f)) { if (pageProgress in 1..99) PixelProgress(pageProgress / 100f) }
                }
                if (LocalInspectionMode.current) Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                    Text("optifine.net (web page)", color = Color.Gray, fontSize = 12.sp)
                } else AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                return scheme != "http" && scheme != "https"   // no intent:// or market:// jumps out of the page
                            }
                        }
                        webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress } }
                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            if (!isOptiFineHost(url)) { status = "Ignored a download from outside optifine.net"; return@setDownloadListener }
                            status = "Downloading OptiFine…"; info = null
                            val referer = this.url
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { fetchOptiFineJar(url, userAgent, referer, contentDisposition, mimetype, File(c.cacheDir, "optifine")) } }
                                r.onSuccess { accept(it) }.onFailure { status = "Download failed: ${it.message}" }
                            }
                        }
                        loadUrl(OPTIFINE_START)
                        webView = this
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
