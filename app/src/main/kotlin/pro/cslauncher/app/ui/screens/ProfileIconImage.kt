package pro.cslauncher.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import pro.cslauncher.app.Services
import pro.cslauncher.app.ui.theme.LocalReduceMotion
import pro.cslauncher.app.ui.theme.PixelIcon
import pro.cslauncher.core.profile.Profile
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.min

/** Copies a user-selected icon into app-private storage; profile exports intentionally omit it. */
fun importProfileIcon(context: Context, uri: android.net.Uri): String {
    val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
    val ext = when (mime) {
        "image/png", "image/x-png" -> "png"
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> error("Choose a PNG, JPG, WebP or GIF image")
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 4 * 1024 * 1024) { "Profile icon must be 4 MB or smaller" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    } ?: error("Could not read the selected image")
    require(bytes.isNotEmpty()) { "The selected image is empty" }
    val (width, height) = if (ext == "gif") {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("The GIF could not be decoded")
        movie.width() to movie.height()
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        bounds.outWidth to bounds.outHeight
    }
    require(width in 1..1024 && height in 1..1024 && width.toLong() * height <= 1_000_000L) {
        "Profile image dimensions must be at most 1024 × 1024"
    }
    val id = "${UUID.randomUUID()}.$ext"
    val directory = Services.dirs.config.resolve("profile-icons")
    Files.createDirectories(directory)
    Files.write(directory.resolve(id), bytes)
    return id
}

private fun iconFile(id: String): File? {
    if (!id.matches(Regex("^[a-fA-F0-9-]{36}\\.(png|jpg|webp|gif)$", RegexOption.IGNORE_CASE))) return null
    val root = Services.dirs.config.resolve("profile-icons").toAbsolutePath().normalize()
    val candidate = root.resolve(id).normalize()
    return candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) }?.toFile()
}

@Composable
fun ProfileIconImage(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val id = profile.customIconId
    if (id.isNullOrBlank()) {
        PixelIcon(profileIcon(profile), modifier, size)
        return
    }
    val file = rememberIconFile(id)
    if (file == null) {
        PixelIcon(profileIcon(profile), modifier, size)
        return
    }
    val reduceMotion = LocalReduceMotion.current
    key(id, reduceMotion) {
        AndroidView(
            factory = { context -> createIconView(context, file, reduceMotion) },
            modifier = modifier.size(size).clip(RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun rememberIconFile(id: String): File? = androidx.compose.runtime.remember(id) { runCatching { iconFile(id) }.getOrNull() }

private fun createIconView(context: Context, file: File, reduceMotion: Boolean): View {
    if (file.extension.equals("gif", ignoreCase = true)) {
        if (Build.VERSION.SDK_INT >= 28 && !reduceMotion) {
            val image = ImageView(context)
            runCatching {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                image.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            }.onFailure { image.setImageBitmap(firstGifFrame(file)) }
            return image
        }
        val movie = runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull()
        if (movie != null) return GifIconView(context, movie, animate = !reduceMotion)
    }
    return ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
    }
}

private fun firstGifFrame(file: File): Bitmap? {
    val movie = Movie.decodeFile(file.absolutePath) ?: return null
    val bitmap = Bitmap.createBitmap(movie.width().coerceAtLeast(1), movie.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    movie.setTime(0)
    movie.draw(Canvas(bitmap), 0f, 0f)
    return bitmap
}

/** GIF animation fallback for API 26–27; honors Reduce motion and bounds every frame to the icon view. */
private class GifIconView(context: Context, private val movie: Movie, private val animate: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var startedAt = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (movie.width() <= 0 || movie.height() <= 0 || width <= 0 || height <= 0) return
        if (startedAt == 0L) startedAt = SystemClock.uptimeMillis()
        val duration = movie.duration().coerceAtLeast(100)
        movie.setTime(if (animate) ((SystemClock.uptimeMillis() - startedAt) % duration).toInt() else 0)
        val scale = min(width.toFloat() / movie.width(), height.toFloat() / movie.height())
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.scale(scale, scale, width / 2f, height / 2f)
        movie.draw(canvas, (width / scale - movie.width()) / 2f, (height / scale - movie.height()) / 2f, paint)
        canvas.restoreToCount(save)
        if (animate) postInvalidateDelayed(66)
    }
}
