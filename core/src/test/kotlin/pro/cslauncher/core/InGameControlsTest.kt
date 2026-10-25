package pro.cslauncher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.cslauncher.core.controls.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.renderer.*
import pro.cslauncher.core.settings.*
import java.nio.file.Files
import kotlin.test.*

/** Pointer state at game start, the virtual-mouse switch, in-game menu access and the MobileGlues config. */
class InGameControlsTest {
    private val geo = Geometry(2000, 1000, 2f)
    private fun engine(mode: CursorMode = CursorMode.SMART): Triple<TouchEngine, RecordingSink, DynamicCursor> {
        val s = RecordingSink()
        val cur = DynamicCursor(CursorSettings(mode = mode)).also { it.setBounds(geo.widthPx, geo.heightPx) }
        return Triple(TouchEngine(DefaultLayouts.defaultTouch(), geo, TouchSettings(), s, cursor = cur), s, cur)
    }

    @Test fun titleScreenStartsWithAFreePointerAndDirectTouch() {
        // The game window starts with a normal cursor (title screen) and never reports it; GameActivity applies that
        // state at start. A tap must then click where the finger is - not turn the camera and click the screen centre.
        val (e, s, cur) = engine()
        e.setCursorGrabbed(false)
        assertTrue(cur.visible, "pointer visible on the title screen")
        assertEquals("inventory", e.mode)
        e.onPointerDown(0, 333f, 222f, 0); e.onPointerUp(0, 333f, 222f, 40)
        assertEquals(333f, s.ax); assertEquals(222f, s.ay)
        assertEquals(listOf("mouse:0:down", "mouse:0:up"), s.events)
        assertEquals(0f, s.dx); assertEquals(0f, s.dy)
    }

    @Test fun virtualMouseSwitchAndPointerVisibility() {
        val base = CursorSettings(mode = CursorMode.SMART)
        val on = base.withVirtualMouse(true)
        assertEquals(CursorMode.TOUCHPAD, on.mode)
        assertEquals(CursorMode.SMART, on.withVirtualMouse(false).mode)
        assertEquals(CursorMode.IMMERSIVE, base.withPointerOnTouch(false).mode)
        assertFalse(base.withPointerOnTouch(false).pointerOnTouch)
        assertEquals(CursorMode.SMART, base.withPointerOnTouch(false).withPointerOnTouch(true).mode)
        assertEquals(CursorMode.TOUCHPAD, on.withPointerOnTouch(false).mode, "pointer option does not affect the virtual mouse")

        val c = DynamicCursor(base.withPointerOnTouch(false)); c.setBounds(100, 100); c.onGrabChanged(false)
        assertFalse(c.visible, "direct touch without pointer")
        c.externalMouseActive = true
        assertTrue(c.visible, "a real mouse always shows the pointer")
        c.onGrabChanged(true); assertFalse(c.visible, "never while the game captures the mouse")
        c.settings = on; c.externalMouseActive = false; c.onGrabChanged(false)
        assertTrue(c.virtualMouse); assertTrue(c.visible)
    }

    @Test fun switchingToVirtualMouseLiveMakesMenuTouchesRelative() {
        val (e, s, cur) = engine()
        e.setCursorGrabbed(false)
        cur.settings = cur.settings.withVirtualMouse(true)          // what the in-game menu toggle does
        e.onPointerDown(0, 100f, 900f, 0); e.onPointerMove(0, 160f, 900f, 10); e.onPointerUp(0, 160f, 900f, 300)
        assertEquals(1060f, s.ax, "pointer moved by the drag distance from the centre, not to the finger")
        assertTrue(s.events.isEmpty(), "a drag does not click")
    }

