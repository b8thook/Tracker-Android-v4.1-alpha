package co.neatfolk.triptracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * v4.3-alpha (revised): right-edge scrubber for the trip history list.
 *
 * v1 relied entirely on animating the whole View's alpha from 0 -> 1 on scroll,
 * with a thumb color close to the app's own dark-green theme. Result: on the
 * dark theme it was effectively invisible, and if the fade animation never
 * fired, there was no fallback — nothing drew at all.
 *
 * This version always draws a low-opacity baseline track + thumb (so the
 * control is discoverable even at rest), and boosts opacity + uses a bright,
 * theme-independent accent color when the list is actively scrolling or being
 * dragged. The View's own alpha is never touched — only Paint alpha values
 * change — so there's no "everything invisible" failure mode.
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // (fraction 0..1 of scrollable range, short label e.g. "Yesterday", "22 Jul")
    var sections: List<Pair<Float, String>> = emptyList()

    var onScrub: ((Float) -> Unit)? = null

    private var scrollFraction = 0f
    private var isDragging = false

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { animateEmphasis(0f) }
    private val idleDelayMs = 1100L

    // 0f = resting baseline, 1f = actively scrolling/dragging
    private var emphasis = 0f
    private var emphasisAnimator: ValueAnimator? = null

    companion object {
        private const val BASELINE_TRACK_ALPHA = 60   // out of 255 — always at least this visible
        private const val ACTIVE_TRACK_ALPHA   = 130
        private const val BASELINE_THUMB_ALPHA = 130
        private const val ACTIVE_THUMB_ALPHA   = 255
    }

    // Bright amber accent — deliberately different from the app's green branding
    // so it reads clearly against both the light and dark theme backgrounds.
    private val thumbColor = Color.parseColor("#F2A93A")
    private val trackColor = Color.parseColor("#9AA5A0")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8111827")
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.RIGHT
    }

    private fun animateEmphasis(target: Float) {
        emphasisAnimator?.cancel()
        emphasisAnimator = ValueAnimator.ofFloat(emphasis, target).apply {
            duration = 180
            addUpdateListener {
                emphasis = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        if (!isDragging) handler.postDelayed(idleRunnable, idleDelayMs)
    }

    /** Called by MainActivity whenever the ScrollView's scroll position changes. */
    fun reportScroll(scrollY: Int, maxScroll: Int) {
        scrollFraction = if (maxScroll <= 0) 0f else (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        if (!isDragging) {
            animateEmphasis(1f)
            scheduleIdle()
        }
        invalidate()
    }

    private fun nearestLabel(fraction: Float): String? {
        if (sections.isEmpty()) return null
        return sections.lastOrNull { it.first <= fraction + 0.001f }?.second ?: sections.first().second
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                handler.removeCallbacks(idleRunnable)
                animateEmphasis(1f)
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                scheduleIdle()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(y: Float) {
        val fraction = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        scrollFraction = fraction
        onScrub?.invoke(fraction)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        trackPaint.color = trackColor
        trackPaint.alpha = (BASELINE_TRACK_ALPHA + emphasis * (ACTIVE_TRACK_ALPHA - BASELINE_TRACK_ALPHA)).toInt()
        canvas.drawRoundRect(RectF(w * 0.55f, 8f, w - 4f, h - 8f), 8f, 8f, trackPaint)

        thumbPaint.color = thumbColor
        thumbPaint.alpha = (BASELINE_THUMB_ALPHA + emphasis * (ACTIVE_THUMB_ALPHA - BASELINE_THUMB_ALPHA)).toInt()

        val thumbHeight = if (isDragging) 64f else 48f
        val thumbCenterY = (8f + thumbHeight / 2f + scrollFraction * (h - 16f - thumbHeight))
            .coerceIn(8f + thumbHeight / 2f, h - 8f - thumbHeight / 2f)
        val thumbTop = thumbCenterY - thumbHeight / 2f
        val thumbBottom = thumbCenterY + thumbHeight / 2f
        canvas.drawRoundRect(RectF(w * 0.35f, thumbTop, w - 4f, thumbBottom), 10f, 10f, thumbPaint)

        // Section label bubble, shown only while actively dragging
        if (isDragging) {
            val label = nearestLabel(scrollFraction)
            if (!label.isNullOrEmpty()) {
                val textWidth = bubbleTextPaint.measureText(label)
                val bubblePadding = 20f
                val bubbleHeight = 56f
                val bubbleRight = w * 0.35f - 12f
                val bubbleLeft = (bubbleRight - textWidth - bubblePadding * 2).coerceAtLeast(0f)
                val bubbleTop = thumbCenterY - bubbleHeight / 2f
                val bubbleBottom = thumbCenterY + bubbleHeight / 2f
                canvas.drawRoundRect(
                    RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom), 12f, 12f, bubblePaint
                )
                canvas.drawText(
                    label, bubbleRight - bubblePadding, thumbCenterY + bubbleTextPaint.textSize / 3f,
                    bubbleTextPaint
                )
            }
        }
    }
}
