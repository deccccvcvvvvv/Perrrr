package pro.cslauncher.app.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import pro.cslauncher.core.controls.*
import pro.cslauncher.core.settings.CursorShape

/**
 * Draws the touch controls and the dynamic cursor, and feeds multi-touch events to [TouchEngine].
 * Redraws only when the engine reports a change (no idle animation loop). Also acts as the soft-keyboard target.
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(ctx: Context, val engine: TouchEngine, private val cursor: DynamicCursor) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 13f * density; color = 0xFFF0F1F5.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    var recording: Boolean = false; set(v) { field = v; invalidate() }
    var recordingPaused: Boolean = false; set(v) { field = v; invalidate() }
    /** Global opacity multiplier for every control (in-game menu / settings). */
    var opacityScale: Float = 1f; set(v) { field = v; invalidate() }
    /** Top-edge tab that opens the in-game menu regardless of the layout. */
    var showMenuHandle: Boolean = true; set(v) { field = v; invalidate() }
    var onMenuHandle: () -> Unit = {}
    /** Frame-rate / memory read-out, null when off. */
    var hud: String? = null; set(v) { if (v != field) { field = v; invalidate() } }
    private var handlePointer = -1
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; color = 0xFFFFFFFF.toInt(); typeface = Typeface.MONOSPACE }
    private fun handleRect(r: RectF) { val w = 64f * density; val h = 26f * density; r.set(width / 2f - w / 2, 0f, width / 2f + w / 2, h) }
    private val handleHit = RectF()
    private val ticker = object : Runnable {
        override fun run() { engine.tick(SystemClock.uptimeMillis()); if (activePointers > 0) postDelayed(this, 16) }
    }
    private var activePointers = 0

    init {
        isFocusable = true; isFocusableInTouchMode = true
        engine.onChanged = { postInvalidateOnAnimation() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                handleRect(handleHit)
                if (showMenuHandle && handlePointer < 0 && handleHit.contains(e.getX(i), e.getY(i))) { handlePointer = e.getPointerId(i); invalidate(); return true }
                engine.onPointerDown(e.getPointerId(i), e.getX(i), e.getY(i), t)
                if (activePointers++ == 0) postDelayed(ticker, 16)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) { val id = e.getPointerId(i); if (id != handlePointer) engine.onPointerMove(id, e.getX(i), e.getY(i), t) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                if (id == handlePointer) {
                    handlePointer = -1; handleRect(handleHit)
                    if (handleHit.contains(e.getX(i), e.getY(i))) { engine.cancelAll(); activePointers = 0; onMenuHandle() }
                    invalidate(); return true
                }
                engine.onPointerUp(id, e.getX(i), e.getY(i), t)
                activePointers = (activePointers - 1).coerceAtLeast(0)
            }
            MotionEvent.ACTION_CANCEL -> { engine.cancelAll(); activePointers = 0; handlePointer = -1 }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!engine.overlayHidden) {
            for ((el, r) in engine.visibleElements()) {
                val active = engine.isElementActive(el.id)
                val alpha = (el.opacity.coerceIn(0.05f, 1f) * opacityScale * (if (active) 1f else 0.8f) * 255).toInt().coerceIn(0, 255)
                rect.set(r.left, r.top, r.right, r.bottom)
                val radius = minOf(rect.width(), rect.height()) * el.cornerRadius.coerceIn(0f, 0.5f)
                when (el.kind) {
                    ElementKind.JOYSTICK -> {
                        val v = engine.joystickVisuals[el.id]
                        val cx = v?.centerX ?: rect.centerX(); val cy = v?.centerY ?: rect.centerY(); val rad = v?.radius ?: (minOf(rect.width(), rect.height()) / 2f)
                        fill.color = el.colorArgb.toInt(); fill.alpha = alpha / 2; c.drawCircle(cx, cy, rad, fill)
                        stroke.color = 0xFF34343F.toInt(); stroke.alpha = alpha; c.drawCircle(cx, cy, rad, stroke)
                        fill.color = if (active) 0xFFC5CBD5.toInt() else 0xFF777B87.toInt(); fill.alpha = alpha
                        c.drawCircle(v?.knobX ?: cx, v?.knobY ?: cy, rad * 0.38f, fill)
                    }
                    else -> {
                        fill.color = if (active) 0xFF30343D.toInt() else el.colorArgb.toInt(); fill.alpha = alpha
                        c.drawRoundRect(rect, radius, radius, fill)
                        stroke.color = if (active) 0xFFC5CBD5.toInt() else 0xFF34343F.toInt(); stroke.alpha = alpha
                        c.drawRoundRect(rect, radius, radius, stroke)
                        val label = el.label.ifBlank { el.actions.firstOrNull()?.describe() ?: "" }
                        if (label.isNotEmpty()) { text.alpha = alpha.coerceAtLeast(160); c.drawText(label.take(10), rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text) }
                    }
                }
            }
        }
        drawCursor(c)
        if (showMenuHandle) drawMenuHandle(c)
        hud?.let { drawHud(c, it) }
        if (recording) {
            fill.color = if (recordingPaused) 0xFFA5A7B2.toInt() else 0xFFE5484D.toInt(); fill.alpha = 230
            c.drawCircle(width - 18f * density, 18f * density, 6f * density, fill)
        }
    }

    /** Small pixel tab at the top centre: three bars, brighter while pressed. */
    private fun drawMenuHandle(c: Canvas) {
        val w = 44f * density; val h = 16f * density; val l = width / 2f - w / 2
        val pressed = handlePointer >= 0
        fill.color = 0xFF1E1E1F.toInt(); fill.alpha = if (pressed) 230 else 150; c.drawRect(l, 0f, l + w, h, fill)
        fill.color = if (pressed) 0xFF6CC349.toInt() else 0xFF48494A.toInt(); fill.alpha = if (pressed) 255 else 170
        c.drawRect(l + 2 * density, 0f, l + w - 2 * density, h - 2 * density, fill)
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = if (pressed) 255 else 200
        for (k in 0..2) { val y = 3f * density + k * 3.5f * density; c.drawRect(l + 12 * density, y, l + w - 12 * density, y + 1.5f * density, fill) }
    }

    private fun drawHud(c: Canvas, s: String) {
        val lines = s.split('\n'); val pad = 6f * density; val lh = hudText.textSize * 1.25f
        val w = lines.maxOf { hudText.measureText(it) } + 2 * pad; val h = lines.size * lh + 2 * pad - (lh - hudText.textSize)
        fill.color = 0xFF000000.toInt(); fill.alpha = 120; c.drawRect(8f * density, 8f * density, 8f * density + w, 8f * density + h, fill)
        lines.forEachIndexed { i, l -> c.drawText(l, 8f * density + pad, 8f * density + pad + hudText.textSize * 0.85f + i * lh, hudText) }
    }

    private fun drawCursor(c: Canvas) {
        if (!cursor.visible) return
        val s = cursor.settings; val size = s.sizeDp * density
        val x = cursor.x; val y = cursor.y
        fill.color = s.colorArgb.toInt(); fill.alpha = (s.opacity * 255).toInt()
        if (s.trail) cursor.trail.forEachIndexed { i, (tx, ty) -> fill.alpha = (s.opacity * 255 * (i + 1) / (cursor.trail.size + 1) / 3).toInt(); c.drawCircle(tx, ty, size * 0.15f, fill) }
        fill.alpha = (s.opacity * 255).toInt()
        when (s.shape) {
            CursorShape.ARROW -> {
                val p = Path().apply { moveTo(x, y); lineTo(x, y + size); lineTo(x + size * 0.28f, y + size * 0.74f); lineTo(x + size * 0.68f, y + size * 0.72f); close() }
                c.drawPath(p, fill); stroke.color = 0xFF0B0B0E.toInt(); stroke.alpha = 200; c.drawPath(p, stroke)
            }
            CursorShape.CIRCLE -> { c.drawCircle(x, y, size / 2, fill.apply { alpha = (alpha * 0.45f).toInt() }); stroke.color = s.colorArgb.toInt(); c.drawCircle(x, y, size / 2, stroke) }
            CursorShape.CROSSHAIR -> { stroke.color = s.colorArgb.toInt(); c.drawLine(x - size / 2, y, x + size / 2, y, stroke); c.drawLine(x, y - size / 2, x, y + size / 2, stroke) }
            CursorShape.DOT -> c.drawCircle(x, y, size * 0.18f, fill)
        }
        if (s.clickAnimation) cursor.clickAnimation(SystemClock.uptimeMillis())?.let { p ->
            stroke.color = s.colorArgb.toInt(); stroke.alpha = ((1 - p) * 200).toInt(); c.drawCircle(x, y, size * (0.3f + p * 0.7f), stroke)
            postInvalidateOnAnimation()   // only while the short ripple runs
        }
    }

    // ---- soft keyboard: committed text becomes GLFW char events, deletions become Backspace ----
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
                t.toString().codePoints().forEach { NativeBridge.sendChar(it, 0) }; return true
            }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before.coerceAtMost(64)) { tap(Glfw.KEY_BACKSPACE) }; return true
            }
            override fun performEditorAction(actionCode: Int): Boolean { tap(Glfw.KEY_ENTER); return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val k = AndroidKeyMap.toGlfw(event.keyCode) ?: return super.sendKeyEvent(event)
                NativeBridge.sendKey(k, k, if (event.action == KeyEvent.ACTION_DOWN) Glfw.PRESS else Glfw.RELEASE, 0); return true
            }
            private fun tap(k: Int) { NativeBridge.sendKey(k, k, Glfw.PRESS, 0); NativeBridge.sendKey(k, k, Glfw.RELEASE, 0) }
        }
    }
}
