package pro.cslauncher.app.game

import pro.cslauncher.core.controls.Glfw
import pro.cslauncher.core.controls.InputAction
import pro.cslauncher.core.controls.InputSink

/**
 * Forwards the touch engine's translated input to the GLFW event queue. Absolute positions arrive in overlay pixels
 * and are converted to the game's framebuffer (render-scale) coordinates; camera deltas are passed unscaled so look
 * sensitivity does not change with render scale. Commands are handled by the activity.
 */
class GameInputSink(private val scale: () -> Float, private val onCommand: (InputAction) -> Unit) : InputSink {
    override fun key(key: Int, action: Int, mods: Int) = NativeBridge.sendKey(key, Glfw.scancodeFor(key), action, mods)
    override fun mouseButton(button: Int, pressed: Boolean, mods: Int) = NativeBridge.sendMouseButton(button, if (pressed) Glfw.PRESS else Glfw.RELEASE, mods)
    override fun scroll(dx: Double, dy: Double) = NativeBridge.sendScroll(dx, dy)
    override fun cursorDelta(dx: Float, dy: Float) = NativeBridge.sendCursorDelta(dx.toDouble(), dy.toDouble())
    override fun cursorAbsolute(x: Float, y: Float) { val s = scale(); NativeBridge.sendCursorPos((x * s).toDouble(), (y * s).toDouble()) }
    override fun command(action: InputAction) = onCommand(action)
}