    @Test fun menusAlwaysOfferTheGameMenuAndTheMouseSwitch() {
        for (l in DefaultLayouts.all()) {
            for (m in listOf("game", "inventory", "chat")) assertTrue(l.elements(m).any { InputAction.LauncherMenu in it.actions }, "${l.name}/$m has a menu button")
            assertTrue(l.elements("inventory").any { InputAction.ToggleVirtualMouse in it.actions }, "${l.name} inventory has the mouse switch")
            assertEquals(emptyList(), ControlLayoutCodec.validate(l))
        }
        val (e, s, _) = engine()
        e.setCursorGrabbed(false)
        val r = e.visibleElements().first { it.first.id == "inv_mouse" }.second
        e.onPointerDown(0, r.cx, r.cy, 0); e.onPointerUp(0, r.cx, r.cy, 30)
        assertEquals(listOf("cmd:Virtual mouse"), s.events)
        val json = ControlLayoutCodec.encode(DefaultLayouts.defaultTouch())
        assertTrue("\"virtual_mouse\"" in json, "serialised with a stable name")
    }

    @Test fun newTouchSettingsHaveSafeDefaultsAndOldFilesStillLoad() {
        val t = TouchSettings()
        assertEquals(1f, t.controlsOpacity); assertEquals(1f, t.controlsScale); assertTrue(t.showMenuHandle); assertFalse(t.showFpsHud)
        val dir = Files.createTempDirectory("cs-settings")
        val f = dir.resolve("settings.json")
        Files.writeString(f, """{"schemaVersion":2,"touch":{"lookSensitivityX":1.5}}""")
        val store = SettingsStore(f)
        assertEquals(1.5f, store.state.value.touch.lookSensitivityX); assertTrue(store.state.value.touch.showMenuHandle)
        // Another process saves (the in-game menu); reload picks it up instead of keeping a stale copy.
        SettingsStore(f).update { it.copy(touch = it.touch.copy(controlsOpacity = 0.4f)) }
        assertEquals(1f, store.state.value.touch.controlsOpacity)
        store.reload(); assertEquals(0.4f, store.state.value.touch.controlsOpacity)
    }

    private fun mgEnv(sdl: Boolean) = RendererEnvironment(PlatformInfo.android("arm64-v8a", "14"), 34, 32, "1.3.0",
        mapOf("libmobileglues.so" to Files.createTempFile("libmobileglues", ".so")), if (sdl) "26.3" else "1.21.11", true, true, gameUsesSdl = sdl)
    private fun cfg(c: RendererConfig) = Json.parseToJsonElement(c.env.getValue(MobileGluesConfig.ENV)).jsonObject

    @Test fun mobileGluesConfigEnablesErrorFilteringFor263() {
        val mg = MobileGluesBackend()
        val sdl = cfg(mg.configure(mgEnv(true), emptyMap()))
        assertEquals(2, sdl.getValue("enableNoError").jsonPrimitive.int, "26.3+: partial error filtering (level 1)")
        assertEquals(0, sdl.getValue("enableANGLE").jsonPrimitive.int)
        assertEquals(0, sdl.getValue("customGLVersion").jsonPrimitive.int, "MobileGlues default GL version")
        assertEquals(32, sdl.getValue("maxGlslCacheSize").jsonPrimitive.int)
        val old = cfg(mg.configure(mgEnv(false), emptyMap()))
        assertEquals(0, old.getValue("enableNoError").jsonPrimitive.int, "older versions keep the behaviour that already worked")
        val custom = cfg(mg.configure(mgEnv(true), mapOf("errorFilter" to "off", "glVersion" to "46", "glslCache" to "0", "dsa" to "1")))
        assertEquals(1, custom.getValue("enableNoError").jsonPrimitive.int)
        assertEquals(46, custom.getValue("customGLVersion").jsonPrimitive.int)
        assertEquals(0, custom.getValue("maxGlslCacheSize").jsonPrimitive.int)
        assertEquals(1, custom.getValue("enableExtDirectStateAccess").jsonPrimitive.int)
        val junk = cfg(mg.configure(mgEnv(true), mapOf("errorFilter" to "bogus", "glVersion" to "99")))
        assertEquals(2, junk.getValue("enableNoError").jsonPrimitive.int, "invalid values fall back to the defaults")
        assertEquals(0, junk.getValue("customGLVersion").jsonPrimitive.int)
    }
}
