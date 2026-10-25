package pro.cslauncher.app.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import pro.cslauncher.app.Services
import pro.cslauncher.core.install.ResolvedVersion
import pro.cslauncher.core.media.EncoderCapability
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.renderer.RendererEnvironment

/** Real capability detection from Android system services; nothing here is assumed or hard-coded per device. */
class AndroidDevice(private val ctx: Context) {
    private val am = ctx.getSystemService(ActivityManager::class.java)

    /** OpenGL ES version advertised by the device, e.g. 32 for ES 3.2. */
    val glesVersion: Int by lazy {
        val v = am.deviceConfigurationInfo.reqGlEsVersion
        (v shr 16) * 10 + (v and 0xffff).coerceAtMost(9)
    }

    /** Vulkan API version from the platform feature flag, or null if the device declares no Vulkan support. */
    val vulkanApi: String? by lazy {
        val f = ctx.packageManager.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION } ?: return@lazy null
        val v = f.version
        if (v == 0) null else "${v shr 22}.${(v shr 12) and 0x3ff}.${v and 0xfff}"
    }

    fun facts(): DeviceFacts {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceFacts((mi.totalMem / 1048576).toInt(), Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Services.abi.contains("64"),
            Build.VERSION.SDK_INT, Runtime.getRuntime().availableProcessors(), am.isLowRamDevice)
    }

    fun availableMemoryMb(): Int { val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }; return (mi.availMem / 1048576).toInt() }

    fun rendererEnvironment(rv: ResolvedVersion) = RendererEnvironment(
        platform = Services.platform, androidApi = Build.VERSION.SDK_INT, glesVersion = glesVersion, vulkanApi = vulkanApi,
        libraries = Services.components.availableLibraries(Services.abi), gameVersionId = rv.meta.id,
        gameUsesLwjgl3 = rv.meta.usesLwjgl3, gameNeedsGl32Core = needsGl32Core(rv),
        gameUsesSdl = pro.cslauncher.core.component.LwjglCompat.usesSdl(rv.libraries.map { it.library.coordinate }),
    )

    /** Minecraft 1.17 (21w10a) moved to the OpenGL 3.2 core profile; it is also the first version requiring Java 16+. */
    private fun needsGl32Core(rv: ResolvedVersion): Boolean =
        (rv.meta.javaMajor ?: 8) >= 16 || (rv.meta.releaseTime ?: "") >= "2021-03-10"

    /** Video encoders as reported by MediaCodecList (only AVC/HEVC are offered). */
    fun videoEncoders(): List<EncoderCapability> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { info -> info.supportedTypes.filter { it == "video/avc" || it == "video/hevc" }.map { info to it } }
        .mapNotNull { (info, mime) ->
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@mapNotNull null
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
            EncoderCapability(mime, info.name, hw, caps.supportedWidths.upper, caps.supportedHeights.upper, caps.widthAlignment, caps.heightAlignment,
                caps.bitrateRange.lower..caps.bitrateRange.upper,
                maxFps = { w, h -> runCatching { if (caps.isSizeSupported(w, h)) caps.getSupportedFrameRatesFor(w, h).upper else null }.getOrNull() })
        }

    @Suppress("unused") private fun level(info: MediaCodecInfo) = info.name
}
