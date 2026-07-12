package co.neatfolk.triptracker

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import co.neatfolk.triptracker.data.Trip
import co.neatfolk.triptracker.data.TripDatabase
import co.neatfolk.triptracker.sync.StoreSync
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var closeZoneView: View? = null
    private var fareView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // v4.0-alpha: stores AS-captured fare so it applies even if dialog opens after broadcast
    private var pendingAutoFare: Double? = null

    // v4.2-alpha: tracks current overlay so previous is removed before showing next
    private var currentToastView: TextView? = null

    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var state = State.IDLE

    // ── Collapse / expand state ───────────────────────────────────────────────
    private var overlayExpanded = false
    private val collapseHandler  = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseOverlay() }
    private val AUTO_COLLAPSE_MS = 4000L

    // Trip timing
    private var tripStartMs       = 0L
    private var pickupMs: Long?   = null
    private var stopMs: MutableList<Long> = mutableListOf()

    // GPS tracking
    private var currentDistanceKm = 0.0
    private var currentSpeedKmh   = 0.0
    private var maxSpeedKmh       = 0.0
    private val speedSamples      = mutableListOf<Float>()
    private var startLat: Double? = null
    private var startLon: Double? = null
    private var pickupLat: Double? = null
    private var pickupLon: Double? = null
    private var currentLat        = 0.0
    private var currentLon        = 0.0
    private var lastLat           = 0.0
    private var lastLon           = 0.0
    private var hasFirstFix       = false

    // Mid-trip button state
    private var destinationChanged = false
    private var pickedUpDone       = false
    private var destChangeDone     = false

    // Screen dimensions
    private var screenHeight = 0
    private var screenWidth  = 0

    private var pendingTrip: Trip? = null

    private lateinit var db: TripDatabase
    private lateinit var storeSync: StoreSync
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            refreshOverlayDisplay()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val grabBookingReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == GrabNotificationListener.ACTION_GRAB_BOOKING) {
                if (state == State.IDLE) startTrip(autoStarted = true)
            }
        }
    }

    // v4.0-alpha: auto-fills fare dialog when AS captures post-trip fare from Grab
    private val fareCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == GrabAccessibilityService.ACTION_FARE_CAPTURED) {
                val fare = intent.getDoubleExtra("actualFare", 0.0)
                if (fare > 0) autoFillFareDialog(fare)
            }
        }
    }

    enum class State { IDLE, TRACKING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        db = TripDatabase.getDatabase(this)
        storeSync = StoreSync(this)

        val metrics = resources.displayMetrics
        screenHeight = metrics.heightPixels
        screenWidth  = metrics.widthPixels

        createNotificationChannels()
        startForeground(NOTIF_OVERLAY, buildOverlayNotification())
        createOverlayWidget()

        registerReceiver(grabBookingReceiver,
            IntentFilter(GrabNotificationListener.ACTION_GRAB_BOOKING),
            RECEIVER_NOT_EXPORTED)

        registerReceiver(fareCaptureReceiver,
            IntentFilter(GrabAccessibilityService.ACTION_FARE_CAPTURED),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerTick)
        collapseHandler.removeCallbacks(collapseRunnable)
        stopGPS()
        overlayView?.let { safeRemove(it) }
        closeZoneView?.let { safeRemove(it) }
        fareView?.let { safeRemove(it) }
        try { unregisterReceiver(grabBookingReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(fareCaptureReceiver) } catch (_: Exception) {}
        currentToastView?.let { safeRemove(it) }
        scope.cancel()
        super.onDestroy()
    }

    // ── Create pill overlay ───────────────────────────────────────────────────

    @SuppressLint("InflateParams")
    private fun createOverlayWidget() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_widget, null)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24; y = 240
        }

        val pill = overlayView as? DraggableLayout

        pill?.onDragDelta = { dx, dy ->
            overlayParams?.let { p ->
                p.x -= dx.toInt()
                p.y -= dy.toInt()
                try { windowManager.updateViewLayout(overlayView, p) } catch (_: Exception) {}
                if (state == State.IDLE) {
                    val nearBottom = p.y < 120
                    if (nearBottom != pill?.inCloseZone) {
                        pill?.inCloseZone = nearBottom
                        showCloseZone(nearBottom)
                    }
                }
            }
        }

        pill?.onDragToClose = {
            if (state == State.IDLE) stopSelf()
        }

        overlayView?.findViewById<SwipeBar>(R.id.swipeBar)?.apply {
            onEndTrip    = { stopTrip() }
            onCancelTrip = { cancelTrip() }
        }

        overlayView?.findViewById<Button>(R.id.btnAction)?.setOnClickListener {
            if (state == State.IDLE) startTrip()
        }

        // Collapsed bar — tap to expand
        overlayView?.findViewById<TextView>(R.id.tvCollapsedBar)?.setOnClickListener {
            expandOverlay()
        }

        // Mid-trip buttons
        overlayView?.findViewById<Button>(R.id.btnPickedUp)?.setOnClickListener {
            if (!pickedUpDone) markPassengerPickedUp()
            scheduleCollapse()
        }

        overlayView?.findViewById<Button>(R.id.btnAddStop)?.setOnClickListener {
            addStop()
            scheduleCollapse()
        }

        overlayView?.findViewById<Button>(R.id.btnDestChange)?.setOnClickListener {
            if (!destChangeDone) markDestinationChanged()
            scheduleCollapse()
        }

        windowManager.addView(overlayView, overlayParams)
        refreshOverlayDisplay()
    }

    // ── Collapse / expand ─────────────────────────────────────────────────────

    private fun expandOverlay() {
        overlayExpanded = true
        refreshOverlayDisplay()
        scheduleCollapse()
    }

    private fun collapseOverlay() {
        overlayExpanded = false
        refreshOverlayDisplay()
    }

    private fun scheduleCollapse() {
        collapseHandler.removeCallbacks(collapseRunnable)
        collapseHandler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun cancelCollapse() {
        collapseHandler.removeCallbacks(collapseRunnable)
    }

    // ── Close zone X indicator ────────────────────────────────────────────────

    @SuppressLint("InflateParams")
    private fun showCloseZone(show: Boolean) {
        if (show && closeZoneView == null) {
            closeZoneView = TextView(this).apply {
                text = "✕"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = resources.getDrawable(R.drawable.bg_close_zone, null)
                setPadding(24, 24, 24, 24)
            }
            val p = WindowManager.LayoutParams(
                80.dpToPx(), 80.dpToPx(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 60
            }
            try { windowManager.addView(closeZoneView, p) } catch (_: Exception) {}
        } else if (!show && closeZoneView != null) {
            closeZoneView?.let { safeRemove(it) }
            closeZoneView = null
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    // ── Overlay display ───────────────────────────────────────────────────────

    private fun refreshOverlayDisplay() {
        val view            = overlayView ?: return
        val actionBtn       = view.findViewById<Button>(R.id.btnAction)
        val statsContainer  = view.findViewById<View>(R.id.statsContainer)
        val midTripButtons  = view.findViewById<View>(R.id.midTripButtons)
        val swipeBar        = view.findViewById<SwipeBar>(R.id.swipeBar)
        val tvTime          = view.findViewById<TextView>(R.id.tvTime)
        val tvDist          = view.findViewById<TextView>(R.id.tvDist)
        val tvCollapsedBar  = view.findViewById<TextView>(R.id.tvCollapsedBar)
        val btnPickedUp     = view.findViewById<Button>(R.id.btnPickedUp)
        val btnAddStop      = view.findViewById<Button>(R.id.btnAddStop)
        val btnDestChange   = view.findViewById<Button>(R.id.btnDestChange)

        when (state) {

            State.IDLE -> {
                tvCollapsedBar?.visibility  = View.GONE
                actionBtn?.visibility       = View.VISIBLE
                actionBtn?.text             = "▶"
                actionBtn?.setBackgroundResource(R.drawable.bg_circle_green)
                statsContainer?.visibility  = View.GONE
                midTripButtons?.visibility  = View.GONE
                swipeBar?.visibility        = View.GONE
                swipeBar?.reset()
                (overlayView as? DraggableLayout)?.inCloseZone = false
            }

            State.TRACKING -> {
                val elapsed = System.currentTimeMillis() - tripStartMs
                val mins    = (elapsed / 60000).toInt()
                val secs    = ((elapsed / 1000) % 60).toInt()
                val timeStr = "%d:%02d".format(mins, secs)
                val distStr = "%.2f".format(currentDistanceKm)

                if (overlayExpanded) {
                    tvCollapsedBar?.visibility  = View.GONE
                    actionBtn?.visibility       = View.VISIBLE
                    actionBtn?.text             = "■"
                    actionBtn?.setBackgroundResource(R.drawable.bg_circle_red)
                    statsContainer?.visibility  = View.VISIBLE
                    midTripButtons?.visibility  = View.VISIBLE
                    swipeBar?.visibility        = View.VISIBLE

                    tvTime?.text = timeStr
                    tvDist?.text = distStr

                    btnPickedUp?.alpha      = if (pickedUpDone) 0.4f else 1.0f
                    btnPickedUp?.isEnabled  = !pickedUpDone

                    btnAddStop?.text = if (stopMs.isEmpty()) "+ STOP" else "+ STOP (${stopMs.size})"

                    if (destChangeDone) {
                        btnDestChange?.alpha    = 0.4f
                        btnDestChange?.isEnabled = false
                        btnDestChange?.text     = "⤳ DONE"
                    } else {
                        btnDestChange?.alpha    = 1.0f
                        btnDestChange?.isEnabled = true
                        btnDestChange?.text     = "⤳ DEST"
                    }

                } else {
                    tvCollapsedBar?.visibility  = View.VISIBLE
                    tvCollapsedBar?.text        = "$timeStr · ${distStr}km  ⌄"
                    actionBtn?.visibility       = View.GONE
                    statsContainer?.visibility  = View.GONE
                    midTripButtons?.visibility  = View.GONE
                    swipeBar?.visibility        = View.GONE
                }
            }
        }
    }

    // ── Trip start ────────────────────────────────────────────────────────────

    private fun startTrip(autoStarted: Boolean = false) {
        tripStartMs = System.currentTimeMillis()
        currentDistanceKm = 0.0; currentSpeedKmh = 0.0; maxSpeedKmh = 0.0
        speedSamples.clear()
        startLat = null; startLon = null
        pickupLat = null; pickupLon = null; pickupMs = null
        stopMs.clear()
        destinationChanged = false
        pickedUpDone   = false
        destChangeDone = false
        hasFirstFix    = false
        overlayExpanded = false
        state = State.TRACKING
        startGPS()
        timerHandler.post(timerTick)
        refreshOverlayDisplay()
        val msg = if (autoStarted) "Trip started — Grab booking detected" else "Trip started"
        showToast(msg)
    }

    // ── Mid-trip actions ──────────────────────────────────────────────────────

    private fun markPassengerPickedUp() {
        if (state != State.TRACKING) return
        pickupMs  = System.currentTimeMillis()
        pickupLat = currentLat.takeIf { hasFirstFix }
        pickupLon = currentLon.takeIf { hasFirstFix }
        pickedUpDone = true
        refreshOverlayDisplay()
        showToast("Passenger picked up ✓")
    }

    private fun addStop() {
        if (state != State.TRACKING) return
        stopMs.add(System.currentTimeMillis())
        refreshOverlayDisplay()
        showToast("Stop ${stopMs.size} logged ✓")
    }

    private fun markDestinationChanged() {
        if (state != State.TRACKING) return
        destinationChanged = true
        destChangeDone = true
        refreshOverlayDisplay()
        showToast("Destination change noted ✓")
    }

    // ── Trip cancel (swipe left) ──────────────────────────────────────────────

    private fun cancelTrip() {
        cancelCollapse()
        stopGPS()
        timerHandler.removeCallbacks(timerTick)
        showCloseZone(false)
        overlayExpanded = false
        state = State.IDLE
        pendingTrip = null

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val trip = Trip(
            date         = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(cal.time),
            dayName      = SimpleDateFormat("EEEE",        Locale.ENGLISH).format(cal.time),
            startTimeStr = SimpleDateFormat("HH:mm",       Locale.ENGLISH).format(Date(tripStartMs)),
            endTimeStr   = SimpleDateFormat("HH:mm",       Locale.ENGLISH).format(Date(now)),
            startMs = tripStartMs, endMs = now,
            durationMin = 0.0, distanceKm = 0.0,
            fare = 0.0, cancelled = true, notes = "Cancelled"
        )
        scope.launch {
            db.tripDao().insert(trip)
            sendBroadcast(Intent(ACTION_TRIP_SAVED))
        }
        refreshOverlayDisplay()
        showToast("Trip cancelled")
    }

    // ── Trip stop (swipe right) ───────────────────────────────────────────────

    private fun stopTrip() {
        cancelCollapse()
        val endMs = System.currentTimeMillis()
        stopGPS()
        timerHandler.removeCallbacks(timerTick)
        showCloseZone(false)
        overlayExpanded = false
        state = State.IDLE

        val cal      = Calendar.getInstance()
        val avgSpeed = speedSamples.filter { it > 0 }.let {
            if (it.isEmpty()) 0.0 else it.average()
        }

        val waitMins = pickupMs?.let {
            roundTo((it - tripStartMs).toDouble() / 60_000.0, 1)
        } ?: 0.0

        val pickupTimeStr = pickupMs?.let {
            SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(it))
        } ?: ""

        val stopsJson = if (stopMs.isNotEmpty()) {
            val fmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
            val entries = stopMs.mapIndexed { i, ms ->
                "{\"stop\":${i + 1},\"ms\":$ms,\"time\":\"${fmt.format(Date(ms))}\"}"
            }
            "[${entries.joinToString(",")}]"
        } else ""

        pendingTrip = Trip(
            date             = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(cal.time),
            dayName          = SimpleDateFormat("EEEE",        Locale.ENGLISH).format(cal.time),
            startTimeStr     = SimpleDateFormat("HH:mm",       Locale.ENGLISH).format(Date(tripStartMs)),
            endTimeStr       = SimpleDateFormat("HH:mm",       Locale.ENGLISH).format(Date(endMs)),
            startMs          = tripStartMs, endMs = endMs,
            durationMin      = roundTo((endMs - tripStartMs).toDouble() / 60_000.0, 1),
            distanceKm       = roundTo(currentDistanceKm, 2),
            avgSpeedKmh      = roundTo(avgSpeed, 1),
            maxSpeedKmh      = roundTo(maxSpeedKmh, 1),
            startLat         = startLat, startLon = startLon,
            endLat           = currentLat.takeIf { hasFirstFix },
            endLon           = currentLon.takeIf { hasFirstFix },
            passengerPickedUpMs      = pickupMs,
            passengerPickedUpTimeStr = pickupTimeStr,
            waitTimeMins             = waitMins,
            pickupLat                = pickupLat,
            pickupLon                = pickupLon,
            isMultiStop              = stopMs.isNotEmpty(),
            stopCount                = stopMs.size,
            stopsJson                = stopsJson,
            destinationChanged       = destinationChanged
        )

        refreshOverlayDisplay()
        showFareDialog(pendingTrip!!)
    }

    // ── Fare dialog ───────────────────────────────────────────────────────────

    @SuppressLint("InflateParams")
    private fun showFareDialog(trip: Trip) {
        fareView?.let { safeRemove(it); fareView = null }
        fareView = LayoutInflater.from(this).inflate(R.layout.dialog_fare, null)

        val fareParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = (screenHeight * 0.15).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        fareView?.apply {
            val distStr = "%.2f".format(trip.distanceKm)
            val durStr  = trip.durationMin.toInt().toString()
            val waitStr = if (trip.waitTimeMins > 0) "  ·  Wait ${trip.waitTimeMins.toInt()}min" else ""
            val stopStr = if (trip.stopCount > 0) "  ·  ${trip.stopCount} stop${if (trip.stopCount > 1) "s" else ""}" else ""
            val destStr = if (trip.destinationChanged) "  ·  Dest ⤳" else ""
            findViewById<TextView>(R.id.tvSummary)?.text =
                "${distStr}km  ·  ${durStr}min${waitStr}${stopStr}${destStr}"

            val fareInput = findViewById<EditText>(R.id.etFare)
            val tipInput  = findViewById<EditText>(R.id.etTip)

            var dragStartY = 0f; var dragParamY = 0
            val dragHandle = findViewById<View>(R.id.fareDialogRoot)
            dragHandle?.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { dragStartY = ev.rawY; dragParamY = fareParams.y; true }
                    MotionEvent.ACTION_MOVE -> {
                        fareParams.y = (dragParamY + (ev.rawY - dragStartY)).toInt()
                            .coerceIn(0, screenHeight - 400)
                        try { windowManager.updateViewLayout(fareView, fareParams) } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            findViewById<Button>(R.id.btnSave)?.setOnClickListener {
                val fareVal = fareInput?.text?.toString()?.toDoubleOrNull()
                if (fareVal == null || fareVal <= 0) {
                    showToast("Enter the fare from Grab")
                    return@setOnClickListener
                }
                val tipVal = tipInput?.text?.toString()?.toDoubleOrNull() ?: 0.0
                saveAndClose(trip.copy(fare = roundTo(fareVal, 2), tip = roundTo(tipVal, 2)))
            }

            findViewById<Button>(R.id.btnSkip)?.setOnClickListener {
                saveAndClose(trip)
            }
        }

        windowManager.addView(fareView, fareParams)

        // v4.0-alpha: apply any fare captured by AS while dialog was opening (timing fix)
        pendingAutoFare?.let { fare ->
            timerHandler.postDelayed({
                fareView?.let { applyFareToDialog(it, fare) }
            }, 400)
        }

        timerHandler.postDelayed({
            fareView?.findViewById<EditText>(R.id.etFare)?.let { et ->
                if (et.text.isNullOrEmpty()) {
                    et.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }, 600)
    }

    // v4.0-alpha: called when AS captures fare from Grab's post-trip screen
    private fun autoFillFareDialog(fare: Double) {
        pendingAutoFare = fare
        val view = fareView
        if (view != null) {
            applyFareToDialog(view, fare)
        } else {
            // Dialog not open yet — pendingAutoFare will apply when showFareDialog() is called
            showToast("AS captured S\$%.2f - swipe right to end trip and it will auto-fill".format(fare), long = true)
        }
    }

    private fun applyFareToDialog(view: View, fare: Double) {
        val etFare = view.findViewById<EditText>(R.id.etFare) ?: return
        etFare.setText("%.2f".format(fare))
        etFare.setBackgroundColor(0x4038A169.toInt())
        timerHandler.postDelayed({ etFare.background = null }, 1500)
        showToast("Fare S\$%.2f auto-filled from Grab".format(fare))
        pendingAutoFare = null
    }

    private fun saveAndClose(trip: Trip) {
        fareView?.let { v ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
        fareView?.let { safeRemove(it) }
        fareView = null; pendingTrip = null; pendingAutoFare = null

        val msg = if (trip.fare != null && trip.fare > 0)
            "S$%.2f saved".format(trip.fare) else "Trip saved"
        showToast(msg)

        scope.launch {
            db.tripDao().insert(trip)
            sendBroadcast(Intent(ACTION_TRIP_SAVED))
            val ok = storeSync.smartPushTrips(db.tripDao().getAll())
            sendBroadcast(Intent(ACTION_SYNC_STATUS).putExtra("ok", ok))
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGPS() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000).setMaxUpdateDelayMillis(3000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it) }
            }
        }
        try {
            fusedLocation.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            showToast("Location permission needed", long = true)
            stopSelf()
        }
    }

    private fun processLocation(loc: Location) {
        currentLat = loc.latitude; currentLon = loc.longitude
        val speedKmh = loc.speed * 3.6f
        currentSpeedKmh = speedKmh.toDouble()
        speedSamples.add(speedKmh)
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh.toDouble()

        if (!hasFirstFix) {
            startLat = loc.latitude; startLon = loc.longitude
            lastLat  = loc.latitude; lastLon  = loc.longitude
            hasFirstFix = true; return
        }

        if (state == State.TRACKING && loc.accuracy < 80f) {
            val d = haversine(lastLat, lastLon, loc.latitude, loc.longitude)
            if (d in 0.005..2.0) currentDistanceKm += d
        }
        lastLat = loc.latitude; lastLon = loc.longitude
    }

    private fun stopGPS() {
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERLAY, "Overlay Service",
                NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps the floating widget alive" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BOOKING, "Booking Detected",
                NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Grab booking auto-start alerts" }
        )
    }

    private fun buildOverlayNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_OVERLAY)
            .setContentTitle("Trip Tracker active")
            .setContentText("Tap pill to expand  ·  Swipe right to end  ·  Swipe left to cancel")
            .setSmallIcon(R.drawable.ic_car)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun safeRemove(v: View) { try { windowManager.removeView(v) } catch (_: Exception) {} }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    private fun roundTo(value: Double, places: Int): Double {
        val factor = 10.0.pow(places)
        return (value * factor).roundToLong() / factor
    }

    private fun Double.roundToLong(): Long = Math.round(this)


    // v4.2-alpha: WindowManager overlay at top — Toast.setGravity() ignored on Android 11+
    private fun showToast(msg: String, long: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            try {
                currentToastView?.let { safeRemove(it) }
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
                windowManager.addView(tv, params)
                currentToastView = tv
                Handler(Looper.getMainLooper()).postDelayed({
                    safeRemove(tv)
                    if (currentToastView == tv) currentToastView = null
                }, if (long) 5000L else 3000L)
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val CHANNEL_OVERLAY   = "overlay_svc"
        const val CHANNEL_BOOKING   = "booking_detect"
        const val NOTIF_OVERLAY     = 2001
        const val ACTION_TRIP_SAVED = "co.neatfolk.triptracker.TRIP_SAVED"
        const val ACTION_SYNC_STATUS = "co.neatfolk.triptracker.SYNC_STATUS"
    }
}


