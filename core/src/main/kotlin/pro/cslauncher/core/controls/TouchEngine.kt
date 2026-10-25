package pro.cslauncher.core.controls

import pro.cslauncher.core.settings.CursorMode
import pro.cslauncher.core.settings.TapAction
import pro.cslauncher.core.settings.TouchSettings
import kotlin.math.hypot

data class Geometry(
    val widthPx: Int, val heightPx: Int, val density: Float,
    val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0, val paddingPx: Int = 0,
) {
    val safeLeft get() = insetLeft + paddingPx
    val safeTop get() = insetTop + paddingPx
    val safeWidth get() = (widthPx - insetLeft - insetRight - 2 * paddingPx).coerceAtLeast(1)
    val safeHeight get() = (heightPx - insetTop - insetBottom - 2 * paddingPx).coerceAtLeast(1)

    fun rectOf(e: ControlElement): Rect {
        val w = e.widthDp * density; val h = e.heightDp * density
        val cx = (safeLeft + e.x * safeWidth).coerceIn(safeLeft + w / 2, safeLeft + safeWidth - w / 2)
        val cy = (safeTop + e.y * safeHeight).coerceIn(safeTop + h / 2, safeTop + safeHeight - h / 2)
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    /** Inverse of [rectOf] for the editor: pixel centre → normalised position. */
    fun normalised(cxPx: Float, cyPx: Float): Pair<Float, Float> =
        ((cxPx - safeLeft) / safeWidth).coerceIn(0f, 1f) to ((cyPx - safeTop) / safeHeight).coerceIn(0f, 1f)
}

data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2; val cy get() = (top + bottom) / 2
    val width get() = right - left; val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Visual state for the overlay renderer (read on the UI thread after events). */
data class JoystickVisual(val centerX: Float, val centerY: Float, val knobX: Float, val knobY: Float, val radius: Float)

/**
 * Original multi-touch translation engine. Every pointer is owned by exactly one handler from DOWN to UP/CANCEL,
 * so moving one finger never affects another finger's joystick, button or camera gesture.
 * Not thread-safe: call from one thread (the Android UI thread).
 */
class TouchEngine(
    layout: ControlLayout,
    geometry: Geometry,
    var settings: TouchSettings,
    private val sink: InputSink,
    val tracker: InputStateTracker = InputStateTracker(sink),
    val cursor: DynamicCursor? = null,
) {
    var layout = layout; private set
    var geometry = geometry; private set
    var mode: String = layout.defaultMode; private set
    var cursorGrabbed = true; private set
    var overlayHidden = false
    private val pointers = HashMap<Int, PointerHandler>()
    private val toggled = HashSet<String>()
    private val lastTap = HashMap<String, Long>()
    private val pressedElements = HashMap<String, Int>()
    private var cache: List<Pair<ControlElement, Rect>>? = null
    val joystickVisuals = HashMap<String, JoystickVisual>()
    var onChanged: () -> Unit = {}
    private val slopPx get() = 9f * geometry.density

    fun setLayout(l: ControlLayout) { cancelAll(); layout = l; mode = l.defaultMode; toggled.clear(); invalidate() }
    fun setGeometry(g: Geometry) { if (g != geometry) { cancelAll(); geometry = g; cursor?.setBounds(g.widthPx, g.heightPx); invalidate() } }
    private fun invalidate() { cache = null; onChanged() }

    fun visibleElements(): List<Pair<ControlElement, Rect>> = cache ?: layout.elements(mode).filter {
        !overlayHidden && when (it.visibility) { Visibility.ALWAYS -> true; Visibility.IN_GAME -> cursorGrabbed; Visibility.IN_MENU -> !cursorGrabbed }
    }.map { it to geometry.rectOf(it) }.also { cache = it }

    fun isElementActive(id: String) = (pressedElements[id] ?: 0) > 0 || id in toggled
    val activePointerCount get() = pointers.size

    fun switchMode(m: String) {
        if (m == mode || m !in layout.modes) return
        // Release everything owned by controls of the old mode; keep nothing half-pressed.
        pointers.values.toList().forEach { it.cancel() }; pointers.clear()
        toggled.toList().forEach { id -> layout.elements(mode).firstOrNull { it.id == id }?.let { releaseActions(it) } }
        toggled.clear(); pressedElements.clear(); joystickVisuals.clear()
        mode = m; invalidate()
    }

    /** Called when the game grabs/releases the pointer (glfwSetInputMode CURSOR_DISABLED / NORMAL). */
    fun setCursorGrabbed(grabbed: Boolean) {
        if (grabbed == cursorGrabbed) return
        cursorGrabbed = grabbed
        // Gestures started in the other context must not continue (a menu drag must not become a camera turn).
        pointers.entries.filter { it.value is CameraHandler || it.value is MenuHandler || it.value is SecondaryClickHandler }.map { it.key }.forEach { pointers.remove(it)?.cancel() }
        cursor?.onGrabChanged(grabbed)
        if (layout.autoModes) {
            if (!grabbed && mode == layout.defaultMode && "inventory" in layout.modes) switchMode("inventory")
            else if (grabbed && mode != layout.defaultMode) switchMode(layout.defaultMode)
        }
        invalidate()
    }

    // ---------------- pointer events ----------------
    fun onPointerDown(id: Int, x: Float, y: Float, t: Long) {
        pointers.remove(id)?.cancel()  // stale pointer id: never leave it holding keys
        val hit = visibleElements().lastOrNull { (e, r) -> r.contains(x, y) && modifierOk(e) }
        val h: PointerHandler = when {
            hit != null -> handlerFor(hit.first, hit.second)
            cursorGrabbed -> CameraHandler()
            pointers.values.any { it is MenuHandler } -> SecondaryClickHandler()
            else -> MenuHandler()
        }
        pointers[id] = h
        h.down(x, y, t)
        onChanged()
    }
    fun onPointerMove(id: Int, x: Float, y: Float, t: Long) { pointers[id]?.move(x, y, t) }
    fun onPointerUp(id: Int, x: Float, y: Float, t: Long) { pointers.remove(id)?.up(x, y, t); onChanged() }
    fun onPointerCancel(id: Int) { pointers.remove(id)?.cancel(); onChanged() }

    /** Focus loss, pause, surface destruction, rotation, game exit: release every simulated input. */
    fun cancelAll() {
        pointers.values.toList().forEach { it.cancel() }; pointers.clear()
        toggled.clear(); pressedElements.clear(); joystickVisuals.clear()
        tracker.releaseAll(); onChanged()
    }

    fun tick(t: Long) { pointers.values.toList().forEach { it.tick(t) } }
    val needsTicks get() = pointers.values.any { it.wantsTicks }

    private fun modifierOk(e: ControlElement) = e.requiresModifier?.let { tracker.isKeyDown(it) } ?: true
    private fun handlerFor(e: ControlElement, r: Rect): PointerHandler = when (e.kind) {
        ElementKind.BUTTON -> ButtonHandler(e)
        ElementKind.JOYSTICK -> JoystickHandler(e, r)
        ElementKind.TOUCHPAD -> TouchpadHandler(e)
    }

    // ---------------- action helpers ----------------
    private fun pressActions(e: ControlElement) {
        pressedElements[e.id] = (pressedElements[e.id] ?: 0) + 1
        for (a in e.actions) when (a) {
            is InputAction.Key -> tracker.pressKey(a.key)
            is InputAction.Mouse -> tracker.pressButton(a.button)
            is InputAction.Scroll -> sink.scroll(0.0, a.dy.toDouble())
            is InputAction.SwitchMode -> {}
            else -> sink.command(a)
        }
        e.actions.filterIsInstance<InputAction.SwitchMode>().firstOrNull()?.let { switchMode(it.mode) }
    }
    private fun releaseActions(e: ControlElement) {
        val c = pressedElements[e.id] ?: 0
        if (c <= 1) pressedElements.remove(e.id) else pressedElements[e.id] = c - 1
        for (a in e.actions) when (a) {
            is InputAction.Key -> tracker.releaseKey(a.key)
            is InputAction.Mouse -> tracker.releaseButton(a.button)
            else -> {}
        }
    }
    private fun repeatActions(e: ControlElement) {
        for (a in e.actions) when (a) {
            is InputAction.Key -> tracker.repeatKey(a.key)
            is InputAction.Mouse -> { tracker.releaseButton(a.button); tracker.pressButton(a.button) }
            is InputAction.Scroll -> sink.scroll(0.0, a.dy.toDouble())
            else -> {}
        }
    }

    private fun applyLook(dx: Float, dy: Float, sx: Float, sy: Float, dtMs: Long, state: FloatArray) {
        val s = settings
        var mx = dx * s.lookSensitivityX * sx; var my = dy * s.lookSensitivityY * sy * (if (s.invertY) -1 else 1)
        if (s.acceleration > 0f && dtMs > 0) {
            val speed = hypot(dx, dy) / dtMs / geometry.density   // dp per ms
            val f = 1f + s.acceleration * (speed / 2f).coerceAtMost(2f); mx *= f; my *= f
        }
        if (s.smoothing > 0f) { // state = [sx, sy]; single-pole low-pass, alpha ≤ 0.8 keeps latency low
            val a = s.smoothing.coerceIn(0f, 0.8f)
            state[0] = state[0] * a + mx * (1 - a); state[1] = state[1] * a + my * (1 - a); mx = state[0]; my = state[1]
        }
        sink.cursorDelta(mx, my)
    }

    // ---------------- handlers ----------------
    private abstract inner class PointerHandler {
        open val wantsTicks = false
        abstract fun down(x: Float, y: Float, t: Long)
        open fun move(x: Float, y: Float, t: Long) {}
        abstract fun up(x: Float, y: Float, t: Long)
        abstract fun cancel()
        open fun tick(t: Long) {}
    }

    private inner class ButtonHandler(var e: ControlElement) : PointerHandler() {
        private var nextRepeat = Long.MAX_VALUE
        private var holding = false
        override val wantsTicks get() = e.behavior == ButtonBehavior.REPEAT
        override fun down(x: Float, y: Float, t: Long) = engage(t)
        private fun engage(t: Long) {
            when (e.behavior) {
                ButtonBehavior.HOLD -> { pressActions(e); holding = true }
                ButtonBehavior.REPEAT -> { pressActions(e); holding = true; nextRepeat = t + e.repeatDelayMs }
                ButtonBehavior.TAP -> { pressActions(e); releaseActions(e) }
                ButtonBehavior.TOGGLE -> if (e.id in toggled) { toggled.remove(e.id); releaseActions(e) } else { toggled.add(e.id); pressActions(e) }
                ButtonBehavior.DOUBLE_TAP -> {
                    val last = lastTap[e.id]
                    if (last != null && t - last <= e.doubleTapMs) { lastTap.remove(e.id); pressActions(e); releaseActions(e) } else lastTap[e.id] = t
                }
            }
        }
        private fun disengage() { if (holding) { holding = false; releaseActions(e) }; nextRepeat = Long.MAX_VALUE }
        override fun move(x: Float, y: Float, t: Long) {
            if (!e.swipeable || geometry.rectOf(e).contains(x, y)) return
            val next = visibleElements().lastOrNull { (o, r) -> o.swipeable && o.kind == ElementKind.BUTTON && o.id != e.id && r.contains(x, y) && modifierOk(o) }?.first ?: return
            disengage(); e = next; engage(t); onChanged()
        }
        override fun up(x: Float, y: Float, t: Long) = disengage()
        override fun cancel() = disengage()
        override fun tick(t: Long) {
            if (holding && t >= nextRepeat) { repeatActions(e); nextRepeat = t + e.repeatIntervalMs.coerceAtLeast(16) }
        }
    }

    private inner class JoystickHandler(val e: ControlElement, val r: Rect) : PointerHandler() {
        private val cfg = e.joystick ?: JoystickConfig()
        private var cx = r.cx; private var cy = r.cy
        private val radius = r.width / 2
        private val held = HashSet<Int>()
        private val resolver = DirectionResolver()
        private var vx = 0f; private var vy = 0f; private var lastTick = 0L
        override val wantsTicks get() = cfg.role == JoystickRole.LOOK
        override fun down(x: Float, y: Float, t: Long) {
            if (cfg.floating) { cx = x; cy = y }
            lastTick = t; update(x, y)
        }
        override fun move(x: Float, y: Float, t: Long) = update(x, y)
        private fun update(x: Float, y: Float) {
            var dx = (x - cx) / radius; var dy = (y - cy) / radius
            val mag = hypot(dx, dy)
            if (mag > 1f) { dx /= mag; dy /= mag }   // clamp: finger outside the ring keeps a stable direction
            vx = dx * cfg.sensitivity; vy = dy * cfg.sensitivity
            joystickVisuals[e.id] = JoystickVisual(cx, cy, cx + dx * radius, cy + dy * radius, radius)
            if (cfg.role == JoystickRole.MOVEMENT) {
                val sector = resolver.sector(dx, dy, cfg.deadZone)
                val f = DirectionResolver.flags(sector)
                val want = HashSet<Int>()
                if (f[0]) want += cfg.forward; if (f[1]) want += cfg.back; if (f[2]) want += cfg.left; if (f[3]) want += cfg.right
                if (cfg.sprintThreshold > 0f && f[0] && minOf(1f, mag) >= cfg.sprintThreshold) want += cfg.sprintKey
                (held - want).forEach { tracker.releaseKey(it) }
                (want - held).forEach { tracker.pressKey(it) }
                held.clear(); held.addAll(want)
            }
            onChanged()
        }
        override fun tick(t: Long) {
            val dt = (t - lastTick).coerceIn(0, 50); lastTick = t
            if (cfg.role == JoystickRole.LOOK && hypot(vx, vy) > cfg.deadZone) {
                val speed = 0.9f * geometry.density * dt // px per tick at full deflection
                sink.cursorDelta(vx * speed * settings.lookSensitivityX, vy * speed * settings.lookSensitivityY * (if (settings.invertY) -1 else 1))
            }
        }
        private fun reset() { held.forEach { tracker.releaseKey(it) }; held.clear(); resolver.reset(); vx = 0f; vy = 0f; joystickVisuals.remove(e.id); onChanged() }
        override fun up(x: Float, y: Float, t: Long) = reset()
        override fun cancel() = reset()
    }

    /** Gameplay camera on empty screen space: drag = look, tap = use/place, long press = attack/break. */
    private inner class CameraHandler : PointerHandler() {
        private var lx = 0f; private var ly = 0f; private var sx = 0f; private var sy = 0f
        private var downAt = 0L; private var lastAt = 0L
        private var moved = false; private var longPress = false
        private val smooth = FloatArray(2)
        override val wantsTicks get() = !moved && !longPress
        override fun down(x: Float, y: Float, t: Long) { lx = x; ly = y; sx = x; sy = y; downAt = t; lastAt = t }
        override fun move(x: Float, y: Float, t: Long) {
            if (!moved && hypot(x - sx, y - sy) > slopPx) moved = true
            if (moved || longPress) applyLook(x - lx, y - ly, 1f, 1f, t - lastAt, smooth)
            lx = x; ly = y; lastAt = t
        }
        override fun tick(t: Long) {
            if (settings.longPressBreak && !moved && !longPress && t - downAt >= settings.longPressMs) { longPress = true; tracker.pressButton(Glfw.MOUSE_LEFT) }
        }
        override fun up(x: Float, y: Float, t: Long) {
            if (longPress) { tracker.releaseButton(Glfw.MOUSE_LEFT); return }
            if (!moved && t - downAt < settings.longPressMs) when (settings.tapAction) {
                TapAction.MOUSE_LEFT -> tracker.clickButton(Glfw.MOUSE_LEFT)
                TapAction.MOUSE_RIGHT -> tracker.clickButton(Glfw.MOUSE_RIGHT)
                TapAction.NONE -> {}
            }
        }
        override fun cancel() { if (longPress) tracker.releaseButton(Glfw.MOUSE_LEFT); longPress = false }
    }

    /** Menus (cursor visible): direct pointing by default; relative touchpad when the cursor mode is TOUCHPAD. */
    private inner class MenuHandler : PointerHandler() {
        private val relative get() = cursor?.settings?.mode == CursorMode.TOUCHPAD
        private var lx = 0f; private var ly = 0f; private var sx = 0f; private var sy = 0f; private var downAt = 0L
        private var pressed = false; private var moved = false
        override fun down(x: Float, y: Float, t: Long) {
            lx = x; ly = y; sx = x; sy = y; downAt = t
            if (!relative) { place(x, y); tracker.pressButton(Glfw.MOUSE_LEFT); pressed = true; cursor?.click(t) }
        }
        private fun place(x: Float, y: Float) { val c = cursor; if (c != null) { c.moveAbsolute(x, y); sink.cursorAbsolute(c.x, c.y) } else sink.cursorAbsolute(x, y) }
        override fun move(x: Float, y: Float, t: Long) {
            if (hypot(x - sx, y - sy) > slopPx) moved = true
            if (relative) { val c = cursor; if (c != null) { c.moveRelative(x - lx, y - ly); sink.cursorAbsolute(c.x, c.y) } }
            else place(x, y)
            lx = x; ly = y
        }
        override fun up(x: Float, y: Float, t: Long) {
            if (pressed) { tracker.releaseButton(Glfw.MOUSE_LEFT); pressed = false }
            else if (relative && !moved && t - downAt < 250) { tracker.clickButton(Glfw.MOUSE_LEFT); cursor?.click(t) }
        }
        override fun cancel() { if (pressed) tracker.releaseButton(Glfw.MOUSE_LEFT); pressed = false }
    }

    /** A second finger in a menu performs a right click at the cursor (e.g. split item stacks). */
    private inner class SecondaryClickHandler : PointerHandler() {
        private var pressed = false
        override fun down(x: Float, y: Float, t: Long) { tracker.pressButton(Glfw.MOUSE_RIGHT); pressed = true; cursor?.click(t) }
        override fun up(x: Float, y: Float, t: Long) = cancel()
        override fun cancel() { if (pressed) tracker.releaseButton(Glfw.MOUSE_RIGHT); pressed = false }
    }

    private inner class TouchpadHandler(val e: ControlElement) : PointerHandler() {
        private val cfg = e.touchpad ?: TouchpadConfig()
        private var lx = 0f; private var ly = 0f; private var sx = 0f; private var sy = 0f; private var downAt = 0L; private var lastAt = 0L
        private var moved = false; private val smooth = FloatArray(2)
        override fun down(x: Float, y: Float, t: Long) { lx = x; ly = y; sx = x; sy = y; downAt = t; lastAt = t; pressedElements[e.id] = 1 }
        override fun move(x: Float, y: Float, t: Long) {
            if (hypot(x - sx, y - sy) > slopPx) moved = true
            val dx = (x - lx) * cfg.sensitivity; val dy = (y - ly) * cfg.sensitivity
            if (cursorGrabbed) applyLook(dx, dy, 1f, 1f, t - lastAt, smooth)
            else { val c = cursor; if (c != null) { c.moveRelative(dx, dy); sink.cursorAbsolute(c.x, c.y) } }
            lx = x; ly = y; lastAt = t
        }
        override fun up(x: Float, y: Float, t: Long) {
            pressedElements.remove(e.id)
            if (cfg.tapToClick && !moved && t - downAt < 250) { tracker.clickButton(if (cursorGrabbed) Glfw.MOUSE_RIGHT else Glfw.MOUSE_LEFT); cursor?.click(t) }
        }
        override fun cancel() { pressedElements.remove(e.id) }
    }
}
