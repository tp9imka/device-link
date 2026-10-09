package dev.devicelink.receiver.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.devicelink.receiver.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws a key grid and turns touches into key events. Taps commit on release (so a finger can slide
 * to the right key); shift and delete act on press, delete repeats while held, a long-press commits
 * the key's alternate, and a horizontal drag on the space bar moves the cursor. With two thumbs, a
 * new touch commits the previous one immediately so fast typing keeps its order.
 */
class KeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onKey(key: Key)
        /** Returns true when the long-press did something (the tap is then not committed). */
        fun onLongPress(key: Key): Boolean
        fun onCursor(steps: Int)
    }

    var listener: Listener? = null

    /** Setting an equal layout is a no-op, so the service can re-render after every key. */
    var rows: List<List<Key>> = emptyList()
        set(value) {
            if (value == field) return
            val heightChanged = value.size != field.size
            field = value
            place()
            // Fingers still down keep their key (same position) in the new layout.
            touches.forEach { touch -> placed.getOrNull(touch.key.row)?.getOrNull(touch.key.column)?.let { touch.key = it } }
            if (heightChanged) requestLayout()
            invalidate()
        }
    var shift = ShiftMode.OFF
        set(value) { field = value; invalidate() }
    var enterLabel = "⏎"
        set(value) { field = value; invalidate() }

    private val res = context.resources
    private val density = res.displayMetrics.density
    // Keys keep their size; labels follow the font scale up to a point so they still fit.
    private val labelScale = density * min(res.configuration.fontScale, 1.3f)
    private val keyHeight = res.getDimension(R.dimen.kb_key_height)
    private val keyGap = res.getDimension(R.dimen.kb_key_gap)
    private val rowGap = res.getDimension(R.dimen.kb_row_gap)
    private val padding = res.getDimension(R.dimen.kb_padding)
    private val radius = 8 * density
    private val slideStep = 14 * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMillis = ViewConfiguration.getLongPressTimeout().toLong().coerceAtMost(400)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; textSize = 10 * labelScale }
    private val colorBackground = context.getColor(R.color.kb_background)
    private val colorKey = context.getColor(R.color.kb_key)
    private val colorFunction = context.getColor(R.color.kb_key_function)
    private val colorPressed = context.getColor(R.color.kb_key_pressed)
    private val colorLabel = context.getColor(R.color.kb_label)
    private val colorHint = context.getColor(R.color.kb_hint)
    private val colorAccent = context.getColor(R.color.accent)
    private val colorOnAccent = context.getColor(R.color.on_accent)

    private class Placed(val key: Key, val rect: RectF, val hit: RectF, val row: Int, val column: Int)
    private var placed: List<List<Placed>> = emptyList()

    private class Touch(val id: Int, var key: Placed, val downX: Float) {
        var done = false
        var sliding = false
        var slideX = downX
    }
    private val touches = mutableListOf<Touch>()
    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private var repeat: Runnable? = null

    init {
        // Keyboard rows are not mirrored in right-to-left locales.
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = rows.size
        val height = 2 * padding + count * keyHeight + (count - 1).coerceAtLeast(0) * rowGap
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    private fun place() {
        if (width == 0) { placed = emptyList(); return }
        val width = width - 2 * padding
        placed = rows.mapIndexed { index, row ->
            val top = padding + index * (keyHeight + rowGap)
            val unit = width / row.sumOf { it.width.toDouble() }.toFloat()
            var x = padding
            row.mapIndexed { column, key ->
                val w = key.width * unit
                val rect = RectF(x + keyGap / 2, top, x + w - keyGap / 2, top + keyHeight)
                val hit = RectF(x, if (index == 0) 0f else top - rowGap / 2, x + w,
                    if (index == rows.lastIndex) height.toFloat() else top + keyHeight + rowGap / 2)
                x += w
                Placed(key, rect, hit, index, column)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || placed.size != rows.size) place()
    }

    override fun onDraw(canvas: Canvas) {
        if (placed.size != rows.size) place()
        canvas.drawColor(colorBackground)
        val pressed = touches.map { it.key }.toSet()
        for (row in placed) for (item in row) {
            val key = item.key
            if (key.action == Action.SPACER) continue
            val accent = key.action == Action.ENTER
            keyPaint.color = when {
                item in pressed -> colorPressed
                accent -> colorAccent
                key.action == Action.TEXT || key.action == Action.SPACE -> colorKey
                else -> colorFunction
            }
            canvas.drawRoundRect(item.rect, radius, radius, keyPaint)
            drawLabel(canvas, item, accent && item !in pressed)
        }
    }

    private fun drawLabel(canvas: Canvas, item: Placed, onAccent: Boolean) {
        val key = item.key
        val (text, size, color) = when (key.action) {
            Action.SHIFT -> Triple(if (shift == ShiftMode.LOCKED) "⇪" else "⇧", 22f, if (shift == ShiftMode.OFF) colorLabel else colorAccent)
            Action.DELETE -> Triple("⌫", 20f, colorLabel)
            Action.ENTER -> Triple(enterLabel, if (enterLabel.length > 2) 14f else 20f, colorLabel)
            Action.SPACE -> Triple(key.label, 13f, colorHint)
            Action.TEXT -> Triple(key.label, 22f, colorLabel)
            else -> Triple(key.label, 15f, colorLabel)
        }
        labelPaint.textSize = size * labelScale
        labelPaint.color = if (onAccent) colorOnAccent else color
        labelPaint.typeface = if (key.action == Action.TEXT) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        val y = item.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(text, item.rect.centerX(), y, labelPaint)
        key.alternate?.let {
            hintPaint.color = colorHint
            canvas.drawText(it, item.rect.right - 4 * density, item.rect.top + hintPaint.textSize + 2 * density, hintPaint)
        }
    }

    // ----- touch ------------------------------------------------------------------------------

    private fun keyAt(x: Float, y: Float): Placed? {
        val row = placed.firstOrNull { r -> r.isNotEmpty() && y >= r[0].hit.top && y < r[0].hit.bottom } ?: return null
        val index = row.indexOfFirst { x >= it.hit.left && x < it.hit.right }.takeIf { it >= 0 }
            ?: (if (x < (row.firstOrNull()?.hit?.left ?: 0f)) 0 else row.lastIndex)
        // Spacers (the half keys around the middle row) belong to their neighbour.
        if (row[index].key.action != Action.SPACER) return row[index]
        return row.getOrNull(index + 1)?.takeIf { it.key.action != Action.SPACER }
            ?: row.getOrNull(index - 1)?.takeIf { it.key.action != Action.SPACER }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val item = keyAt(event.getX(index), event.getY(index)) ?: return true
                // A second finger commits the first one now, preserving typing order.
                touches.filter { !it.done }.forEach { commit(it) }
                val touch = Touch(event.getPointerId(index), item, event.getX(index))
                touches += touch
                press(touch)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val touch = touches.firstOrNull { it.id == event.getPointerId(i) && !it.done } ?: continue
                move(touch, event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                touches.firstOrNull { it.id == id }?.let { touch ->
                    if (!touch.done && !touch.sliding) commit(touch)
                    touches.remove(touch)
                    stopTimers()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        invalidate()
        return true
    }

    private fun press(touch: Touch) {
        stopTimers()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val key = touch.key.key
        when (key.action) {
            Action.DELETE -> {
                touch.done = true
                listener?.onKey(key)
                repeat = object : Runnable {
                    var delay = 60L
                    override fun run() {
                        if (touch !in touches) return
                        listener?.onKey(key)
                        delay = (delay - 5).coerceAtLeast(30)
                        handler.postDelayed(this, delay)
                    }
                }.also { handler.postDelayed(it, longPressMillis) }
            }
            Action.SHIFT -> {
                touch.done = true
                listener?.onKey(key)
                scheduleLongPress(touch)
            }
            else -> scheduleLongPress(touch)
        }
    }

    private fun scheduleLongPress(touch: Touch) {
        longPress?.let(handler::removeCallbacks)
        longPress = Runnable {
            if (touch in touches && !touch.sliding && listener?.onLongPress(touch.key.key) == true) {
                touch.done = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }.also { handler.postDelayed(it, longPressMillis) }
    }

    private fun move(touch: Touch, x: Float, y: Float) {
        val key = touch.key.key
        if (key.action == Action.SPACE) {
            if (!touch.sliding && abs(x - touch.downX) > touchSlop) { touch.sliding = true; stopTimers() }
            if (touch.sliding) {
                val steps = ((x - touch.slideX) / slideStep).toInt()
                if (steps != 0) { touch.slideX += steps * slideStep; listener?.onCursor(steps) }
            }
            return
        }
        if (key.action == Action.DELETE || key.action == Action.SHIFT) return
        // Sliding onto a neighbouring key retargets the touch, as on most keyboards.
        val now = keyAt(x, y)
        if (now != null && now !== touch.key && now.key.action == Action.TEXT) {
            touch.key = now
            scheduleLongPress(touch)
        }
    }

    private fun commit(touch: Touch) {
        touch.done = true
        stopTimers()
        listener?.onKey(touch.key.key)
    }

    private fun stopTimers() {
        longPress?.let(handler::removeCallbacks); longPress = null
        repeat?.let(handler::removeCallbacks); repeat = null
    }

    fun cancelTouches() {
        stopTimers()
        touches.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }
}
