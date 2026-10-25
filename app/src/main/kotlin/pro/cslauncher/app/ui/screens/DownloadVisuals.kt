package pro.cslauncher.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pro.cslauncher.app.install.InstallJob
import pro.cslauncher.app.install.JobStatus
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.app.ui.theme.LocalReduceMotion
import pro.cslauncher.app.ui.theme.Monocraft
import pro.cslauncher.app.ui.theme.Px
import kotlin.math.max
import kotlin.math.min

enum class DlPhase { ACTIVE, DONE, FAILED, IDLE }

fun InstallJob.phase(): DlPhase = when (status) {
    JobStatus.QUEUED, JobStatus.RUNNING -> DlPhase.ACTIVE
    JobStatus.DONE -> DlPhase.DONE
    JobStatus.FAILED -> DlPhase.FAILED
    JobStatus.CANCELLED -> DlPhase.IDLE
}

private val Dirt = Color(0xFF866043)
private val DirtDark = Color(0xFF6A4B33)
private val DirtLight = Color(0xFF9C7352)
private val GrassTop = Color(0xFF62AE3B)
private val GrassHi = Color(0xFF86CF55)
private val GrassDark = Color(0xFF468A2A)
private val Tie = Color(0xFF6B4A2A)
private val Rail = Color(0xFFA7A7A7)
private val RailDark = Color(0xFF5E5E5E)
private val CartDark = Color(0xFF2A2B2C)
private val CartBody = Color(0xFF7A7C80)
private val CartHi = Color(0xFFA9ABAF)

private fun hash(a: Int, b: Int): Int { var h = a * 73856093 xor b * 19349663; h = h xor (h ushr 13); h *= 0x5bd1e995; return h xor (h ushr 15) }

/**
 * Pixel download bar: grass blocks are laid one by one (the newest drops in with a small bounce), a rail is built on top of
 * them and a minecart rides at the progress front. Unknown progress: the cart rolls back and forth. Done: sparkle burst.
 * All infinite motion stops when the job is not active or reduced motion is on.
 */
