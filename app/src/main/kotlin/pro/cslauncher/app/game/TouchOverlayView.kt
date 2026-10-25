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
                val i = e.actionIndex; engine.onPointerDown(e.getPointerId(i), e.getX(i), e.getY(i), t)
                if (activePointers++ == 0) postDelayed(ticker, 16)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) engine.onPointerMove(e.getPointerId(i), e.getX(i), e.getY(i), t)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; engine.onPointerUp(e.getPointerId(i), e.getX(i), e.getY(i), t)
                activePointers = (activePointers - 1).coerceAtLeast(0)
            }
            MotionEvent.ACTION_CANCEL -> { engine.cancelAll(); activePointers = 0 }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!engine.overlayHidden) {
            for ((el, r) in engine.visibleElements()) {
                val active = engine.isElementActive(el.id)
                val alpha = (el.opacity.coerceIn(0.05f, 1f) * (if (active) 1f else 0.8f) * 255).toInt()
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
        if (recording) {
            fill.color = if (recordingPaused) 0xFFA5A7B2.toInt() else 0xFFE5484D.toInt(); fill.alpha = 230
            c.drawCircle(width - 18f * density, 18f * density, 6f * density, fill)
        }
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
