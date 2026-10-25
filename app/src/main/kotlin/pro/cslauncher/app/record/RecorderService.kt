package pro.cslauncher.app.record

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pro.cslauncher.app.CsApp
import pro.cslauncher.app.Services
import pro.cslauncher.app.game.NativeBridge
import pro.cslauncher.core.activity.ActivityItem
import pro.cslauncher.core.activity.ActivityKind
import pro.cslauncher.core.media.RecordingPlan
import pro.cslauncher.core.media.RecordingPlanner
import pro.cslauncher.core.media.RecordingUnsupportedException
import pro.cslauncher.core.settings.AudioSourceChoice
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

data class RecorderState(val recording: Boolean = false, val paused: Boolean = false, val startedAtMs: Long = 0, val lastFile: String? = null, val error: String? = null,
                         /** True while frames come straight from the game renderer (no touch controls in the video). */ val clean: Boolean = false, val notice: String? = null)

/**
 * In-game recorder: MediaProjection → VirtualDisplay → hardware MediaCodec (AVC/HEVC) → MediaMuxer (MP4), with optional
 * AAC audio from game playback capture (API 29+) and/or the microphone. Runs as a mediaProjection foreground service
 * with a visible notification; recording starts only after the user granted the system capture dialog.
 */
class RecorderService : Service() {
    companion object {
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"; const val ACTION_PAUSE = "pause"; const val ACTION_RESUME = "resume"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"; const val EXTRA_WIDTH = "w"; const val EXTRA_HEIGHT = "h"; const val EXTRA_DPI = "dpi"; const val EXTRA_PROFILE = "profile"
        private const val NOTIFICATION_ID = 42
        private const val MIN_FREE_BYTES = 150L * 1024 * 1024
        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var video: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var audio: AudioPipeline? = null
    private var muxer: Muxer? = null
    private var videoThread: Thread? = null
    private var target: File? = null
    private var plan: RecordingPlan? = null
    @Volatile private var running = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val clock = PauseClock()
    private var profileName = ""
    /** Clean mode: the game's render thread blits each frame into the encoder surface (record_gl.c); no VirtualDisplay. */
    @Volatile private var clean = false
    private var displayDpi = 320

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop("stopped")
            ACTION_PAUSE -> if (running && !clock.paused) {
                clock.pause(); if (clean) setCleanTarget(null) else display?.surface = null
                _state.value = _state.value.copy(paused = true); notifyState()
            }
            ACTION_RESUME -> if (running && clock.paused) {
                clock.resume(); if (clean) setCleanTarget(inputSurface) else display?.surface = inputSurface
                _state.value = _state.value.copy(paused = false); notifyState()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = _state.value.paused
        return NotificationCompat.Builder(this, CsApp.CHANNEL_RECORD).setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (paused) "Recording paused" else "Recording Minecraft").setContentText(profileName).setOngoing(true)
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2)).build()
    }
    private fun notifyState() = getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification())

    private fun fail(msg: String) { _state.value = RecorderState(error = msg); release(); stopSelfCompat() }
    private fun stopSelfCompat() { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun start(intent: Intent) {
        if (running) return
        profileName = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        // Must be in the foreground with the mediaProjection type before obtaining the projection (Android 14).
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        if (data == null) return fail("Screen capture permission data missing")
        val dir = Services.dirs.recordings.toFile().apply { mkdirs() }
        if (dir.usableSpace < MIN_FREE_BYTES) return fail("Not enough free storage to record (need at least 150 MB)")

        val settings = Services.settings.state.value.recording
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val p = try { RecordingPlanner.plan(settings, intent.getIntExtra(EXTRA_WIDTH, 1280), intent.getIntExtra(EXTRA_HEIGHT, 720), Services.device.videoEncoders(), Build.VERSION.SDK_INT, mic) }
                catch (e: RecordingUnsupportedException) { return fail(e.message ?: "Recording is not supported on this device") }
        plan = p
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) ?: return fail("Screen capture was not granted")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { handler.post { stop("capture ended by system") } } }, handler)

            val fmt = MediaFormat.createVideoFormat(p.mime, p.width, p.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, p.fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / p.fps)
            }
            val codec = MediaCodec.createByCodecName(p.encoder).also { video = it }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface(); codec.start()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            target = File(dir, "CS_$stamp.mp4")
            val audioPipe = if (p.audio.source != AudioSourceChoice.NONE) AudioPipeline.create(p, mp, clock, mic) else null
            val partPath = File(dir, ".CS_$stamp.mp4.part").absolutePath
            muxer = Muxer(MediaMuxer(partPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), partPath, expectedTracks = if (audioPipe != null) 2 else 1)
            displayDpi = intent.getIntExtra(EXTRA_DPI, 320)
            clean = settings.hideControls && setCleanTarget(inputSurface)
            if (!clean) display = createDisplay(mp, p)
            running = true
            clock.reset()
            videoThread = thread(name = "cs-rec-video") { drainVideo(codec) }
            audio = audioPipe?.also { it.start(muxer!!) }
            _state.value = RecorderState(recording = true, startedAtMs = System.currentTimeMillis(), clean = clean)
            if (clean) handler.postDelayed({ verifyClean() }, 2500)
            p.warnings.forEach { android.util.Log.w("CSRecorder", it) }
        } catch (e: Exception) {
            fail("Recorder could not start: ${e.message}")
        }
    }

    private fun createDisplay(mp: MediaProjection, p: RecordingPlan): VirtualDisplay =
        mp.createVirtualDisplay("CSRecorder", p.width, p.height, displayDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, handler)

    private fun setCleanTarget(surface: android.view.Surface?): Boolean = try {
        NativeBridge.setRecordTarget(surface, plan?.fps ?: 30); true
    } catch (e: Throwable) {   // UnsatisfiedLinkError when the native bridge is not loaded in this process
        android.util.Log.w("CSRecorder", "Clean recording unavailable: $e"); false
    }

    /**
     * Clean mode must prove itself: if the renderer cannot blit into the encoder (status -1) or no frame arrived, switch to
     * screen capture so the user still gets a video, and say so (that video will include the on-screen controls).
     */
    private fun verifyClean() {
        if (!running || !clean) return
        val status = runCatching { NativeBridge.recordStatus() }.getOrDefault(-1)
        if (status == 1 || (status == 0 && clock.paused)) return
        android.util.Log.w("CSRecorder", "Clean recording not possible with this renderer (status $status); using screen capture")
        setCleanTarget(null); clean = false
        val mp = projection; val p = plan
        if (mp != null && p != null) runCatching { display = createDisplay(mp, p).also { if (clock.paused) it.surface = null } }
            .onFailure { return stop("screen capture fallback failed: ${it.message}") }
        _state.value = _state.value.copy(clean = false, notice = "This renderer cannot record a clean video; recording the screen instead (controls will be visible).")
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo(); var track = -1; var lastSpaceCheck = 0L
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer!!.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        val pts = clock.adjust(info.presentationTimeUs)
                        if (pts != null) muxer!!.write(track, buf, info, pts)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastSpaceCheck > 5000) {
                lastSpaceCheck = now
                if ((target?.parentFile?.usableSpace ?: Long.MAX_VALUE) < MIN_FREE_BYTES / 2) handler.post { stop("storage almost full") }
            }
        }
    }

    private fun stop(reason: String) {
        if (!running) { stopSelfCompat(); return }
        running = false
        android.util.Log.i("CSRecorder", "Stopping: $reason")
        if (clean) { setCleanTarget(null); clean = false }   // the game thread lets go of the encoder surface before it is closed
        runCatching { display?.surface = null; video?.signalEndOfInputStream() }
        thread(name = "cs-rec-finish") {
            videoThread?.join(3000)
            audio?.stop()
            val ok = muxer?.finish() == true
            val part = muxer?.path?.let { File(it) }
            release()
            val out = target
            val result = if (ok && part != null && out != null && part.renameTo(out)) out else { part?.delete(); null }
            if (result != null && Services.settings.state.value.recording.saveToGallery) copyToGallery(result)
            if (result != null) Services.activity.add(ActivityItem(ActivityKind.RECORDING, "Recording saved", "${result.name} · ${result.length() / 1048576} MB"), Services.settings.state.value.recentActivityEnabled)
            _state.value = RecorderState(lastFile = result?.absolutePath, error = if (result == null) "The recording could not be finalised ($reason)" else null)
            handler.post { stopSelfCompat() }
        }
    }

    private fun copyToGallery(f: File) {
        if (Build.VERSION.SDK_INT < 29) return   // older versions would need broad storage permission; file stays in the launcher gallery
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CS Launcher Pro")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        }
    }

    private fun release() {
        if (clean) { setCleanTarget(null); clean = false }
        runCatching { display?.release() }; display = null
        runCatching { video?.stop() }; runCatching { video?.release() }; video = null
        runCatching { inputSurface?.release() }; inputSurface = null
        runCatching { projection?.stop() }; projection = null
        audio = null; muxer = null
    }

    override fun onDestroy() { if (running) stop("service destroyed"); super.onDestroy() }
}

