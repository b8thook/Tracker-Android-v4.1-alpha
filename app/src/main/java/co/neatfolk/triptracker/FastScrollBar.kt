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
 * v4.3-alpha (revised again): right-edge scrubber for the trip history list.
 *
 * v1 relied entirely on animating the whole View's alpha from 0 -> 1 on scroll,
 * with a thumb color close to the app's own dark-green theme — invisible on
 * the dark theme, with no fallback if the animation didn't fire.
 *
 * v2 fixed the track/thumb visibility but drew the label bubble directly on
 * this View's own Canvas, positioned to the left of the thumb. A View's
 * onDraw is clipped to its own bounds, and this View is only 28dp wide — so
 * the bubble text, which needs much more horizontal room than that, was
 * being silently clipped away every time. Track and thumb stayed visible
 * because they're drawn within the 28dp strip; the bubble never was.
 *
 * This version draws only the track + thumb here (both safely within
 * bounds), and reports the current label + vertical position via
 * [onLabelUpdate] so MainActivity can position a real sibling TextView —
 * which isn't constrained by this View's narrow bounds.
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // (fraction 0..1 of scrollable range, short label e.g. "Yesterday", "22 Jul")
    var sections: List<Pair<Float, String>> = emptyList()

    var onScrub: ((Float) -> Unit)? = null

    // label == null means "hide the bubble". centerYFraction is 0..1 relative
    // to this View's own height, for the Activity to convert to a pixel Y.
    var onLabelUpdate: ((label: String?, centerYFraction: Float) -> Unit)? = null

    private var scrollFraction = 0f
    private var isDragging = false
    private var lastReportedLabel: String? = null

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

    private fun animateEmphasis(target: Float) {
        emphasisAnimator?.cancel()
        emphasisAnimator = ValueAnimator.ofFloat(emphasis, target).apply {
            duration = 180
            addUpdateListener {
                emphasis = it.animatedValue as Float
                invalidate()
                notifyLabel()
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
        notifyLabel()
    }

    private fun nearestLabel(fraction: Float): String? {
        if (sections.isEmpty()) return null
        return sections.lastOrNull { it.first <= fraction + 0.001f }?.second ?: sections.first().second
    }

    // Reports the current label (or null to hide) + where the thumb sits, so
    // MainActivity can show/hide/reposition the real bubble TextView. Only
    // fires the callback when something actually changed, to avoid layout
    // churn on every single scroll-position update.
    private fun notifyLabel() {
        val shouldShow = isDragging || emphasis > 0.4f
        val label = if (shouldShow) nearestLabel(scrollFraction) else null
        if (label != lastReportedLabel || label != null) {
            lastReportedLabel = label
            val h = height.coerceAtLeast(1)
            val thumbHeight = if (isDragging) 64f else 48f
            val centerYFraction = ((8f + thumbHeight / 2f + scrollFraction * (h - 16f - thumbHeight)) / h)
                .coerceIn(0f, 1f)
            onLabelUpdate?.invoke(label, centerYFraction)
        }
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
                notifyLabel()
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
        notifyLabel()
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
    }
}
