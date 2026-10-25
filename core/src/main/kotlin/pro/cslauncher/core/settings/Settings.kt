package pro.cslauncher.core.settings

import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path

/** PIXEL is the blocky, Pocket-Edition-inspired default; the earlier smooth themes stay available. */
@Serializable enum class ThemeChoice { PIXEL, DARK_GRAPHITE, GLASSMORPHISM }
@Serializable enum class PerformancePreset { BATTERY_SAVER, BALANCED, PERFORMANCE, CUSTOM }
@Serializable enum class CursorMode { DESKTOP, TOUCHPAD, IMMERSIVE, SMART }
@Serializable enum class CursorShape { ARROW, CIRCLE, CROSSHAIR, DOT }
@Serializable enum class AudioSourceChoice { NONE, MICROPHONE, GAME_AUDIO, GAME_AND_MICROPHONE }
@Serializable enum class RecordingQuality { LOW, BALANCED, HIGH, CUSTOM }
@Serializable enum class RecordingResolution { P480, P720, P1080, NATIVE }
@Serializable enum class TapAction { NONE, MOUSE_LEFT, MOUSE_RIGHT }

@Serializable data class CursorSettings(
    val mode: CursorMode = CursorMode.SMART, val shape: CursorShape = CursorShape.ARROW, val sizeDp: Float = 22f, val opacity: Float = 0.95f,
    val colorArgb: Long = 0xFFF0F1F5, val sensitivity: Float = 1.0f, val smoothing: Float = 0f, val clickAnimation: Boolean = true,
    val trail: Boolean = false, val selectionHighlight: Boolean = true, val externalMouse: Boolean = true,
)

/** Virtual mouse on = touchpad pointer in menus; off = direct touch (pointer shown at the finger unless hidden). */
fun CursorSettings.withVirtualMouse(on: Boolean): CursorSettings =
    if (on) copy(mode = CursorMode.TOUCHPAD) else if (mode == CursorMode.TOUCHPAD) copy(mode = CursorMode.SMART) else this
/** Direct-touch only: whether the pointer is drawn where the finger touches (SMART) or not at all (IMMERSIVE). */
fun CursorSettings.withPointerOnTouch(show: Boolean): CursorSettings = when {
    mode == CursorMode.TOUCHPAD -> this
    show -> copy(mode = if (mode == CursorMode.IMMERSIVE) CursorMode.SMART else mode)
    else -> copy(mode = CursorMode.IMMERSIVE)
}
val CursorSettings.pointerOnTouch: Boolean get() = mode != CursorMode.IMMERSIVE

@Serializable data class TouchSettings(
    val lookSensitivityX: Float = 1.0f, val lookSensitivityY: Float = 1.0f, val invertY: Boolean = false, val smoothing: Float = 0f,
    val acceleration: Float = 0f, val tapAction: TapAction = TapAction.MOUSE_RIGHT, val longPressBreak: Boolean = true, val longPressMs: Int = 320,
    val hideOverlayWithPhysicalInput: Boolean = true, val vibrateOnPress: Boolean = false, val safeAreaPaddingDp: Float = 8f,
    /** Global multipliers applied on top of every control's own opacity / size (live, from the in-game menu). */
    val controlsOpacity: Float = 1f, val controlsScale: Float = 1f,
    /** Small always-available tab at the top edge that opens the in-game menu, independent of the layout. */
    val showMenuHandle: Boolean = true,
    /** Frame-rate / memory read-out in the corner while playing. */
    val showFpsHud: Boolean = false,
)

@Serializable data class RecordingSettings(
    val resolution: RecordingResolution = RecordingResolution.P720, val fps: Int = 30, val quality: RecordingQuality = RecordingQuality.BALANCED,
    val customBitrateKbps: Int = 6000, val preferHevc: Boolean = false, val audio: AudioSourceChoice = AudioSourceChoice.GAME_AUDIO,
    val saveToGallery: Boolean = false, val showOverlay: Boolean = true, val overlayX: Float = 0.02f, val overlayY: Float = 0.08f,
)

@Serializable data class LauncherSettings(
    val schemaVersion: Int = LauncherSettings.SCHEMA,
    val theme: ThemeChoice = ThemeChoice.PIXEL,
    /** Animated pixel landscape behind the launcher (paused when hidden, static with reduced motion). */
    val animatedBackground: Boolean = true,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val downloadConcurrency: Int = 6,
    val showSnapshots: Boolean = false,
    val showHistorical: Boolean = false,
    val recentActivityEnabled: Boolean = true,
    val defaultRenderer: String = "auto",
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val frameRateLimit: Int = 0,              // 0 = unlimited (game setting is authoritative)
    val touch: TouchSettings = TouchSettings(),
    val cursor: CursorSettings = CursorSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val msaClientId: String = "",             // Azure app (client) id approved for Minecraft services; never a secret
    val verboseDiagnostics: Boolean = false,
    val keepLauncherLogsDays: Int = 14,
) {
    companion object {
        const val SCHEMA = 2
        /** v1 → v2: the redesigned pixel UI becomes the theme once; users can switch back in Settings. */
        fun migrate(s: LauncherSettings): LauncherSettings = if (s.schemaVersion >= SCHEMA) s else s.copy(schemaVersion = SCHEMA, theme = ThemeChoice.PIXEL)
    }
}

class SettingsStore(private val file: Path) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    private fun read(): LauncherSettings = runCatching {
        if (!Files.isRegularFile(file)) LauncherSettings() else LauncherSettings.migrate(CsJson.decodeFromString(LauncherSettings.serializer(), pro.cslauncher.core.util.FsUtil.readText(file)))
    }.getOrElse { e ->
        CsLog.w("Settings", "Settings file unreadable, keeping a copy and using defaults: ${e.message}")
        runCatching { Files.copy(file, file.resolveSibling("settings.corrupt-${System.currentTimeMillis()}.json")) }
        LauncherSettings()
    }

    /** Re-reads the file: the launcher and the game process each hold a store and both may save (e.g. the in-game menu). */
    @Synchronized fun reload() { _state.value = read() }

    @Synchronized fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_state.value)
        FsUtil.writeAtomic(file, CsJson.encodeToString(LauncherSettings.serializer(), next))
        _state.value = next
    }
}
