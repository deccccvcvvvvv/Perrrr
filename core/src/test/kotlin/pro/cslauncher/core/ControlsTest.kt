package pro.cslauncher.core

import pro.cslauncher.core.controls.*
import pro.cslauncher.core.settings.*
import kotlin.test.*

class RecordingSink : InputSink {
    val events = mutableListOf<String>()
    var dx = 0f; var dy = 0f; var ax = -1f; var ay = -1f
    override fun key(key: Int, action: Int, mods: Int) { events += "key:$key:" + when (action) { 1 -> "down"; 0 -> "up"; else -> "repeat" } }
    override fun mouseButton(button: Int, pressed: Boolean, mods: Int) { events += "mouse:$button:" + if (pressed) "down" else "up" }
    override fun scroll(dx: Double, dy: Double) { events += "scroll:$dy" }
    override fun cursorDelta(dx: Float, dy: Float) { this.dx += dx; this.dy += dy }
    override fun cursorAbsolute(x: Float, y: Float) { ax = x; ay = y }
    override fun command(action: InputAction) { events += "cmd:${action.describe()}" }
    fun clear() { events.clear(); dx = 0f; dy = 0f }
}

class ControlsTest {
    private val geo = Geometry(2000, 1000, 2f)
    private fun engine(layout: ControlLayout = DefaultLayouts.defaultTouch(), settings: TouchSettings = TouchSettings(), cursorMode: CursorMode = CursorMode.SMART): Pair<TouchEngine, RecordingSink> {
        val s = RecordingSink()
        val cur = DynamicCursor(CursorSettings(mode = cursorMode)).also { it.setBounds(geo.widthPx, geo.heightPx) }
        return TouchEngine(layout, geo, settings, s, cursor = cur) to s
    }
    private fun rect(e: TouchEngine, id: String) = e.visibleElements().first { it.first.id == id }.second

    @Test fun allDefaultLayoutsValidateAndRoundTrip() {
        for (l in DefaultLayouts.all()) {
            assertEquals(emptyList(), ControlLayoutCodec.validate(l), l.name)
            assertEquals(l, ControlLayoutCodec.decode(ControlLayoutCodec.encode(l)))
        }
    }