@Composable
fun MinecartProgress(fraction: Float?, phase: DlPhase, modifier: Modifier = Modifier, height: Dp = 32.dp) {
    val reduce = LocalReduceMotion.current
    val animate = phase == DlPhase.ACTIVE && !reduce
    val target = if (phase == DlPhase.DONE) 1f else (fraction ?: 0f).coerceIn(0f, 1f)
    val shown by animateFloatAsState(target, if (reduce) snap() else tween(450, easing = FastOutSlowInEasing), label = "cart")
    val indeterminate = fraction == null && phase == DlPhase.ACTIVE
    var shimmer = -1f
    var ping = 0.5f
    if (animate) {
        val inf = rememberInfiniteTransition(label = "dl")
        shimmer = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "shimmer").value
        if (indeterminate) ping = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ping").value
    }
    val bounce = remember { Animatable(0f) }
    val burst = remember { Animatable(if (phase == DlPhase.DONE) 1f else 0f) }
    LaunchedEffect(phase) {
        if (phase == DlPhase.DONE && !reduce) { burst.snapTo(0f); burst.animateTo(1f, tween(650)) } else burst.snapTo(1f)
    }
    val desc = when (phase) {
        DlPhase.DONE -> "Finished"; DlPhase.FAILED -> "Failed"; DlPhase.IDLE -> "Stopped"
        DlPhase.ACTIVE -> fraction?.let { "${(it * 100).toInt()} percent" } ?: "Working"
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(height).semantics { contentDescription = "Progress: $desc" }) {
        val u = with(LocalDensity.current) { (height / 16).toPx() }
        val innerW = constraints.maxWidth - 2 * u
        val n = max(1, (innerW / (u * 7)).toInt())
        val filled = if (indeterminate) 0 else (shown * n + 0.0001f).toInt().coerceIn(0, n)
        var last by remember { mutableIntStateOf(-1) }
        LaunchedEffect(filled) {
            if (!reduce && last >= 0 && filled > last) { bounce.snapTo(1f); bounce.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 700f)) }
            last = filled
        }
        val bo = bounce.value; val bu = burst.value
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val barH = u * 9; val top = h - barH
            val sw = innerW / n
            // Sunken track.
            drawRect(Px.border, Offset(0f, top), Size(w, barH))
            drawRect(Px.inset, Offset(u, top + u), Size(w - 2 * u, barH - 2 * u))
            drawRect(Px.insetShade, Offset(u, top + u), Size(w - 2 * u, u))
            // Empty slots: a faint dot where each block will go.
            for (i in filled until n) drawRect(Color(0x1FFFFFFF), Offset(u + i * sw + sw / 2 - u / 2, top + u + barH / 2 - u), Size(u, u))
            // Laid blocks.
            for (i in 0 until filled) {
                val y = top + u + if (i == filled - 1) -bo * u * 3 else 0f
                grassBlock(u + i * sw, y, sw, barH - 2 * u, u, i, phase)
            }
            // Shimmer band across the laid blocks.
            val fw = filled * sw
            if (shimmer >= 0f && fw > 0f) {
                val band = sw * 2.5f; val bx = u - band + (fw + band) * shimmer
                val l = max(u, bx); val r = min(u + fw, bx + band)
                if (r > l) drawRect(Color.White.copy(alpha = 0.16f), Offset(l, top + u), Size(r - l, barH - 2 * u))
            }
            // Rail on top of the laid blocks.
            val railEnd = u + fw
            var tx = u
            while (tx + 2 * u <= railEnd) { drawRect(Tie, Offset(tx, top - u), Size(2 * u, u)); tx += 3 * u }
            if (railEnd > u) {
                drawRect(RailDark, Offset(u, top - 2 * u), Size(railEnd - u, u))
                drawRect(Rail, Offset(u, top - 2 * u), Size(railEnd - u, u * 0.5f))
            }
            // Minecart.
            val front = if (indeterminate) u + innerW * ping else u + innerW * shown
            val cw = 10 * u
            val cx = (front - cw / 2).coerceIn(0f, w - cw)
            minecart(cx, top - 2 * u, u, phase, ((front / u).toInt() / 2) % 2 == 0)
            if (animate && !indeterminate && shimmer >= 0f) {
                // Two dust specks behind the moving cart.
                val a = 1f - (shimmer * 3f % 1f)
                drawRect(Color(0xFFBFB59E).copy(alpha = 0.5f * a), Offset(cx - 2 * u - (1 - a) * 2 * u, top - 3 * u), Size(u, u))
                drawRect(Color(0xFFBFB59E).copy(alpha = 0.35f * a), Offset(cx - 3.5f * u - (1 - a) * 2 * u, top - 4.5f * u), Size(u, u))
            }
            if (phase == DlPhase.DONE && bu < 1f) {
                val a = 1f - bu; val d = bu * 7 * u
                val ccx = cx + cw / 2; val ccy = top - 6 * u
                listOf(-1f to -1f, 1f to -1f, -1.3f to 0.2f, 1.3f to 0.2f, 0f to -1.4f).forEachIndexed { k, (dx, dy) ->
                    val c = if (k % 2 == 0) Color(0xFFFFE866) else Color.White
                    val sx = ccx + dx * d; val sy = ccy + dy * d
                    drawRect(c.copy(alpha = a), Offset(sx - u / 2, sy - 1.5f * u), Size(u, 3 * u))
                    drawRect(c.copy(alpha = a), Offset(sx - 1.5f * u, sy - u / 2), Size(3 * u, u))
                }
            }
        }
    }
}

private fun DrawScope.grassBlock(x: Float, y: Float, w: Float, h: Float, u: Float, i: Int, phase: DlPhase) {
    drawRect(Dirt, Offset(x, y), Size(w, h))
    // Speckles.
    for (k in 0 until 4) {
        val hx = hash(i, k * 3 + 1); val hy = hash(i, k * 3 + 2)
        val px = x + ((hx ushr 4) and 0xFFFF) % max(1, (w / u).toInt() - 1) * u
        val py = y + 2 * u + ((hy ushr 4) and 0xFFFF) % max(1, (h / u).toInt() - 3) * u
        drawRect(if (k % 2 == 0) DirtDark else DirtLight, Offset(px, py), Size(u, u))
    }
    drawRect(GrassTop, Offset(x, y), Size(w, 2 * u))
    drawRect(GrassHi, Offset(x, y), Size(w, u * 0.6f))
    // Grass drips over the dirt.
    val cols = max(1, (w / u).toInt())
    for (c in 0 until cols) if (hash(i, 40 + c) and 3 == 0) drawRect(GrassDark, Offset(x + c * u, y + 2 * u), Size(u, u))
    drawRect(Color(0x40000000), Offset(x + w - u * 0.5f, y), Size(u * 0.5f, h))
    if (phase == DlPhase.FAILED) drawRect(Color(0x88B83232), Offset(x, y), Size(w, h))
}

