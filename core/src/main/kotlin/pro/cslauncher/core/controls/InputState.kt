package pro.cslauncher.core.controls

/** Destination of translated input (the native GLFW bridge on Android, a recorder in tests). */
interface InputSink {
    fun key(key: Int, action: Int, mods: Int)
    fun mouseButton(button: Int, pressed: Boolean, mods: Int)
    fun scroll(dx: Double, dy: Double)
    fun cursorDelta(dx: Float, dy: Float)
    fun cursorAbsolute(x: Float, y: Float)
    fun command(action: InputAction)
}

/**
 * Reference-counted key/button state shared by touch, gamepad and keyboard paths. Two controls holding the same key
 * produce a single press and a single release; [releaseAll] guarantees nothing stays stuck after interruptions.
 */
class InputStateTracker(private val sink: InputSink) {
    private val keys = HashMap<Int, Int>()
    private val buttons = HashMap<Int, Int>()

    fun mods(): Int {
        var m = 0
        if (isKeyDown(Glfw.KEY_LEFT_SHIFT) || isKeyDown(Glfw.KEY_RIGHT_SHIFT)) m = m or Glfw.MOD_SHIFT
        if (isKeyDown(Glfw.KEY_LEFT_CONTROL) || isKeyDown(Glfw.KEY_RIGHT_CONTROL)) m = m or Glfw.MOD_CONTROL
        if (isKeyDown(Glfw.KEY_LEFT_ALT) || isKeyDown(Glfw.KEY_RIGHT_ALT)) m = m or Glfw.MOD_ALT
        return m
    }
    fun isKeyDown(k: Int) = (keys[k] ?: 0) > 0
    fun isButtonDown(b: Int) = (buttons[b] ?: 0) > 0
    val anyDown get() = keys.isNotEmpty() || buttons.isNotEmpty()

    fun pressKey(k: Int) { val c = keys[k] ?: 0; keys[k] = c + 1; if (c == 0) sink.key(k, Glfw.PRESS, mods()) }
    fun releaseKey(k: Int) {
        val c = keys[k] ?: return
        if (c <= 1) { keys.remove(k); sink.key(k, Glfw.RELEASE, mods()) } else keys[k] = c - 1
    }
    fun repeatKey(k: Int) { if (isKeyDown(k)) sink.key(k, Glfw.REPEAT, mods()) }
    fun tapKey(k: Int) { pressKey(k); releaseKey(k) }

    fun pressButton(b: Int) { val c = buttons[b] ?: 0; buttons[b] = c + 1; if (c == 0) sink.mouseButton(b, true, mods()) }
    fun releaseButton(b: Int) {
        val c = buttons[b] ?: return
        if (c <= 1) { buttons.remove(b); sink.mouseButton(b, false, mods()) } else buttons[b] = c - 1
    }
    fun clickButton(b: Int) { pressButton(b); releaseButton(b) }

    fun releaseAll() {
        buttons.keys.toList().forEach { buttons.remove(it); sink.mouseButton(it, false, 0) }
        keys.keys.toList().forEach { keys.remove(it); sink.key(it, Glfw.RELEASE, 0) }
    }
}

/** Maps a stick vector to movement keys with 8 sectors and angular hysteresis (prevents flicker at sector edges). */
class DirectionResolver(private val hysteresisDeg: Double = 6.0) {
    private var lastSector = -1
    fun reset() { lastSector = -1 }

    /** dx right-positive, dy down-positive (screen coordinates). Returns sector 0..7 (0 = up/forward, clockwise) or -1. */
    fun sector(dx: Float, dy: Float, deadZone: Float): Int {
        val mag = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
        if (mag < deadZone) { lastSector = -1; return -1 }
        var ang = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())); if (ang < 0) ang += 360.0
        val raw = (((ang + 22.5) % 360) / 45).toInt()
        if (lastSector >= 0 && raw != lastSector) {
            val centre = lastSector * 45.0
            var diff = kotlin.math.abs(ang - centre); if (diff > 180) diff = 360 - diff
            if (diff < 22.5 + hysteresisDeg) return lastSector
        }
        lastSector = raw; return raw
    }

    companion object {
        /** Sector → (forward, back, left, right) flags. */
        fun flags(sector: Int): BooleanArray = when (sector) {
            0 -> booleanArrayOf(true, false, false, false); 1 -> booleanArrayOf(true, false, false, true)
            2 -> booleanArrayOf(false, false, false, true); 3 -> booleanArrayOf(false, true, false, true)
            4 -> booleanArrayOf(false, true, false, false); 5 -> booleanArrayOf(false, true, true, false)
            6 -> booleanArrayOf(false, false, true, false); 7 -> booleanArrayOf(true, false, true, false)
            else -> BooleanArray(4)
        }
    }
}
