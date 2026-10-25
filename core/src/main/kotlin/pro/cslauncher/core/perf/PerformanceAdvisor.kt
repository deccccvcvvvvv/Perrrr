package pro.cslauncher.core.perf

import pro.cslauncher.core.settings.PerformancePreset

data class DeviceFacts(val totalRamMb: Int, val is64Bit: Boolean, val androidApi: Int, val cpuCores: Int, val lowRamDevice: Boolean)
data class PresetEffect(val setting: String, val value: String)
data class Recommendation(val title: String, val detail: String)

/** Evidence-based defaults. Never assigns the whole physical RAM; leaves room for Android, native heap and GPU. */
object PerformanceAdvisor {
    fun recommendedHeapMb(d: DeviceFacts, modCount: Int = 0, javaMajor: Int = 17): Int {
        val ceiling = maxHeapMb(d)
        var base = when {
            d.totalRamMb <= 3072 -> 768
            d.totalRamMb <= 4096 -> 1024
            d.totalRamMb <= 6144 -> 1536
            d.totalRamMb <= 8192 -> 2048
            else -> 3072
        }
        if (modCount > 0) base += minOf(1024, modCount * 24)
        if (javaMajor >= 17 && base < 1024 && d.totalRamMb > 3072) base = 1024
        return base.coerceIn(512, ceiling)
    }

    /** Hard ceiling: 32-bit address space limits heap to ~1 GB; otherwise at most ~45% of RAM minus a 1.5 GB reserve. */
    fun maxHeapMb(d: DeviceFacts): Int {
        if (!d.is64Bit) return 1024
        val byFraction = (d.totalRamMb * 0.45).toInt()
        val byReserve = d.totalRamMb - 1536
        return maxOf(512, minOf(byFraction, byReserve, 8192))
    }

    fun presetEffects(p: PerformancePreset): List<PresetEffect> = when (p) {
        PerformancePreset.BATTERY_SAVER -> listOf(PresetEffect("Frame-rate cap", "30 FPS"), PresetEffect("Render scale", "70%"), PresetEffect("Recording preset", "Battery saver"))
        PerformancePreset.BALANCED -> listOf(PresetEffect("Frame-rate cap", "60 FPS"), PresetEffect("Render scale", "85%"), PresetEffect("Recording preset", "Balanced"))
        PerformancePreset.PERFORMANCE -> listOf(PresetEffect("Frame-rate cap", "Unlimited"), PresetEffect("Render scale", "100%"), PresetEffect("Recording preset", "High quality"))
        PerformancePreset.CUSTOM -> emptyList()
    }
    fun presetFrameCap(p: PerformancePreset) = when (p) { PerformancePreset.BATTERY_SAVER -> 30; PerformancePreset.BALANCED -> 60; else -> 0 }
    fun presetRenderScale(p: PerformancePreset) = when (p) { PerformancePreset.BATTERY_SAVER -> 0.7f; PerformancePreset.BALANCED -> 0.85f; else -> 1f }

    fun recommendations(d: DeviceFacts, heapMb: Int, recentFailures: Int, modCount: Int): List<Recommendation> = buildList {
        if (heapMb > maxHeapMb(d)) add(Recommendation("Memory allocation too high", "$heapMb MB exceeds the safe limit of ${maxHeapMb(d)} MB for this device; Android may kill the game."))
        if (!d.is64Bit) add(Recommendation("32-bit device", "Heap is limited to about 1 GB. Prefer older versions and few mods."))
        if (d.lowRamDevice) add(Recommendation("Low-RAM device", "Use the Battery Saver preset and a render scale of 70% or less."))
        if (modCount > 40 && heapMb < 2048) add(Recommendation("Many mods", "$modCount mods are installed; consider at least 2048 MB if the device allows it."))
        if (recentFailures >= 2) add(Recommendation("Repeated launch failures", "Open Diagnostics to see the classified cause before retrying."))
    }
}
