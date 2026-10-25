package pro.cslauncher.core.controls

import kotlinx.serialization.Serializable

/** Android KeyEvent gamepad key codes (stable platform constants). */
object PadKeys {
    const val A = 96; const val B = 97; const val X = 99; const val Y = 100; const val L1 = 102; const val R1 = 103
    const val L2 = 104; const val R2 = 105; const val THUMBL = 106; const val THUMBR = 107; const val START = 108; const val SELECT = 109
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22
}

@Serializable data class GamepadMapping(
    val buttons: Map<Int, InputAction> = mapOf(
        PadKeys.A to InputAction.Key(Glfw.KEY_SPACE), PadKeys.B to InputAction.Key(Glfw.KEY_LEFT_SHIFT),
        PadKeys.X to InputAction.Key(Glfw.KEY_E), PadKeys.Y to InputAction.Key(Glfw.KEY_Q),
        PadKeys.L1 to InputAction.Scroll(1f), PadKeys.R1 to InputAction.Scroll(-1f),
        PadKeys.START to InputAction.Key(Glfw.KEY_ESCAPE), PadKeys.SELECT to InputAction.Key(Glfw.KEY_TAB),
        PadKeys.THUMBL to InputAction.Key(Glfw.KEY_LEFT_CONTROL), PadKeys.THUMBR to InputAction.Key(Glfw.KEY_F5),
        PadKeys.DPAD_UP to InputAction.Key(Glfw.KEY_UP), PadKeys.DPAD_DOWN to InputAction.Key(Glfw.KEY_DOWN),
        PadKeys.DPAD_LEFT to InputAction.Key(Glfw.KEY_LEFT), PadKeys.DPAD_RIGHT to InputAction.Key(Glfw.KEY_RIGHT),
    ),
    val leftTrigger: InputAction = InputAction.Mouse(Glfw.MOUSE_RIGHT),
    val rightTrigger: InputAction = InputAction.Mouse(Glfw.MOUSE_LEFT),
    val deadZone: Float = 0.18f,
    val lookSpeedDpPerMs: Float = 0.9f,
    val menuCursorSpeedDpPerMs: Float = 0.7f,
    val triggerThreshold: Float = 0.5f,
)

/** Translates controller sticks/buttons/triggers into the same tracker used by touch controls. */
class GamepadEngine(var mapping: GamepadMapping, private val tracker: InputStateTracker, private val sink: InputSink, private val density: Float, private val cursor: DynamicCursor?) {
    private val resolver = DirectionResolver()
    private val movementHeld = HashSet<Int>()
    private val buttonHeld = HashSet<Int>()
    private var lt = false; private var rt = false
    private var rx = 0f; private var ry = 0f
    var cursorGrabbed = true

    fun onButton(code: Int, down: Boolean): Boolean {
        val action = mapping.buttons[code] ?: return false
        if (down) { if (!buttonHeld.add(code)) return true; press(action) } else { if (!buttonHeld.remove(code)) return true; release(action) }
        return true
    }
    private fun press(a: InputAction) = when (a) {
        is InputAction.Key -> tracker.pressKey(a.key); is InputAction.Mouse -> tracker.pressButton(a.button)
        is InputAction.Scroll -> sink.scroll(0.0, a.dy.toDouble()); else -> sink.command(a)
    }
    private fun release(a: InputAction) { when (a) { is InputAction.Key -> tracker.releaseKey(a.key); is InputAction.Mouse -> tracker.releaseButton(a.button); else -> {} } }

    fun onAxes(lx: Float, ly: Float, rxIn: Float, ryIn: Float, ltIn: Float, rtIn: Float) {
        val f = DirectionResolver.flags(resolver.sector(lx, ly, mapping.deadZone))
        val want = HashSet<Int>()
        if (f[0]) want += Glfw.KEY_W; if (f[1]) want += Glfw.KEY_S; if (f[2]) want += Glfw.KEY_A; if (f[3]) want += Glfw.KEY_D
        (movementHeld - want).forEach { tracker.releaseKey(it) }; (want - movementHeld).forEach { tracker.pressKey(it) }
        movementHeld.clear(); movementHeld.addAll(want)
        rx = if (kotlin.math.abs(rxIn) > mapping.deadZone) rxIn else 0f
        ry = if (kotlin.math.abs(ryIn) > mapping.deadZone) ryIn else 0f
        val ltNow = ltIn > mapping.triggerThreshold; val rtNow = rtIn > mapping.triggerThreshold
        if (ltNow != lt) { lt = ltNow; if (ltNow) press(mapping.leftTrigger) else release(mapping.leftTrigger) }
        if (rtNow != rt) { rt = rtNow; if (rtNow) press(mapping.rightTrigger) else release(mapping.rightTrigger) }
    }

    /** Call every frame while a controller is active; the right stick moves the camera or the menu cursor. */
    fun tick(dtMs: Long) {
        if (rx == 0f && ry == 0f) return
        val dt = dtMs.coerceIn(0, 50)
        if (cursorGrabbed) sink.cursorDelta(rx * mapping.lookSpeedDpPerMs * density * dt, ry * mapping.lookSpeedDpPerMs * density * dt)
        else cursor?.let { it.moveRelative(rx * mapping.menuCursorSpeedDpPerMs * density * dt, ry * mapping.menuCursorSpeedDpPerMs * density * dt); sink.cursorAbsolute(it.x, it.y) }
    }
    val active get() = rx != 0f || ry != 0f

    fun releaseAll() { movementHeld.forEach { tracker.releaseKey(it) }; movementHeld.clear(); buttonHeld.toList().forEach { onButton(it, false) }; if (lt) release(mapping.leftTrigger); if (rt) release(mapping.rightTrigger); lt = false; rt = false; rx = 0f; ry = 0f; resolver.reset() }
}
