package co.neatfolk.triptracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import co.neatfolk.triptracker.data.TripDatabase
import co.neatfolk.triptracker.data.TripMetadata
import kotlinx.coroutines.*

/**
 * Monitors Grab Driver app screens passively.
 * v4.1-alpha: Toast debug signals on every screen detection event.
 * v4.1-alpha: isPostTrip() excludes Job Details history screen.
 * v4.2-alpha: Top-screen overlay replaces Toast — Android 11+ ignores Toast.setGravity().
 */
class GrabAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TripDatabase

    private var stagedMetadata: TripMetadata? = null
    private var lastScreen1Ms = 0L
    private var lastScreen3Ms = 0L
    private var lastParsedEventMs = 0L
    private var lastPostTripSuccessMs = 0L   // v4.2: dedup successful fare captures
    private var lastPostTripFailLogMs = 0L   // v4.2: throttle "not parsed" logging
    private val DEBOUNCE_MS = 1500L

    private val GRAB_DRIVER_PACKAGE = "com.grabtaxi.driver2"

    // Tracks current overlay so previous one is removed before showing next
    private var currentToastView: TextView? = null

    // v4.2-alpha: WindowManager overlay positioned at top — Toast.setGravity ignored on Android 11+
    private fun toast(msg: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Handler(Looper.getMainLooper()).post {
            try {
                currentToastView?.let { try { wm.removeView(it) } catch (_: Exception) {} }

                val tv = TextView(this).apply {
                    text = msg
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding(36, 20, 36, 20)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#CC1a1a1a"))
                        cornerRadius = 48f
                    }
                    gravity = Gravity.CENTER
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 150
                }

                wm.addView(tv, params)
                currentToastView = tv

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        wm.removeView(tv)
                        if (currentToastView == tv) currentToastView = null
                    } catch (_: Exception) {}
                }, 3000)

            } catch (_: Exception) {}
        }
    }

    override fun onServiceConnected() {
        db = TripDatabase.getDatabase(this)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(GRAB_DRIVER_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        sendBroadcast(Intent(ACTION_AS_CONNECTED))
        toast("AS connected - Trip Tracker monitoring Grab")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != GRAB_DRIVER_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastParsedEventMs < DEBOUNCE_MS) return
        lastParsedEventMs = now

        val root = rootInActiveWindow ?: return
        try {
            processScreen(root, now)
        } finally {
            root.recycle()
        }
    }

    private fun processScreen(root: AccessibilityNodeInfo, now: Long) {
        when {
            AccessibilityScreenParser.isPostTrip(root) -> handlePostTrip(root, now)
            AccessibilityScreenParser.isScreen3(root)  -> handleScreen3(root, now)
            AccessibilityScreenParser.isScreen1(root)  -> handleScreen1(root, now)
        }
    }

    // ── Screen 1 ──────────────────────────────────────────────────────────────

    private fun handleScreen1(root: AccessibilityNodeInfo, now: Long) {
        if (now - lastScreen1Ms < 30_000) return
        lastScreen1Ms = now

        val parsed = AccessibilityScreenParser.parseScreen1(root)
        stagedMetadata = parsed

        scope.launch { db.tripMetadataDao().insert(parsed) }

        sendBroadcast(Intent(ACTION_SCREEN1_CAPTURED).apply {
            putExtra("pickupAbbrev",  parsed.pickupAbbrev)
            putExtra("dropoffAbbrev", parsed.dropoffAbbrev)
        })

        AsDebugLog.log(this, "SCREEN 1 captured:", listOf(
            "pickupAbbrev = ${parsed.pickupAbbrev.ifBlank { "(blank)" }}",
            "dropoffAbbrev = ${parsed.dropoffAbbrev.ifBlank { "(blank)" }}",
            "estTripMin = ${parsed.estimatedTripMin}"
        ))

        toast("AS: Booking screen detected")
    }

    // ── Screen 3 ──────────────────────────────────────────────────────────────

    private fun handleScreen3(root: AccessibilityNodeInfo, now: Long) {
        if (now - lastScreen3Ms < 60_000) return
        lastScreen3Ms = now

        val existing = if (stagedMetadata != null &&
                           now - (stagedMetadata?.capturedAtMs ?: 0) < 600_000)
            stagedMetadata else null

        val parsed = AccessibilityScreenParser.parseScreen3(root, existing)

        scope.launch {
            val latest = db.tripMetadataDao().getLatest()
            if (latest != null &&
                now - latest.capturedAtMs < 600_000 &&
                !latest.fareConfirmed) {
                db.tripMetadataDao().update(parsed.copy(id = latest.id))
            } else {
                db.tripMetadataDao().insert(parsed)
            }
            stagedMetadata = null
        }

        sendBroadcast(Intent(ACTION_SCREEN3_CAPTURED).apply {
            putExtra("passengerName",  parsed.passengerName)
            putExtra("pickupAddress",  parsed.pickupAddress)
            putExtra("dropoffAddress", parsed.dropoffAddress)
            putExtra("serviceType",    parsed.serviceType)
            putExtra("estimatedFare",  parsed.estimatedFare ?: 0.0)
            putExtra("paymentMethod",  parsed.paymentMethod)
            putExtra("hasPromo",       parsed.hasPromo)
            putExtra("hasSurge",       parsed.hasSurge)
        })

        // v4.2: log every captured field so Roy can validate against the real trip
        AsDebugLog.log(this, "SCREEN 3 captured:", listOf(
            "passenger = ${parsed.passengerName.ifBlank { "(blank)" }}",
            "pickup = ${parsed.pickupName.ifBlank { "(blank)" }} | ${parsed.pickupAddress.ifBlank { "(blank)" }}",
            "dropoff = ${parsed.dropoffName.ifBlank { "(blank)" }} | ${parsed.dropoffAddress.ifBlank { "(blank)" }}",
            "service = ${parsed.serviceType.ifBlank { "(blank)" }}",
            "estFare = ${parsed.estimatedFare?.let { "S$%.2f".format(it) } ?: "(none)"} | surge=${parsed.hasSurge} | promo=${parsed.hasPromo}",
            "payment = ${parsed.paymentMethod.ifBlank { "(blank)" }}"
        ))

        toast("AS: Route details captured - ${parsed.pickupAddress.take(20).ifBlank { "address pending" }}")
    }

    // ── Post-trip ─────────────────────────────────────────────────────────────

    private fun handlePostTrip(root: AccessibilityNodeInfo, now: Long) {
        val actualFare = AccessibilityScreenParser.parsePostTrip(root)

        if (actualFare == null) {
            // v4.2: fare may simply not be rendered yet on the first event —
            // keep retrying on later events. Log what the AS saw (throttled)
            // so any real parse failure is diagnosable from the debug log.
            if (now - lastPostTripFailLogMs > 30_000) {
                lastPostTripFailLogMs = now
                AsDebugLog.log(this, "POST-TRIP detected, fare NOT parsed - screen text was:",
                    AccessibilityScreenParser.dumpTexts(root))
                toast("AS: Post-trip detected - fare not parsed yet (logged)")
            }
            return
        }

        // v4.2: dedup — the same completion screen fires many events
        if (now - lastPostTripSuccessMs < 30_000) return
        lastPostTripSuccessMs = now

        AsDebugLog.log(this, "POST-TRIP fare parsed: S$%.2f".format(actualFare), emptyList())
        toast("AS: Post-trip screen detected - fare=S$%.2f".format(actualFare))

        scope.launch {
            val latest = db.tripMetadataDao().getLatestUnconfirmed()
            if (latest != null) db.tripMetadataDao().confirmFare(latest.id, actualFare)
        }

        sendBroadcast(Intent(ACTION_FARE_CAPTURED).apply {
            putExtra("actualFare", actualFare)
        })
    }

    override fun onInterrupt() { scope.cancel() }

    override fun onDestroy() {
        currentToastView?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_AS_CONNECTED     = "co.neatfolk.triptracker.AS_CONNECTED"
        const val ACTION_SCREEN1_CAPTURED = "co.neatfolk.triptracker.SCREEN1_CAPTURED"
        const val ACTION_SCREEN3_CAPTURED = "co.neatfolk.triptracker.SCREEN3_CAPTURED"
        const val ACTION_FARE_CAPTURED    = "co.neatfolk.triptracker.FARE_CAPTURED"
    }
}
