package pro.cslauncher.app.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pro.cslauncher.core.util.Hashing
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small image loader for catalog icons: memory LRU + disk cache, HTTPS only, size-capped, at most 4 parallel downloads. */
object ImageCache {
    private val mem = LruCache<String, ImageBitmap>(96)
    private val missing = LruCache<String, Boolean>(256)
    private val gate = Semaphore(4)
    private const val MAX_BYTES = 3 * 1024 * 1024

    fun peek(key: String?): ImageBitmap? = key?.let { mem.get(it) }

    suspend fun load(ctx: Context, url: String, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        mem.get(url)?.let { return@withContext it }
        if (missing.get(url) == true || !url.startsWith("https://")) return@withContext null
        val dir = File(ctx.cacheDir, "img")
        val file = File(dir, Hashing.sha1(url.toByteArray()))
        val bytes = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else gate.withPermit { download(url) }?.also { b ->
            dir.mkdirs(); runCatching { file.writeBytes(b) }; trim(dir)
        }
        val img = bytes?.let { decode(it, targetPx) }
        if (img == null) missing.put(url, true) else mem.put(url, img)
        img
    }

    /** Puts an already-decoded image (e.g. a mod's embedded icon) into the memory cache. */
    fun decodeInto(key: String, bytes: ByteArray, targetPx: Int): ImageBitmap? =
        mem.get(key) ?: decode(bytes, targetPx)?.also { mem.put(key, it) }

    private fun decode(b: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= targetPx && o.outHeight / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private fun download(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CSLauncherPro (Android launcher; icons)")
        try {
            if (c.responseCode != 200) return null
            c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) return null; out.write(buf, 0, n) }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= 800) return
        files.sortedBy { it.lastModified() }.take(files.size - 600).forEach { it.delete() }
    }
}

@Composable
fun RemoteCover(url: String?, modifier: Modifier = Modifier, fallbackIcon: String = "grass") {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val targetPx = with(density) { maxOf(maxWidth, maxHeight).roundToPx().coerceIn(1, 2048) }
        val img by produceState(ImageCache.peek(url), url, targetPx) {
            if (url != null && value == null) value = ImageCache.load(ctx.applicationContext, url, targetPx)
        }
        val bitmap = img
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PixelIcon(fallbackIcon, size = 44.dp, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

/** Remote image with a pixel-icon placeholder; tiny sources are scaled with nearest-neighbour to stay crisp. */
@Composable
fun RemoteImage(url: String?, size: Dp, modifier: Modifier = Modifier, fallbackIcon: String = "image") {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val img by produceState(ImageCache.peek(url), url) { if (url != null && value == null) value = ImageCache.load(ctx.applicationContext, url, px) }
    val b = img
    if (b != null) Image(b, null, modifier.size(size), contentScale = ContentScale.Crop, filterQuality = if (b.width <= 64) FilterQuality.None else FilterQuality.Low)
    else PixelIcon(fallbackIcon, modifier, size = size * 0.75f, tint = androidx.compose.ui.graphics.Color(0xFFB0B0B0))
}

/** Image from bytes already in memory (e.g. a mod jar's icon), cached under [key]. */
@Composable
fun BytesImage(key: String, bytes: () -> ByteArray?, size: Dp, modifier: Modifier = Modifier, fallbackIcon: String = "pickaxe") {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val img by produceState(ImageCache.peek(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { bytes()?.let { ImageCache.decodeInto(key, it, px) } }
    }
    val b = img
    if (b != null) Image(b, null, modifier.size(size), contentScale = ContentScale.Fit, filterQuality = if (b.width <= 64) FilterQuality.None else FilterQuality.Low)
    else PixelIcon(fallbackIcon, modifier, size = size * 0.75f, tint = androidx.compose.ui.graphics.Color(0xFFB0B0B0))
}
