package pro.cslauncher.app.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import pro.cslauncher.app.Services
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** Plays Minecraft's own UI click from the user's installed asset index; no Mojang audio is bundled. */
object MinecraftUiClickSound {
    @Volatile private var pool: SoundPool? = null
    @Volatile private var sampleId: Int = 0
    @Volatile private var loaded = false
    @Volatile private var loading = false
    @Volatile private var lastLookupAtMs = 0L
    private val lock = Any()

    fun play(context: Context) {
        if (!runCatching { Services.settings.state.value.uiClickSounds }.getOrDefault(true)) return
        val soundPool = pool
        if (soundPool != null && loaded && sampleId != 0) {
            soundPool.play(sampleId, 0.55f, 0.55f, 1, 0, 1f)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLookupAtMs >= 60_000L && !loading) loadSound(context.applicationContext, now)
        if (pool == null || !loaded) {
            runCatching { context.getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f) }
        }
    }

    private fun loadSound(context: Context, now: Long) {
        synchronized(lock) {
            if (pool != null || loading || now - lastLookupAtMs < 60_000L) return
            lastLookupAtMs = now
            loading = true
        }
        Thread({
            val candidate = runCatching {
                SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .build()
            }.getOrNull()
            if (candidate == null) { loading = false; return@Thread }
            candidate.setOnLoadCompleteListener { soundPool, id, status ->
                if (status == 0) {
                    sampleId = id
                    loaded = true
                } else {
                    loaded = false
                    runCatching { soundPool.release() }
                    pool = null
                }
            }
            // First try packaged raw sound
            val rawId = runCatching { candidate.load(context, pro.cslauncher.app.R.raw.minecraft_click, 1) }.getOrDefault(0)
            if (rawId != 0) {
                sampleId = rawId
                pool = candidate
                loading = false
                return@Thread
            }
            // Fall back to installed asset
            val source = findClickAsset()
            if (source == null) { candidate.release(); loading = false; return@Thread }
            val id = runCatching { candidate.load(source.toString(), 1) }.getOrDefault(0)
            if (id == 0) {
                runCatching { candidate.release() }
                loading = false
                return@Thread
            }
            pool = candidate
            loading = false
        }, "minecraft-ui-sound-loader").apply { isDaemon = true }.start()
    }

    private fun findClickAsset(): Path? = runCatching {
        val dirs = Services.dirs
        if (!Files.isDirectory(dirs.assetIndexes) || !Files.isDirectory(dirs.assetObjects)) return null
        val indexes = Files.list(dirs.assetIndexes).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() })
                .collect(java.util.stream.Collectors.toList())
        }
        for (index in indexes) {
            val objects = runCatching { JSONObject(String(Files.readAllBytes(index), Charsets.UTF_8)).optJSONObject("objects") }.getOrNull() ?: continue
            val asset = objects.optJSONObject("minecraft/sounds/ui/button/click.ogg")
                ?: objects.optJSONObject("sounds/ui/button/click.ogg")
                ?: continue
            val hash = asset.optString("hash").takeIf { it.matches(Regex("^[a-fA-F0-9]{40}$")) } ?: continue
            val path = dirs.assetObjects.resolve(hash.take(2)).resolve(hash)
            if (Files.isRegularFile(path)) return path
        }
        null
    }.getOrNull()
}