/** Removes paused intervals from presentation timestamps so the file plays continuously. Thread-safe. */
class PauseClock {
    @Volatile var paused = false; private set
    private var pausedSinceUs = 0L; private var offsetUs = 0L
    private fun nowUs() = System.nanoTime() / 1000
    @Synchronized fun reset() { paused = false; offsetUs = 0; pausedSinceUs = 0 }
    @Synchronized fun pause() { if (!paused) { paused = true; pausedSinceUs = nowUs() } }
    @Synchronized fun resume() { if (paused) { offsetUs += nowUs() - pausedSinceUs; paused = false } }
    /** null = sample captured during a pause (dropped). */
    @Synchronized fun adjust(ptsUs: Long): Long? = if (paused && ptsUs >= pausedSinceUs) null else ptsUs - offsetUs
}

/** MediaMuxer wrapper: starts once every expected track is known, buffers early samples, keeps timestamps monotonic. */
class Muxer(private val muxer: MediaMuxer, val path: String, private val expectedTracks: Int) {
    private var tracks = 0; private var started = false; private var written = 0
    private val lastPts = HashMap<Int, Long>()
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()
    private val startUs = System.nanoTime() / 1000   // shared time base: audio and video stay aligned

    @Synchronized fun addTrack(f: MediaFormat): Int {
        val t = muxer.addTrack(f); tracks++
        if (tracks == expectedTracks) {
            muxer.start(); started = true
            pending.forEach { (tr, bytes, info) -> writeNow(tr, ByteBuffer.wrap(bytes), info) }; pending.clear()
        }
        return t
    }

    @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) {
        var pts = (ptsUs - startUs).coerceAtLeast(0)
        val last = lastPts[track]; if (last != null && pts <= last) pts = last + 1
        lastPts[track] = pts
        val copy = MediaCodec.BufferInfo().apply { set(0, info.size, pts, info.flags) }
        buf.position(info.offset); buf.limit(info.offset + info.size)
        if (!started) { if (pending.size < 600) pending += Triple(track, ByteArray(info.size).also { buf.get(it) }, copy); return }
        writeNow(track, buf.slice(), copy)
    }

    private fun writeNow(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) { muxer.writeSampleData(track, buf, info); written++ }

    /** @return true when a playable file was produced. */
    @Synchronized fun finish(): Boolean {
        val ok = started && written > 0 && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        return ok
    }
}
