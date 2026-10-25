package pro.cslauncher.core.media

import pro.cslauncher.core.settings.*

/** Measured encoder capabilities (from MediaCodecList on Android). */
data class EncoderCapability(
    val mime: String, val name: String, val hardware: Boolean, val maxWidth: Int, val maxHeight: Int,
    val widthAlignment: Int, val heightAlignment: Int, val bitrateRange: IntRange,
    val maxFps: (Int, Int) -> Double?,       // achievable frame rate for a size, null if unsupported
)

data class AudioPlan(val source: AudioSourceChoice, val sampleRate: Int = 44100, val channels: Int = 2, val bitrate: Int = 128_000)
data class RecordingPlan(val mime: String, val encoder: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int, val audio: AudioPlan, val warnings: List<String>)
class RecordingUnsupportedException(message: String) : Exception(message)

object RecordingPlanner {
    const val AVC = "video/avc"; const val HEVC = "video/hevc"

    fun plan(s: RecordingSettings, surfaceW: Int, surfaceH: Int, encoders: List<EncoderCapability>, androidApi: Int, micPermission: Boolean): RecordingPlan {
        val warnings = mutableListOf<String>()
        val wantHevc = s.preferHevc && encoders.any { it.mime == HEVC && it.hardware }
        if (s.preferHevc && !wantHevc) warnings += "HEVC hardware encoder not available; using H.264"
        val pool = encoders.filter { it.mime == (if (wantHevc) HEVC else AVC) }.sortedByDescending { it.hardware }
        val enc = pool.firstOrNull() ?: encoders.firstOrNull { it.mime == AVC } ?: throw RecordingUnsupportedException("No H.264 or HEVC video encoder is available on this device")
        if (!enc.hardware) warnings += "Only a software encoder is available; recording will reduce game performance noticeably"

        val landscape = surfaceW >= surfaceH
        val long0 = maxOf(surfaceW, surfaceH); val short0 = minOf(surfaceW, surfaceH)
        val targetShort = when (s.resolution) { RecordingResolution.P480 -> 480; RecordingResolution.P720 -> 720; RecordingResolution.P1080 -> 1080; RecordingResolution.NATIVE -> short0 }.coerceAtMost(short0)
        var shortSide = targetShort
        var w = 0; var h = 0; var fps = s.fps.coerceIn(15, 60)
        while (shortSide >= 240) {
            val longSide = (long0.toLong() * shortSide / short0).toInt()
            val aw = align(if (landscape) longSide else shortSide, maxOf(16, enc.widthAlignment)); val ah = align(if (landscape) shortSide else longSide, maxOf(16, enc.heightAlignment))
            if (aw <= enc.maxWidth && ah <= enc.maxHeight) {
                val achievable = enc.maxFps(aw, ah)
                if (achievable != null) {
                    if (achievable + 0.5 < fps) { if (fps > 30 && achievable >= 29.5) { warnings += "$fps FPS is not sustainable at ${aw}x$ah; using 30 FPS"; fps = 30 } else { shortSide -= 120; continue } }
                    w = aw; h = ah; break
                }
            }
            shortSide -= 120
        }
        if (w == 0) throw RecordingUnsupportedException("The encoder ${enc.name} supports none of the requested sizes")
        if (shortSide < targetShort) warnings += "Resolution reduced to ${minOf(w, h)}p by encoder limits"

        val bpp = when (s.quality) { RecordingQuality.LOW -> 0.05; RecordingQuality.BALANCED -> 0.09; RecordingQuality.HIGH -> 0.15; RecordingQuality.CUSTOM -> 0.0 }
        var bitrate = if (s.quality == RecordingQuality.CUSTOM) s.customBitrateKbps * 1000 else (w.toLong() * h * fps * bpp * (if (enc.mime == HEVC) 0.7 else 1.0)).toInt()
        bitrate = bitrate.coerceIn(maxOf(enc.bitrateRange.first, 500_000), enc.bitrateRange.last)

        var audio = s.audio
        if ((audio == AudioSourceChoice.GAME_AUDIO || audio == AudioSourceChoice.GAME_AND_MICROPHONE) && androidApi < 29) {
            warnings += "Game audio capture needs Android 10+"; audio = if (audio == AudioSourceChoice.GAME_AND_MICROPHONE) AudioSourceChoice.MICROPHONE else AudioSourceChoice.NONE
        }
        if (audio != AudioSourceChoice.NONE && !micPermission) {
            // Android requires RECORD_AUDIO for both microphone and playback capture.
            warnings += "Audio permission not granted; recording without audio"; audio = AudioSourceChoice.NONE
        }
        if (fps == 60) warnings += "60 FPS recording only contains new frames if the game itself renders at 60 FPS"
        return RecordingPlan(enc.mime, enc.name, w, h, fps, bitrate, AudioPlan(audio), warnings)
    }

    private fun align(v: Int, a: Int) = (v / a) * a
    /** Rough bytes per second, for storage warnings. */
    fun bytesPerSecond(p: RecordingPlan) = (p.bitrate + if (p.audio.source != AudioSourceChoice.NONE) p.audio.bitrate else 0) / 8L
}
