package pro.cslauncher.core.controls

import pro.cslauncher.core.settings.CursorMode
import pro.cslauncher.core.settings.CursorSettings

/**
 * Dynamic cursor model shared by the overlay renderer and input engines. The position is the real pointer position
 * delivered to the game (cursorAbsolute) and is re-synchronised whenever the game warps the pointer.
 */
class DynamicCursor(var settings: CursorSettings) {
    var width = 1; private set
    var height = 1; private set
    var x = 0f; private set
    var y = 0f; private set
    var grabbed = true; private set
    var externalMouseActive = false
    private var lastClickAt = Long.MIN_VALUE
    private var smX = 0f; private var smY = 0f
    val trail = ArrayDeque<Pair<Float, Float>>()

    fun setBounds(w: Int, h: Int) {
        val first = width <= 1
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        if (first) { x = width / 2f; y = height / 2f } else { x = x.coerceIn(0f, maxOf(0f, width - 1f)); y = y.coerceIn(0f, maxOf(0f, height - 1f)) }
    }

    /** Resolved behaviour for the current state. */
    fun effectiveMode(): CursorMode = when (settings.mode) {
        // Smart: camera capture while the game grabs the pointer; a visible pointer that follows the finger or mouse in menus.
        CursorMode.SMART -> if (grabbed) CursorMode.IMMERSIVE else CursorMode.DESKTOP
        CursorMode.IMMERSIVE -> CursorMode.IMMERSIVE
        else -> if (grabbed) CursorMode.IMMERSIVE else settings.mode
    }

    /**
     * Hidden while the game captures the pointer for camera control. In menus it is shown unless the user chose direct
     * touch without a pointer (IMMERSIVE) - a real mouse always gets a visible pointer.
     */
    val visible: Boolean get() = !grabbed && (externalMouseActive || effectiveMode() != CursorMode.IMMERSIVE)

    /** Virtual mouse = touchpad-style relative pointer in menus; off = touches act directly where the finger is. */
    val virtualMouse: Boolean get() = settings.mode == CursorMode.TOUCHPAD

    fun onGrabChanged(g: Boolean) {
        grabbed = g
        // Minecraft centres the pointer when it releases the mouse; mirror that so nothing jumps.
        if (!g) { x = width / 2f; y = height / 2f }
        trail.clear(); smX = 0f; smY = 0f
    }

    fun moveRelative(dx: Float, dy: Float) {
        var mx = dx * settings.sensitivity; var my = dy * settings.sensitivity
        if (settings.smoothing > 0f) { val a = settings.smoothing.coerceIn(0f, 0.8f); smX = smX * a + mx * (1 - a); smY = smY * a + my * (1 - a); mx = smX; my = smY }
        setPos(x + mx, y + my)
    }
    fun moveAbsolute(nx: Float, ny: Float) = setPos(nx, ny)
    /** The game moved the pointer (glfwSetCursorPos). */
    fun syncFromGame(nx: Float, ny: Float) { x = nx.coerceIn(0f, maxOf(0f, width - 1f)); y = ny.coerceIn(0f, maxOf(0f, height - 1f)); trail.clear() }

    private fun setPos(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, maxOf(0f, width - 1f)); y = ny.coerceIn(0f, maxOf(0f, height - 1f))
        if (settings.trail) { trail.addLast(x to y); while (trail.size > 8) trail.removeFirst() }
    }

    fun click(t: Long) { lastClickAt = t }
    /** 0..1 progress of the click ripple, or null when no animation is running (no idle animation loop). */
    fun clickAnimation(t: Long, durationMs: Long = 220): Float? {
        if (!settings.clickAnimation || lastClickAt == Long.MIN_VALUE) return null
        val p = (t - lastClickAt).toFloat() / durationMs
        return if (p in 0f..1f) p else null
    }
}
