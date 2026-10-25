package pro.cslauncher.app.game

import android.view.Surface

/**
 * JNI surface of libcsglfw (GLFW implementation shared with the game's LWJGL) and libcsbridge (in-process VM).
 * Callback names/signatures are looked up by native code (host_callbacks.c) and must not change.
 */
object NativeBridge {
    init { System.loadLibrary("csglfw"); System.loadLibrary("csbridge") }

    interface Listener {
        fun onCursorMode(grabbed: Boolean) {}
        fun onCursorPos(x: Double, y: Double) {}
        fun onSetClipboard(text: String) {}
        fun onGetClipboard(): String? = null
        fun onLogLine(line: String) {}
        fun onJvmExit(code: Int) {}
        fun onWindowReady(width: Int, height: Int) {}
    }
    @Volatile var listener: Listener? = null

    // ---- surface & input (UI thread) ----
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int, renderWidth: Int, renderHeight: Int)
    @JvmStatic external fun releaseSurface(timeoutMs: Int): Boolean
    @JvmStatic external fun sendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic external fun sendChar(codepoint: Int, mods: Int)
    @JvmStatic external fun sendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic external fun sendCursorPos(x: Double, y: Double)
    @JvmStatic external fun sendCursorDelta(dx: Double, dy: Double)
    @JvmStatic external fun sendScroll(dx: Double, dy: Double)
    @JvmStatic external fun sendFocus(focused: Boolean)
    @JvmStatic external fun requestClose()

    // ---- VM ----
    @JvmStatic external fun redirectStdio(logPath: String): Boolean
    @JvmStatic external fun setEnv(key: String, value: String)
    @JvmStatic external fun chdir(dir: String): Boolean
    @JvmStatic external fun updateLinkerPath(path: String): Boolean
    /** Returns null on success, otherwise "path: reason" for the first required library that failed. */
    @JvmStatic external fun preloadLibraries(paths: Array<String>, required: BooleanArray): String?
    @JvmStatic external fun launchJvm(libjvm: String, options: Array<String>, mainClass: String, args: Array<String>, stackMb: Int): Int

    // ---- callbacks from native threads ----
    @JvmStatic fun onCursorMode(grabbed: Boolean) { listener?.onCursorMode(grabbed) }
    @JvmStatic fun onCursorPos(x: Double, y: Double) { listener?.onCursorPos(x, y) }
    @JvmStatic fun onSetClipboard(text: String) { listener?.onSetClipboard(text) }
    @JvmStatic fun onGetClipboard(): String? = listener?.onGetClipboard()
    @JvmStatic fun onLogLine(line: String) { listener?.onLogLine(line) }
    @JvmStatic fun onJvmExit(code: Int) { listener?.onJvmExit(code) }
    @JvmStatic fun onWindowReady(width: Int, height: Int) { listener?.onWindowReady(width, height) }
}