    @Test fun invalidImportsAreRejected() {
        val bad = DefaultLayouts.defaultTouch().let { it.copy(modes = it.modes + ("game" to it.modes["game"]!! + it.modes["game"]!!.first())) }
        assertFailsWith<LayoutValidationException> { ControlLayoutCodec.decode(ControlLayoutCodec.encode(bad)) }
        assertFailsWith<LayoutValidationException> { ControlLayoutCodec.decode("""{"formatVersion":99,"id":"x","name":"x","modes":{}}""") }
        assertFailsWith<LayoutValidationException> { ControlLayoutCodec.decode("""{"id":"x"}""") }
        val badKey = ControlLayout(id = "k", name = "k", modes = mapOf("game" to listOf(ControlElement("a", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(9999))))))
        assertTrue(ControlLayoutCodec.validate(badKey).any { it.contains("invalid key") })
    }

    @Test fun holdButtonPressAndRelease() {
        val (e, s) = engine(); val r = rect(e, "jump")
        e.onPointerDown(0, r.cx, r.cy, 0); assertEquals(listOf("key:32:down"), s.events)
        assertTrue(e.isElementActive("jump"))
        e.onPointerUp(0, r.cx, r.cy, 100); assertEquals(listOf("key:32:down", "key:32:up"), s.events)
    }

    @Test fun simultaneousJoystickButtonAndCamera() {
        val (e, s) = engine(); val js = rect(e, "move"); val jump = rect(e, "jump")
        e.onPointerDown(0, js.cx, js.cy, 0)
        e.onPointerMove(0, js.cx, js.top - 500f, 10)     // far beyond the ring: forward + sprint
        e.onPointerDown(1, jump.cx, jump.cy, 20)
        e.onPointerDown(2, 1000f, 500f, 30)              // camera finger on empty space
        e.onPointerMove(2, 1100f, 520f, 40)
        assertTrue("key:87:down" in s.events); assertTrue("key:341:down" in s.events); assertTrue("key:32:down" in s.events)
        assertTrue(s.dx > 0f, "camera moved")
        e.onPointerUp(1, jump.cx, jump.cy, 50)             // releasing jump must not affect the joystick
        assertTrue("key:32:up" in s.events); assertFalse("key:87:up" in s.events)
        assertEquals(2, e.activePointerCount)
        e.onPointerUp(0, js.cx, js.cy, 60)
        assertTrue("key:87:up" in s.events); assertTrue("key:341:up" in s.events)
    }

    @Test fun joystickDiagonalDeadzoneAndHysteresis() {
        val (e, s) = engine(); val js = rect(e, "move"); val rad = js.width / 2
        e.onPointerDown(0, js.cx, js.cy, 0)
        e.onPointerMove(0, js.cx + rad * 0.05f, js.cy, 1); assertTrue(s.events.isEmpty(), "inside dead zone")
        e.onPointerMove(0, js.cx + rad * 0.5f, js.cy - rad * 0.5f, 2)   // up-right diagonal
        assertTrue("key:87:down" in s.events && "key:68:down" in s.events)
        s.clear()
        // small wobble across the 22.5° boundary must not flip keys (hysteresis)
        val a = Math.toRadians(45.0 - 25.0); e.onPointerMove(0, js.cx + (rad * 0.6 * Math.sin(a)).toFloat(), js.cy - (rad * 0.6 * Math.cos(a)).toFloat(), 3)
        assertTrue(s.events.isEmpty(), "hysteresis keeps the diagonal: ${s.events}")
    }

    @Test fun pointerCancelAndCancelAllReleaseEverything() {
        val (e, s) = engine(); val sneak = rect(e, "sneak"); val js = rect(e, "move")
        e.onPointerDown(0, sneak.cx, sneak.cy, 0) // toggle on
        e.onPointerUp(0, sneak.cx, sneak.cy, 10)
        e.onPointerDown(1, js.cx, js.cy, 20); e.onPointerMove(1, js.cx, js.top, 21)
        e.onPointerCancel(1); assertTrue("key:87:up" in s.events)
        assertFalse("key:340:up" in s.events, "toggle stays on after an unrelated cancel")
        e.cancelAll(); assertTrue("key:340:up" in s.events, "focus loss releases toggles")
        assertEquals(0, e.activePointerCount)
    }

    @Test fun toggleTapDoubleTapAndRepeat() {
        val (e, s) = engine()
        val sneak = rect(e, "sneak")
        e.onPointerDown(0, sneak.cx, sneak.cy, 0); e.onPointerUp(0, sneak.cx, sneak.cy, 5)
        e.onPointerDown(0, sneak.cx, sneak.cy, 50); e.onPointerUp(0, sneak.cx, sneak.cy, 55)
        assertEquals(listOf("key:340:down", "key:340:up"), s.events); s.clear()
        val inv = rect(e, "inventory"); e.onPointerDown(3, inv.cx, inv.cy, 100); assertEquals(listOf("key:69:down", "key:69:up"), s.events); e.onPointerUp(3, inv.cx, inv.cy, 120)
        s.clear()
        val dt = ControlLayout(id = "d", name = "d", modes = mapOf("game" to listOf(ControlElement("dd", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_F)), behavior = ButtonBehavior.DOUBLE_TAP),
            ControlElement("rep", x = 0.2f, y = 0.2f, actions = listOf(InputAction.Key(Glfw.KEY_BACKSPACE)), behavior = ButtonBehavior.REPEAT, repeatDelayMs = 300, repeatIntervalMs = 50))))
        val (e2, s2) = engine(dt); val d = rect(e2, "dd"); val rp = rect(e2, "rep")
        e2.onPointerDown(0, d.cx, d.cy, 0); e2.onPointerUp(0, d.cx, d.cy, 10); assertTrue(s2.events.isEmpty())
        e2.onPointerDown(0, d.cx, d.cy, 200); assertEquals(listOf("key:70:down", "key:70:up"), s2.events); e2.onPointerUp(0, d.cx, d.cy, 210); s2.clear()
        e2.onPointerDown(1, rp.cx, rp.cy, 1000); e2.tick(1100); assertEquals(listOf("key:259:down"), s2.events)
        e2.tick(1300); e2.tick(1350); e2.tick(1400)
        assertEquals(3, s2.events.count { it == "key:259:repeat" })
        e2.onPointerUp(1, rp.cx, rp.cy, 1410); e2.tick(2000)
        assertEquals("key:259:up", s2.events.last()); assertEquals(3, s2.events.count { it == "key:259:repeat" }, "no repeats after release")
    }

    @Test fun cameraTapUsesAndLongPressBreaks() {
        val (e, s) = engine()
        e.onPointerDown(0, 1000f, 400f, 0); e.onPointerUp(0, 1000f, 400f, 100)
        assertEquals(listOf("mouse:1:down", "mouse:1:up"), s.events); s.clear()
        e.onPointerDown(0, 1000f, 400f, 1000); e.tick(1200); assertTrue(s.events.isEmpty()); e.tick(1400)
        assertEquals(listOf("mouse:0:down"), s.events)
        e.onPointerMove(0, 1050f, 400f, 1500); assertTrue(s.dx > 0, "can aim while breaking")
        e.onPointerUp(0, 1050f, 400f, 1600); assertEquals("mouse:0:up", s.events.last())
    }

    @Test fun lookSettingsSensitivityAndInvert() {
        val (e, s) = engine(settings = TouchSettings(lookSensitivityX = 2f, lookSensitivityY = 0.5f, invertY = true))
        e.onPointerDown(0, 1000f, 400f, 0); e.onPointerMove(0, 1030f, 400f, 10); e.onPointerMove(0, 1040f, 410f, 20)
        assertEquals(80f, s.dx, 0.01f); assertEquals(-5f, s.dy, 0.01f)
    }

    @Test fun menuModeDirectPointingAndSecondFingerRightClick() {
        val (e, s) = engine()
        e.setCursorGrabbed(false)
        assertEquals("inventory", e.mode, "auto mode switch")
        e.onPointerDown(0, 700f, 300f, 0)
        assertEquals(700f, s.ax); assertEquals(300f, s.ay); assertEquals(listOf("mouse:0:down"), s.events)
        e.onPointerMove(0, 720f, 310f, 10); assertEquals(720f, s.ax)
        e.onPointerDown(1, 1200f, 600f, 20); assertEquals("mouse:1:down", s.events.last())
        e.onPointerUp(1, 1200f, 600f, 30); e.onPointerUp(0, 720f, 310f, 40)
        assertEquals(listOf("mouse:0:down", "mouse:1:down", "mouse:1:up", "mouse:0:up"), s.events)
        e.setCursorGrabbed(true); assertEquals("game", e.mode)
    }

    @Test fun touchpadCursorModeIsRelative() {
        val (e, s) = engine(cursorMode = CursorMode.TOUCHPAD)
        e.setCursorGrabbed(false)
        val cur = e.cursor!!; assertEquals(1000f, cur.x)
        e.onPointerDown(0, 100f, 900f, 0); e.onPointerMove(0, 150f, 900f, 10); e.onPointerUp(0, 150f, 900f, 300)
        assertEquals(1050f, s.ax); assertTrue(s.events.isEmpty(), "drag does not click")
        e.onPointerDown(0, 100f, 900f, 400); e.onPointerUp(0, 100f, 900f, 450); assertEquals(listOf("mouse:0:down", "mouse:0:up"), s.events)
    }

    @Test fun grabChangeCancelsCameraGesture() {
        val (e, s) = engine()
        e.onPointerDown(0, 1000f, 400f, 0); e.tick(1000) // long press -> holding left
        e.setCursorGrabbed(false)
        assertEquals("mouse:0:up", s.events.last()); assertEquals(0, e.activePointerCount)
    }

    @Test fun swipeAcrossHotbar() {
        val (e, s) = engine(); val h1 = rect(e, "hotbar1"); val h2 = rect(e, "hotbar2")
        e.onPointerDown(0, h1.cx, h1.cy, 0); e.onPointerMove(0, h2.cx, h2.cy, 10)
        assertEquals(listOf("key:49:down", "key:49:up", "key:50:down", "key:50:up"), s.events)
    }

    @Test fun sharedKeysAreReferenceCounted() {
        val l = ControlLayout(id = "r", name = "r", modes = mapOf("game" to listOf(ControlElement("a", x = 0.2f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_W))), ControlElement("b", x = 0.8f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_W))))))
        val (e, s) = engine(l); val a = rect(e, "a"); val b = rect(e, "b")
        e.onPointerDown(0, a.cx, a.cy, 0); e.onPointerDown(1, b.cx, b.cy, 0); e.onPointerUp(0, a.cx, a.cy, 1)
        assertEquals(listOf("key:87:down"), s.events); e.onPointerUp(1, b.cx, b.cy, 2); assertEquals(listOf("key:87:down", "key:87:up"), s.events)
    }

    @Test fun modifierDependentControlAndModeSwitch() {
        val l = ControlLayout(id = "m", name = "m", modes = mapOf(
            "game" to listOf(ControlElement("shift", x = 0.1f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_LEFT_SHIFT))),
                             ControlElement("shiftq", x = 0.5f, y = 0.5f, actions = listOf(InputAction.Key(Glfw.KEY_Q)), requiresModifier = Glfw.KEY_LEFT_SHIFT),
                             ControlElement("tochat", x = 0.9f, y = 0.5f, actions = listOf(InputAction.SwitchMode("chat")), behavior = ButtonBehavior.TAP)),
            "chat" to listOf(ControlElement("kb", x = 0.5f, y = 0.1f, actions = listOf(InputAction.ShowKeyboard), behavior = ButtonBehavior.TAP))))
        val (e, s) = engine(l); val q = rect(e, "shiftq"); val sh = rect(e, "shift")
        e.onPointerDown(0, q.cx, q.cy, 0); e.onPointerUp(0, q.cx, q.cy, 1); assertFalse(s.events.any { it.startsWith("key:81") }, "modifier not held -> camera, not Q")
        e.onPointerDown(1, sh.cx, sh.cy, 2); e.onPointerDown(2, q.cx, q.cy, 3); assertTrue("key:81:down" in s.events)
        e.cancelAll(); val t = rect(e, "tochat"); e.onPointerDown(0, t.cx, t.cy, 10); assertEquals("chat", e.mode)
        val kb = rect(e, "kb"); e.onPointerDown(1, kb.cx, kb.cy, 20); assertEquals("cmd:Keyboard", s.events.last())
    }

    @Test fun geometryRespectsSafeAreaAndClamps() {
        val g = Geometry(2000, 1000, 2f, insetLeft = 100, paddingPx = 10)
        val r = g.rectOf(ControlElement("x", x = 0f, y = 0f, widthDp = 50f, heightDp = 50f))
        assertTrue(r.left >= 110f && r.top >= 10f)
        val (nx, ny) = g.normalised(r.cx, r.cy); val r2 = g.rectOf(ControlElement("x", x = nx, y = ny, widthDp = 50f, heightDp = 50f))
        assertEquals(r.left, r2.left, 0.01f); assertEquals(r.top, r2.top, 0.01f)
    }

    @Test fun gamepadSharesTrackerWithTouch() {
        val s = RecordingSink(); val tracker = InputStateTracker(s)
        val pad = GamepadEngine(GamepadMapping(), tracker, s, 2f, null)
        pad.onAxes(0f, -1f, 0f, 0f, 0f, 0f); assertEquals(listOf("key:87:down"), s.events)
        tracker.pressKey(Glfw.KEY_W) // touch also holds W
        pad.onAxes(0f, 0f, 0f, 0f, 0f, 0f)
        assertFalse("key:87:up" in s.events, "W still held by touch")
        pad.onAxes(0f, 0f, 1f, 0f, 0f, 0.9f); assertTrue("mouse:0:down" in s.events)
        pad.tick(16); assertTrue(s.dx > 0)
        assertTrue(pad.onButton(PadKeys.A, true)); assertTrue("key:32:down" in s.events)
        pad.releaseAll(); assertTrue("key:32:up" in s.events && "mouse:0:up" in s.events)
    }

    @Test fun editorUndoRedoAndCoalescing() {
        val ed = LayoutEditor(DefaultLayouts.defaultTouch())
        ed.move("game", "jump", 0.5f, 0.5f); ed.move("game", "jump", 0.6f, 0.6f); ed.endGesture()
        ed.move("game", "jump", 0.7f, 0.7f, grid = 0.25f); ed.endGesture()
        assertEquals(0.75f, ed.current.modes["game"]!!.first { it.id == "jump" }.x)
        ed.undo(); assertEquals(0.6f, ed.current.modes["game"]!!.first { it.id == "jump" }.x)
        ed.undo(); assertEquals(0.89f, ed.current.modes["game"]!!.first { it.id == "jump" }.x, "two drags coalesced into one step")
        assertFalse(ed.dirty); ed.redo(); assertTrue(ed.dirty)
        ed.add("game", ControlElement("jump", x = 0.1f, y = 0.1f)); assertTrue(ed.current.modes["game"]!!.any { it.id == "jump-2" })
        ed.delete("game", "jump-2"); ed.resetElement("game", "jump", DefaultLayouts.defaultTouch())
        assertEquals(0.89f, ed.current.modes["game"]!!.first { it.id == "jump" }.x)
        assertEquals(emptyList(), ControlLayoutCodec.validate(ed.current))
    }

    @Test fun dynamicCursorBehaviour() {
        val c = DynamicCursor(CursorSettings(mode = CursorMode.SMART, sensitivity = 2f, trail = true)); c.setBounds(1000, 500)
        assertFalse(c.visible, "hidden while grabbed")
        c.onGrabChanged(false); assertTrue(c.visible); assertEquals(500f, c.x); assertEquals(250f, c.y)
        c.moveRelative(10f, 0f); assertEquals(520f, c.x); assertEquals(1, c.trail.size)
        c.moveAbsolute(5000f, -10f); assertEquals(999f, c.x); assertEquals(0f, c.y)
        c.click(100); assertNotNull(c.clickAnimation(150)); assertNull(c.clickAnimation(1000), "no idle animation")
        c.syncFromGame(10f, 10f); assertEquals(10f, c.x); assertTrue(c.trail.isEmpty())
        val imm = DynamicCursor(CursorSettings(mode = CursorMode.IMMERSIVE)); imm.setBounds(10, 10); imm.onGrabChanged(false); assertFalse(imm.visible)
    }

    @Test fun androidKeyMapping() {
        assertEquals(Glfw.KEY_A, AndroidKeyMap.toGlfw(29)); assertEquals(Glfw.KEY_ESCAPE, AndroidKeyMap.toGlfw(111))
        assertEquals(Glfw.KEY_F1, AndroidKeyMap.toGlfw(131)); assertNull(AndroidKeyMap.toGlfw(4))
        assertEquals(Glfw.MOD_SHIFT or Glfw.MOD_CONTROL, AndroidKeyMap.modsFromMetaState(0x1 or 0x1000))
    }
}
