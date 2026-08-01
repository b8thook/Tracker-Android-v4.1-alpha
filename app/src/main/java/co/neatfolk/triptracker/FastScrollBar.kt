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
 * v4.3-alpha: right-edge scrubber for the trip history list.
 *
 * Not tied to a RecyclerView (the list is a plain ScrollView + LinearLayout),
 * so this is a self-contained overlay: MainActivity reports scroll position via
 * [reportScroll], and reports where each date-header sits (as a 0..1 fraction of
 * scrollable range) via [sections]. Dragging the thumb calls [onScrub] with a
 * 0..1 fraction; MainActivity converts that back into a scrollTo() call.
 *
 * Fades in on scroll or touch, fades out after a short idle period so it never
 * sits on screen as clutter during normal reading.
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
    private val hideRunnable = Runnable { fadeTo(0f) }
    private val idleHideDelayMs = 1100L

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33808080")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1B5E3B")
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8111827")
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.RIGHT
    }

    private var currentAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    init {
        this.alpha = 0f
    }

    private fun fadeTo(target: Float) {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(this.alpha, target).apply {
            duration = 180
            addUpdateListener { this@FastScrollBar.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (!isDragging) handler.postDelayed(hideRunnable, idleHideDelayMs)
    }

    /** Called by MainActivity whenever the ScrollView's scroll position changes. */
    fun reportScroll(scrollY: Int, maxScroll: Int) {
        scrollFraction = if (maxScroll <= 0) 0f else (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        if (!isDragging) {
            fadeTo(1f)
            scheduleHide()
            invalidate()
        }
    }

    private fun nearestLabel(fraction: Float): String? {
        if (sections.isEmpty()) return null
        return sections.lastOrNull { it.first <= fraction + 0.001f }?.second ?: sections.first().second
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                handler.removeCallbacks(hideRunnable)
                fadeTo(1f)
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                scheduleHide()
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

        // Subtle full-height track
        canvas.drawRoundRect(RectF(w * 0.55f, 8f, w - 4f, h - 8f), 8f, 8f, trackPaint)

        // Thumb, centred on current scroll fraction
        val thumbHeight = if (isDragging) 64f else 48f
        val thumbCenterY = 8f + thumbHeight / 2f + scrollFraction * (h - 16f - thumbHeight)
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
