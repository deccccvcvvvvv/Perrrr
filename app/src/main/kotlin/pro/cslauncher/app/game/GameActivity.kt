package pro.cslauncher.app.game

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.cslauncher.app.Services
import pro.cslauncher.app.record.RecorderService
import pro.cslauncher.core.controls.*
import pro.cslauncher.core.launch.JvmOptionNormalizer
import pro.cslauncher.core.launch.LaunchPlan
import pro.cslauncher.core.settings.AudioSourceChoice
import pro.cslauncher.core.settings.LauncherSettings
import pro.cslauncher.core.util.CsJson
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hosts the game VM in the ":game" process: a SurfaceView for the GLFW/EGL window plus the touch overlay.
 * The launcher process prepares and verifies everything and writes plan.json; this activity only executes it.
 */
class GameActivity : ComponentActivity(), SurfaceHolder.Callback, NativeBridge.Listener {
    companion object {
        const val EXTRA_SESSION_DIR = "sessionDir"; const val EXTRA_ACCOUNT_ID = "accountId"
        const val EXTRA_FINGERPRINT = "fingerprint"; const val EXTRA_RENDER_SCALE = "renderScale"
        @Volatile private var started = false   // one game per process
    }

    private lateinit var plan: LaunchPlan
    private lateinit var settings: LauncherSettings
    private lateinit var engine: TouchEngine
    private lateinit var cursor: DynamicCursor
    private lateinit var overlay: TouchOverlayView
    private lateinit var gamepad: GamepadEngine
    private lateinit var sink: GameInputSink
    @Volatile private var session: GameSession? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var renderScale = 1f      // render pixels per overlay pixel
    private var userScale = 1f
    private var grabbed = false
    private var padActive = false
    private var lastPadTick = 0L

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) startRecorder(r.resultCode, data)
        else Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show()
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Microphone denied: recording without microphone audio", Toast.LENGTH_SHORT).show()
        requestProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(EXTRA_SESSION_DIR)
        val loaded = dir?.let { runCatching { CsJson.decodeFromString(LaunchPlan.serializer(), File(it, "plan.json").readText()) }.getOrNull() }
        if (loaded == null) { fatal("The launch plan is missing or unreadable. Start the game from the launcher again."); return }
        plan = loaded
        settings = Services.settings.state.value
        userScale = intent.getFloatExtra(EXTRA_RENDER_SCALE, 1f).coerceIn(0.3f, 1f)
        val density = resources.displayMetrics.density

        cursor = DynamicCursor(settings.cursor)
        sink = GameInputSink({ renderScale }, ::onCommand)
        val layout = ControlLayoutStore(Services.dirs.config.resolve("controls")).load(plan.controlLayoutId)
        engine = TouchEngine(layout, Geometry(1, 1, density), settings.touch, sink, cursor = cursor)
        gamepad = GamepadEngine(GamepadMapping(), engine.tracker, sink, density, cursor)

        val surface = SurfaceView(this).apply { holder.addCallback(this@GameActivity) }
        overlay = TouchOverlayView(this, engine, cursor)
        overlay.setOnCapturedPointerListener { _, e -> onCapturedPointer(e) }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1)); root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> updateGeometry(insets); insets }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGeometry(ViewCompat.getRootWindowInsets(root)) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        NativeBridge.listener = this
        onBackPressedDispatcher.addCallback(this) { tapKey(Glfw.KEY_ESCAPE) }
        lifecycleScope.launch { RecorderService.state.collect { s -> overlay.recording = s.recording; overlay.recordingPaused = s.paused; s.error?.let { Toast.makeText(this@GameActivity, it, Toast.LENGTH_LONG).show() } } }
    }

    private fun updateGeometry(insets: WindowInsetsCompat?) {
        if (overlay.width == 0) return
        val cut = insets?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val d = resources.displayMetrics.density
        engine.setGeometry(Geometry(overlay.width, overlay.height, d, cut?.left ?: 0, cut?.top ?: 0, cut?.right ?: 0, cut?.bottom ?: 0,
            (settings.touch.safeAreaPaddingDp * d).toInt()))
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- surface lifecycle
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val rw = ((width * userScale).toInt() and 1.inv()).coerceAtLeast(2); val rh = ((height * userScale).toInt() and 1.inv()).coerceAtLeast(2)
        renderScale = rw.toFloat() / width
        NativeBridge.setSurface(holder.surface, width, height, rw, rh)
        if (!started) { started = true; startGame() }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Bounded wait so the game thread stops rendering into a surface that is about to disappear.
        if (!NativeBridge.releaseSurface(2000)) android.util.Log.w("CSGame", "Game thread did not release the surface within 2s")
    }

    // ---------------------------------------------------------------- VM start
    private fun startGame() = thread(name = "cs-game-start") {
        try {
            val libjvm = plan.libjvm?.takeIf { File(it).isFile } ?: error("The selected Java runtime has no libjvm.so for ${Services.abi}. Re-import an Android build of the runtime.")
            var exec = plan
            if (plan.gameArgs.any { LaunchPlan.TOKEN_PLACEHOLDER in it }) {
                val id = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: error("No account selected")
                val token = Services.secrets.get("$id.mc") ?: error("The stored Microsoft session is unavailable. Sign in again in Accounts.")
                exec = plan.withSecret(token)
            }
            val s = GameSession(plan, intent.getStringExtra(EXTRA_FINGERPRINT)); session = s
            File(plan.sessionDir, "tmp").mkdirs()
            if (!NativeBridge.redirectStdio(plan.logFile)) error("Cannot create the game log file")
            val env = LinkedHashMap<String, String>()
            env["HOME"] = plan.workingDir; env["TMPDIR"] = File(plan.sessionDir, "tmp").absolutePath
            env["CS_CONTEXT"] = plan.rendererContext
            plan.rendererLibraries["gl"]?.let { env["CS_GL_LIB"] = it }
            plan.rendererLibraries["egl"]?.let { env["CS_EGL_LIB"] = it }
            plan.rendererLibraries["osmesa"]?.let { env["CS_OSMESA_LIB"] = it }
            env.putAll(plan.env)
            env.forEach { (k, v) -> NativeBridge.setEnv(k, v) }
            File(plan.workingDir).mkdirs(); NativeBridge.chdir(plan.workingDir)
            val opts = JvmOptionNormalizer.normalize(exec.jvmArgs).options
            println("[CSLauncher] Starting ${plan.versionId} with renderer ${plan.rendererId} (${plan.rendererContext}), Java ${plan.javaMajor}")
            val rc = NativeBridge.launchJvm(libjvm, opts.toTypedArray(), exec.mainClass, exec.gameArgs.toTypedArray(), 16)
            if (rc != 0) error("Could not start the game thread (error $rc)")
            s.monitor.markJvmStarted()
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            session?.failed(msg) ?: GameSession(plan, null).failed(msg)
            main.post { fatal(msg) }
        }
    }

    private fun fatal(message: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle("Game could not start").setMessage(message).setCancelable(false)
            .setPositiveButton("Back to launcher") { _, _ -> finishAndRemoveTask(); main.postDelayed({ Process.killProcess(Process.myPid()) }, 300) }.show()
    }

    // ---------------------------------------------------------------- callbacks from the game (native threads)
    private val xml = pro.cslauncher.core.launch.Log4jXml()
    override fun onLogLine(line: String) { val s = session ?: return; synchronized(xml) { xml.feed(line).forEach(s::onLine) } }
    override fun onJvmExit(code: Int) {
        session?.exited(code)
        if (RecorderService.state.value.recording) startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        main.post { finishAndRemoveTask(); main.postDelayed({ Process.killProcess(Process.myPid()) }, 1500) }
    }
    override fun onCursorMode(grabbed: Boolean) = main.post {
        this.grabbed = grabbed
        engine.setCursorGrabbed(grabbed); gamepad.cursorGrabbed = grabbed
        if (grabbed && cursor.externalMouseActive) overlay.requestPointerCapture() else overlay.releasePointerCapture()
        overlay.invalidate()
    }.let {}
    override fun onCursorPos(x: Double, y: Double) = main.post { cursor.syncFromGame((x / renderScale).toFloat(), (y / renderScale).toFloat()); overlay.invalidate() }.let {}
    override fun onSetClipboard(text: String) = main.post { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Minecraft", text)) }.let {}
    override fun onGetClipboard(): String? {
        val task = FutureTask { getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }
        main.post(task)
        return runCatching { task.get(500, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    // ---------------------------------------------------------------- commands from controls
    private fun onCommand(a: InputAction) {
        when (a) {
            InputAction.ShowKeyboard -> { overlay.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(overlay, 0) }
            InputAction.ToggleOverlay -> { engine.overlayHidden = !engine.overlayHidden; overlay.invalidate() }
            InputAction.ToggleRecording -> toggleRecording()
            InputAction.LauncherMenu -> showMenu()
            else -> {}
        }
    }

    private fun showMenu() {
        val rec = RecorderService.state.value
        PopupMenu(this, overlay, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            menu.add(0, 1, 0, if (engine.overlayHidden) "Show controls" else "Hide controls")
            menu.add(0, 2, 1, "Keyboard")
            menu.add(0, 3, 2, if (rec.recording) "Stop recording" else "Start recording")
            if (rec.recording) menu.add(0, 4, 3, if (rec.paused) "Resume recording" else "Pause recording")
            menu.add(0, 5, 4, "Quit game (saves world)")
            menu.add(0, 6, 5, "Force stop")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> onCommand(InputAction.ToggleOverlay); 2 -> onCommand(InputAction.ShowKeyboard); 3 -> toggleRecording()
                    4 -> startService(Intent(this@GameActivity, RecorderService::class.java).setAction(if (rec.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE))
                    5 -> NativeBridge.requestClose()
                    6 -> AlertDialog.Builder(this@GameActivity).setTitle("Force stop the game?")
                        .setMessage("Unsaved progress since the last autosave will be lost. Use \"Quit game\" to save first.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Force stop") { _, _ -> session?.exited(137); finishAndRemoveTask(); Process.killProcess(Process.myPid()) }.show()
                }
                true
            }
        }.show()
    }

    private fun toggleRecording() {
        if (RecorderService.state.value.recording) { startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)); return }
        val wantsMic = settings.recording.audio == AudioSourceChoice.MICROPHONE || settings.recording.audio == AudioSourceChoice.GAME_AND_MICROPHONE
        if (wantsMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else requestProjection()
    }
    private fun requestProjection() = projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    private fun startRecorder(code: Int, data: Intent) {
        val dm = resources.displayMetrics
        val i = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_CODE, code).putExtra(RecorderService.EXTRA_DATA, data)
            .putExtra(RecorderService.EXTRA_WIDTH, maxOf(overlay.width, 2)).putExtra(RecorderService.EXTRA_HEIGHT, maxOf(overlay.height, 2)).putExtra(RecorderService.EXTRA_DPI, dm.densityDpi)
            .putExtra(RecorderService.EXTRA_PROFILE, plan.profileName)
        ContextCompat.startForegroundService(this, i)
    }

    // ---------------------------------------------------------------- physical keyboard / mouse / controller
    private fun tapKey(k: Int) { NativeBridge.sendKey(k, k, Glfw.PRESS, 0); NativeBridge.sendKey(k, k, Glfw.RELEASE, 0) }
    private fun physicalInput() { if (settings.touch.hideOverlayWithPhysicalInput && !engine.overlayHidden) { engine.overlayHidden = true; overlay.invalidate() } }

    /** Returns true when the key was forwarded to the game; unhandled keys fall through to the framework (volume, touch-device back). */
    private fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            if (e.repeatCount == 0 && gamepad.onButton(e.keyCode, down)) { physicalInput(); return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || e.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return false
        if (e.keyCode == AndroidKeyMap.KEYCODE_BACK && e.source and InputDevice.SOURCE_KEYBOARD == 0) return false
        val k = (if (e.keyCode == AndroidKeyMap.KEYCODE_BACK) Glfw.KEY_ESCAPE else AndroidKeyMap.toGlfw(e.keyCode)) ?: return false
        val mods = AndroidKeyMap.modsFromMetaState(e.metaState)
        NativeBridge.sendKey(k, k, if (!down) Glfw.RELEASE else if (e.repeatCount > 0) Glfw.REPEAT else Glfw.PRESS, mods)
        if (down && mods and Glfw.MOD_CONTROL == 0) e.unicodeChar.takeIf { it > 0 && !Character.isISOControl(it) }?.let { NativeBridge.sendChar(it, mods) }
        if (e.isFromSource(InputDevice.SOURCE_KEYBOARD) && e.device?.isVirtual == false) physicalInput()
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyUp(keyCode, event)
    /** IME commits arrive as KEYCODE_UNKNOWN + characters (e.g. emoji, composed text): forward them as GLFW char events. */
    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        chars.codePoints().forEach { if (!Character.isISOControl(it)) NativeBridge.sendChar(it, 0) }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK) && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val rx = e.getAxisValue(MotionEvent.AXIS_Z); val ry = e.getAxisValue(MotionEvent.AXIS_RZ)
            gamepad.onAxes(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), rx, ry,
                maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)), maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)))
            val active = kotlin.math.abs(rx) > 0.1f || kotlin.math.abs(ry) > 0.1f
            if (active && !padActive) { padActive = true; lastPadTick = SystemClock.uptimeMillis(); main.post(padTicker) } else if (!active) padActive = false
            physicalInput(); return true
        }
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) { cursor.externalMouseActive = true; physicalInput(); return handleMouse(e, relative = false) }
        return super.onGenericMotionEvent(e)
    }
    private val padTicker = object : Runnable {
        override fun run() { if (!padActive) return; val now = SystemClock.uptimeMillis(); gamepad.tick(now - lastPadTick); lastPadTick = now; overlay.invalidate(); main.postDelayed(this, 16) }
    }

    private fun onCapturedPointer(e: MotionEvent): Boolean = handleMouse(e, relative = true)

    private fun handleMouse(e: MotionEvent, relative: Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                if (relative) NativeBridge.sendCursorDelta(e.x.toDouble(), e.y.toDouble())
                else if (!grabbed) { cursor.moveAbsolute(e.x, e.y); sink.cursorAbsolute(cursor.x, cursor.y); overlay.invalidate() }
                else overlay.requestPointerCapture()
            }
            MotionEvent.ACTION_SCROLL -> NativeBridge.sendScroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(), e.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble())
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val b = when (e.actionButton) { MotionEvent.BUTTON_PRIMARY -> 0; MotionEvent.BUTTON_SECONDARY -> 1; MotionEvent.BUTTON_TERTIARY -> 2; MotionEvent.BUTTON_BACK -> 3; MotionEvent.BUTTON_FORWARD -> 4; else -> return true }
                NativeBridge.sendMouseButton(b, if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) Glfw.PRESS else Glfw.RELEASE, AndroidKeyMap.modsFromMetaState(e.metaState))
            }
        }
        return true
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onPause() {
        super.onPause()
        if (::engine.isInitialized) { engine.cancelAll(); gamepad.releaseAll(); padActive = false }
        NativeBridge.sendFocus(false)
    }
    override fun onResume() { super.onResume(); NativeBridge.sendFocus(true) }
}