private fun DrawScope.minecart(x: Float, railTop: Float, u: Float, phase: DlPhase, frame: Boolean) {
    val bodyTop = railTop - 7 * u
    // Cargo peeking over the rim: diamond ore while working, emerald when done, empty after a failure.
    if (phase != DlPhase.FAILED) {
        val (c1, c2) = if (phase == DlPhase.DONE) Color(0xFF41D96B) to Color(0xFF1C8A3A) else Color(0xFF7FE9F0) to Color(0xFF8E8E8E)
        drawRect(c2, Offset(x + 3 * u, bodyTop - 1.5f * u), Size(4 * u, 2 * u))
        drawRect(c1, Offset(x + 3.5f * u, bodyTop - 1.5f * u), Size(u, u)); drawRect(c1, Offset(x + 5.5f * u, bodyTop - u), Size(u, u))
    }
    drawRect(CartDark, Offset(x, bodyTop), Size(10 * u, 5 * u))
    drawRect(CartBody, Offset(x + u, bodyTop + u), Size(8 * u, 3 * u))
    drawRect(CartHi, Offset(x + u, bodyTop + u), Size(8 * u, u * 0.6f))
    drawRect(Color(0xFF55575B), Offset(x + 2 * u, bodyTop + 2 * u), Size(6 * u, u))
    // Wheels with a spoke pixel that flips as the cart moves.
    for (wx in listOf(x + u, x + 7 * u)) {
        drawRect(Color(0xFF1B1B1C), Offset(wx, railTop - 2.5f * u), Size(2 * u, 2.5f * u))
        drawRect(Color(0xFF6E6E6E), Offset(wx + if (frame) 0f else u, railTop - (if (frame) 2.5f else 1f) * u), Size(u, u))
    }
}

/** Measured transfer rate (bytes/s, files/s) over the last few seconds; null until there is at least a second of data. */
data class TransferRate(val bytesPerSec: Double, val filesPerSec: Double)

@Composable
fun rememberTransferRate(key: String, bytes: Long, files: Int, active: Boolean): TransferRate? {
    val samples = remember(key) { ArrayDeque<Triple<Long, Long, Int>>() }
    var rate by remember(key) { mutableStateOf<TransferRate?>(null) }
    var tick by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, active) { while (active) { delay(1000); tick++ } }
    LaunchedEffect(key, bytes, files, active, tick) {
        if (!active) { rate = null; samples.clear(); return@LaunchedEffect }
        val now = System.nanoTime() / 1_000_000
        samples.addLast(Triple(now, bytes, files))
        while (samples.size > 2 && now - samples.first().first > 6000) samples.removeFirst()
        val f = samples.first(); val dt = (now - f.first) / 1000.0
        if (dt >= 1.0) rate = TransferRate(((bytes - f.second) / dt).coerceAtLeast(0.0), ((files - f.third) / dt).coerceAtLeast(0.0))
    }
    return rate
}

fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${"%02d".format(seconds % 60)}s"
    else -> "${seconds / 3600}h ${"%02d".format((seconds % 3600) / 60)}m"
}

/** Progress block shared by Home and Downloads: minecart bar, percentage, files, size, speed and remaining time. */
@Composable
fun JobProgress(job: InstallJob, modifier: Modifier = Modifier, compact: Boolean = false) {
    val p = LocalPalette.current
    val phase = job.phase()
    val rate = rememberTransferRate(job.versionId, job.bytes, job.finishedFiles, phase == DlPhase.ACTIVE)
    Column(modifier) {
        MinecartProgress(job.fraction, phase, height = if (compact) 28.dp else 32.dp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val files = if (job.totalFiles > 0) "${job.finishedFiles}/${job.totalFiles} files" else job.stage
            Text(listOfNotNull(files, job.bytes.takeIf { it > 0 }?.let(::formatBytes), job.failedFiles.takeIf { it > 0 }?.let { "$it failed" }).joinToString(" · "),
                color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (phase == DlPhase.ACTIVE && rate != null) {
                val eta = if (rate.filesPerSec > 0.05 && job.totalFiles > job.finishedFiles) ((job.totalFiles - job.finishedFiles) / rate.filesPerSec).toLong() else null
                Text(listOfNotNull(if (rate.bytesPerSec >= 1024) "${formatBytes(rate.bytesPerSec.toLong())}/s" else null, eta?.let { "~${formatEta(it)} left" }).joinToString(" · "),
                    color = Color(0xFF9FE07F), fontFamily = Monocraft, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
