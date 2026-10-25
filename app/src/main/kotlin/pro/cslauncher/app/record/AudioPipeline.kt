package pro.cslauncher.app.record

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import pro.cslauncher.core.media.RecordingPlan
import pro.cslauncher.core.settings.AudioSourceChoice
import kotlin.concurrent.thread

/**
 * PCM capture (game playback capture on API 29+, microphone, or both mixed) encoded to AAC-LC.
 * Timestamps share System.nanoTime() with the video encoder so the muxer can align the tracks.
 */
class AudioPipeline private constructor(private val records: List<AudioRecord>, private val encoder: MediaCodec, private val channels: Int,
                                        private val sampleRate: Int, private val clock: PauseClock) {
    @Volatile private var running = false
    private var worker: Thread? = null

    companion object {
        fun create(p: RecordingPlan, mp: MediaProjection, clock: PauseClock, micGranted: Boolean): AudioPipeline? {
            val a = p.audio
            val mask = if (a.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(a.sampleRate).setChannelMask(mask).build()
            val buf = AudioRecord.getMinBufferSize(a.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096) * 2
            val recs = mutableListOf<AudioRecord>()
            val game = a.source == AudioSourceChoice.GAME_AUDIO || a.source == AudioSourceChoice.GAME_AND_MICROPHONE
            val mic = a.source == AudioSourceChoice.MICROPHONE || a.source == AudioSourceChoice.GAME_AND_MICROPHONE
            if (game && Build.VERSION.SDK_INT >= 29) runCatching { playback(mp, fmt, buf) }.getOrNull()?.let(recs::add)
            if (mic && micGranted) runCatching { micRecord(fmt, buf) }.getOrNull()?.let(recs::add)
            val ready = recs.filter { it.state == AudioRecord.STATE_INITIALIZED }
            recs.filterNot { it in ready }.forEach { it.release() }
            if (ready.isEmpty()) return null
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, a.sampleRate, a.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, a.bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return AudioPipeline(ready, enc, a.channels, a.sampleRate, clock)
        }

        @SuppressLint("MissingPermission") @RequiresApi(29)
        private fun playback(mp: MediaProjection, fmt: AudioFormat, buf: Int): AudioRecord {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            return AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(buf).setAudioPlaybackCaptureConfig(cfg).build()
        }

        @SuppressLint("MissingPermission")   // checked by the caller (micGranted)
        private fun micRecord(fmt: AudioFormat, buf: Int) =
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt).setBufferSizeInBytes(buf).build()
    }

    fun start(muxer: Muxer) {
        running = true
        records.forEach { it.startRecording() }
        encoder.start()
        worker = thread(name = "cs-rec-audio") { loop(muxer) }
    }

    private fun loop(muxer: Muxer) {
        val chunk = 1024 * channels                 // samples per read (1024 frames)
        val main = ShortArray(chunk); val extra = ShortArray(chunk)
        val info = MediaCodec.BufferInfo(); var track = -1
        var framesFed = 0L; val startUs = System.nanoTime() / 1000
        fun drain(untilEos: Boolean) {
            var waits = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); continue }
                if (idx < 0) { if (untilEos && idx == MediaCodec.INFO_TRY_AGAIN_LATER && ++waits < 100) continue else return }
                val out = encoder.getOutputBuffer(idx)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.write(track, out, info, info.presentationTimeUs)
                encoder.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        while (running) {
            val n = records[0].read(main, 0, chunk, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            if (records.size > 1) {
                val m = records[1].read(extra, 0, n, AudioRecord.READ_NON_BLOCKING)
                for (i in 0 until maxOf(m, 0)) main[i] = (main[i] + extra[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            // Timestamp from the sample count (jitter-free), anchored to the shared monotonic clock.
            val pts = clock.adjust(startUs + framesFed * 1_000_000L / sampleRate)
            framesFed += n / channels
            if (pts == null) continue                 // paused: capture is drained but not encoded
            val inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = encoder.getInputBuffer(inIdx)!!; ib.clear()
                val count = minOf(n, ib.remaining() / 2)
                ib.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(main, 0, count)
                encoder.queueInputBuffer(inIdx, 0, count * 2, pts, 0)
            }
            drain(false)
        }
        runCatching {
            val inIdx = encoder.dequeueInputBuffer(50_000)
            if (inIdx >= 0) { encoder.queueInputBuffer(inIdx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(true) }
        }
    }

    fun stop() {
        running = false
        worker?.join(2000)
        records.forEach { runCatching { it.stop() }; it.release() }
        runCatching { encoder.stop() }; encoder.release()
    }
}
