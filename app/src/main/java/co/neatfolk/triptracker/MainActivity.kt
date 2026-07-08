package co.neatfolk.triptracker

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import co.neatfolk.triptracker.data.Trip
import co.neatfolk.triptracker.data.TripDatabase
import co.neatfolk.triptracker.sync.StoreSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var db: TripDatabase
    private lateinit var storeSync: StoreSync
    private lateinit var prefs: SharedPreferences

    private lateinit var tvDate: TextView
    private lateinit var tvTodayEarnings: TextView
    private lateinit var tvTripCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var tripListContainer: LinearLayout
    private lateinit var btnStartOverlay: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnSyncStore: Button
    private lateinit var btnSettings: Button
    private lateinit var btnAddTrip: Button
    private lateinit var btnFilterDay: Button
    private lateinit var btnFilterWeek: Button
    private lateinit var btnFilterMonth: Button
    private lateinit var btnFilterYear: Button
    private lateinit var btnFilterAll: Button
    private lateinit var tvSyncStatus: TextView

    // v4.1-alpha: active filter (Today/Week/Month/Year/All)
    private var activeFilter = "Today"

    private val tripSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlayService.ACTION_TRIP_SAVED) refreshTrips()
        }
    }

    private val syncStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlayService.ACTION_SYNC_STATUS) {
                updateSyncStatus(intent.getBooleanExtra("ok", false))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("tt_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // v4.0-alpha: request POST_NOTIFICATIONS for Android 13+ Toast and notification visibility
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }

        db = TripDatabase.getDatabase(this)
        storeSync = StoreSync(this)
        bindViews()
        setupButtons()
        updateHeader()
        refreshTrips()
        syncFromStoreOnStart()
        registerReceiver(tripSavedReceiver,
            IntentFilter(FloatingOverlayService.ACTION_TRIP_SAVED), RECEIVER_NOT_EXPORTED)
        registerReceiver(syncStatusReceiver,
            IntentFilter(FloatingOverlayService.ACTION_SYNC_STATUS), RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        refreshTrips()
        updateHeader()
        checkAccessibilityService()
        // Show last sync time from stored timestamp
        val lastSync = storeSync.getLastSyncMs()
        if (lastSync > 0) updateSyncStatus(true)
        else if (!storeSync.hasStoredCredentials()) {
            if (::tvSyncStatus.isInitialized) tvSyncStatus.text = "Not signed in"
        }
    }

    // v4.0-alpha: banner click listener + visibility check
    private fun checkAccessibilityService() {
        val banner = findViewById<android.view.View>(R.id.bannerAS)
        if (!SetupActivity.isEnabled(this)) {
            banner?.visibility = android.view.View.VISIBLE
            banner?.setOnClickListener {
                startActivity(android.content.Intent(this, SetupActivity::class.java))
            }
        } else {
            banner?.visibility = android.view.View.GONE
            banner?.setOnClickListener(null)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(tripSavedReceiver)
        try { unregisterReceiver(syncStatusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvDate            = findViewById(R.id.tvDate)
        tvTodayEarnings   = findViewById(R.id.tvTodayEarnings)
        tvTripCount       = findViewById(R.id.tvTripCount)
        tvSyncStatus      = findViewById(R.id.tvSyncStatus)
        tvHint            = findViewById(R.id.tvHint)
        tripListContainer = findViewById(R.id.tripListContainer)
        btnStartOverlay   = findViewById(R.id.btnStartOverlay)
        btnExportCsv      = findViewById(R.id.btnExportCsv)
        btnSyncStore      = findViewById(R.id.btnSyncStore)
        btnSettings       = findViewById(R.id.btnSettings)
        btnAddTrip        = findViewById(R.id.btnAddTrip)
        btnFilterDay      = findViewById(R.id.btnFilterDay)
        btnFilterWeek     = findViewById(R.id.btnFilterWeek)
        btnFilterMonth    = findViewById(R.id.btnFilterMonth)
        btnFilterYear     = findViewById(R.id.btnFilterYear)
        btnFilterAll      = findViewById(R.id.btnFilterAll)
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {

        btnStartOverlay.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> {
                    AlertDialog.Builder(this, R.style.TripTracker_Dialog)
                        .setTitle("Permission needed")
                        .setMessage("Trip Tracker needs 'Display over other apps' so the widget can sit on top of Grab.\n\nTap OK → find Trip Tracker → enable the toggle.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    requestPermissions(arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
                }
                else -> {
                    startForegroundService(Intent(this, FloatingOverlayService::class.java))
                    tvHint.visibility = View.VISIBLE
                    btnStartOverlay.text = "Overlay Running"
                    Toast.makeText(this, "Switch to Grab — tap the green button to start", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnExportCsv.setOnClickListener {
            lifecycleScope.launch {
                val trips = withContext(Dispatchers.IO) { db.tripDao().getAll() }
                if (trips.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No trips to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                shareCsv(buildCsv(trips))
            }
        }

        btnSyncStore.setOnClickListener {
            if (!storeSync.isAuthenticated()) showSyncLoginDialog()
            else syncToStore()
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        // v4.0-alpha: Add trip button — opens manual entry dialog
        btnAddTrip.setOnClickListener {
            showManualAddTripDialog()
        }

        // v4.1-alpha: filter buttons
        val filterButtons = listOf(
            btnFilterDay to "Today",
            btnFilterWeek to "Week",
            btnFilterMonth to "Month",
            btnFilterYear to "Year",
            btnFilterAll to "All"
        )
        filterButtons.forEach { (btn, label) ->
            btn.setOnClickListener {
                activeFilter = label
                updateFilterButtonStyles(filterButtons)
                refreshTrips()
            }
        }
        updateFilterButtonStyles(filterButtons)
    }

    private fun updateFilterButtonStyles(buttons: List<Pair<Button, String>>) {
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        buttons.forEach { (btn, label) ->
            if (label == activeFilter) {
                btn.setBackgroundColor(if (isDark) 0xFF2D6A4F.toInt() else 0xFF1B5E3B.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(if (isDark) 0xFF2D4A38.toInt() else 0xFF9CA3AF.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    // ── Manual add trip dialog ────────────────────────────────────────────────

    private fun showManualAddTripDialog() {
        // v4.1-alpha: Use calendar and time pickers instead of raw text fields
        val now = Calendar.getInstance()
        val pickedDate  = now.clone() as Calendar
        var startHour   = now.get(Calendar.HOUR_OF_DAY)
        var startMinute = now.get(Calendar.MINUTE)
        var endHour     = startHour
        var endMinute   = (startMinute + 20).let { if (it >= 60) it - 60 else it }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(0xFF6B7280.toInt()); setPadding(0, 12, 0, 4)
        }
        fun selectorButton(hint: String) = Button(this).apply {
            text = hint; textSize = 13f
            setTextColor(0xFF1B5E3B.toInt())
            background = null
            setBackgroundColor(0x10000000)
            setPadding(12, 12, 12, 12)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            stateListAnimator = null
        }

        val btnDate  = selectorButton("Tap to select date")
        val btnStart = selectorButton("Tap to select start time")
        val btnEnd   = selectorButton("Tap to select end time")

        val sdfDate = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        val sdfTime = SimpleDateFormat("HH:mm", Locale.ENGLISH)

        fun refreshLabels() {
            btnDate.text  = sdfDate.format(pickedDate.time)
            btnStart.text = "%02d:%02d".format(startHour, startMinute)
            btnEnd.text   = "%02d:%02d".format(endHour, endMinute)
        }
        refreshLabels()

        btnDate.setOnClickListener {
            android.app.DatePickerDialog(this, R.style.TripTracker_Dialog,
                { _, y, m, d ->
                    pickedDate.set(y, m, d)
                    refreshLabels()
                },
                pickedDate.get(Calendar.YEAR),
                pickedDate.get(Calendar.MONTH),
                pickedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnStart.setOnClickListener {
            android.app.TimePickerDialog(this, R.style.TripTracker_Dialog,
                { _, h, m -> startHour = h; startMinute = m; refreshLabels() },
                startHour, startMinute, true
            ).show()
        }

        btnEnd.setOnClickListener {
            android.app.TimePickerDialog(this, R.style.TripTracker_Dialog,
                { _, h, m -> endHour = h; endMinute = m; refreshLabels() },
                endHour, endMinute, true
            ).show()
        }

        val etFare    = EditText(this).apply {
            hint = "0.00"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etPickup  = EditText(this).apply { hint = "Pickup location (optional)"; textSize = 13f }
        val etDropoff = EditText(this).apply { hint = "Drop-off location (optional)"; textSize = 13f }

        val serviceOptions = arrayOf("Premium","Standard","Standard | 6 seats",
            "Premium | 4 seats","Premium | 6 seats","Standard (JustGrab)")
        val serviceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, serviceOptions)
        }

        listOf(
            label("Date"), btnDate,
            label("Start Time"), btnStart,
            label("End Time"), btnEnd,
            label("Fare (S$)"), etFare,
            label("Service Type"), serviceSpinner,
            label("Pickup (optional)"), etPickup,
            label("Drop-off (optional)"), etDropoff
        ).forEach { layout.addView(it) }

        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Add Trip")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val dateStr  = sdfDate.format(pickedDate.time)
                val startStr = "%02d:%02d".format(startHour, startMinute)
                val endStr   = "%02d:%02d".format(endHour, endMinute)
                val fareVal  = etFare.text.toString().toDoubleOrNull()
                val pickup   = etPickup.text.toString().trim()
                val dropoff  = etDropoff.text.toString().trim()
                val service  = serviceSpinner.selectedItem?.toString() ?: "Premium"
                val dayName  = SimpleDateFormat("EEEE", Locale.ENGLISH).format(pickedDate.time)

                val startCal = pickedDate.clone() as Calendar
                startCal.set(Calendar.HOUR_OF_DAY, startHour)
                startCal.set(Calendar.MINUTE, startMinute)
                startCal.set(Calendar.SECOND, 0)
                val endCal = pickedDate.clone() as Calendar
                endCal.set(Calendar.HOUR_OF_DAY, endHour)
                endCal.set(Calendar.MINUTE, endMinute)
                endCal.set(Calendar.SECOND, 0)

                val trip = Trip(
                    date         = dateStr,
                    dayName      = dayName,
                    startTimeStr = startStr,
                    endTimeStr   = endStr,
                    startMs      = startCal.timeInMillis,
                    endMs        = endCal.timeInMillis,
                    fare         = fareVal,
                    pickup       = pickup,
                    dropoff      = dropoff,
                    serviceType  = service,
                    payment      = "GrabPay"
                )

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.tripDao().insert(trip)
                        storeSync.smartPushTrips(db.tripDao().getAll())
                    }
                    refreshTrips()
                    Toast.makeText(this@MainActivity, "Trip added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun refreshTrips() {
        lifecycleScope.launch {
            val allTrips = withContext(Dispatchers.IO) { db.tripDao().getAll() }
            updateTodayStats(allTrips)
            renderTripList(filterTrips(allTrips))
        }
    }

    private fun filterTrips(trips: List<Trip>): List<Trip> {
        val cal = Calendar.getInstance()
        val filtered = when (activeFilter) {
            "Today" -> {
                val todayStr = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
                trips.filter { it.date == todayStr }
            }
            "Week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                val weekStart = cal.timeInMillis
                trips.filter { it.startMs >= weekStart || parseDate(it.date) >= weekStart }
            }
            "Month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                val monthStart = cal.timeInMillis
                trips.filter { it.startMs >= monthStart || parseDate(it.date) >= monthStart }
            }
            "Year" -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                val yearStart = cal.timeInMillis
                trips.filter { it.startMs >= yearStart || parseDate(it.date) >= yearStart }
            }
            else -> trips // All
        }
        // v4.1-alpha: trips with startMs=0 (manually added before fix) sort by date+time string
        return filtered.sortedByDescending { trip ->
            if (trip.startMs > 0L) trip.startMs
            else {
                try {
                    val sdf = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH)
                    sdf.parse("${trip.date} ${trip.startTimeStr}")?.time ?: 0L
                } catch (_: Exception) { 0L }
            }
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    // ── Sync status display ───────────────────────────────────────────────────

    private fun updateSyncStatus(ok: Boolean, customMsg: String? = null) {
        val tv = if (::tvSyncStatus.isInitialized) tvSyncStatus else return
        if (customMsg != null) {
            tv.text = customMsg
            tv.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
            tv.setOnClickListener(null)
            return
        }
        if (ok) {
            val lastMs = storeSync.getLastSyncMs()
            val timeStr = if (lastMs > 0) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(lastMs))
            } else "just now"
            tv.text = "\u2713 Synced $timeStr"
            tv.setTextColor(android.graphics.Color.parseColor("#4ADE80"))
            tv.setOnClickListener(null)
        } else {
            tv.text = "\u26A0 Not synced — tap Sync"
            tv.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
            tv.setOnClickListener { syncToStore() }
        }
    }

    private fun syncFromStoreOnStart() {
        if (!storeSync.isAuthenticated() && !storeSync.hasStoredCredentials()) return
        lifecycleScope.launch {
            // Auto-refresh expired token using stored PIN
            val authed = withContext(Dispatchers.IO) { storeSync.autoRefreshTokenIfNeeded() }
            if (!authed) {
                updateSyncStatus(false, "Sign in to sync")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                try { storeSync.fetchTrips() } catch (_: Exception) { null }
            }
            if (result != null && result.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    db.tripDao().deleteAll()
                    result.forEach { db.tripDao().insert(it) }
                }
                refreshTrips()
                val ok = withContext(Dispatchers.IO) { storeSync.smartPushTrips(db.tripDao().getAll()) }
                updateSyncStatus(ok)
            } else {
                val local = withContext(Dispatchers.IO) { db.tripDao().getAll() }
                val ok = if (local.isNotEmpty()) {
                    withContext(Dispatchers.IO) { storeSync.smartPushTrips(local) }
                } else true
                updateSyncStatus(ok)
            }
        }
    }

    private fun updateHeader() {
        val now = Date()
        tvDate.text = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH).format(now).uppercase()
    }

    @SuppressLint("SetTextI18n")
    private fun updateTodayStats(trips: List<Trip>) {
        val todayStr = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
        val todayTrips = trips.filter { t ->
            t.date == todayStr &&
            !t.cancelled &&
            (t.distanceKm > 0.0 || (t.fare != null && t.fare > 0.0))
        }
        val todayEarnings = todayTrips.sumOf { (it.fare ?: 0.0) + it.tip }
        val count = todayTrips.size
        tvTodayEarnings.text = "S$%.2f".format(todayEarnings)
        tvTripCount.text = if (count == 0) "No trips today"
            else "$count trip${if (count != 1) "s" else ""} today"
    }

    // ── Trip list ─────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun renderTripList(trips: List<Trip>) {
        tripListContainer.removeAllViews()

        if (trips.isEmpty()) {
            val empty = TextView(this).apply {
                val filterLabel = when (activeFilter) {
                    "Today" -> "today"
                    "Week"  -> "this week"
                    "Month" -> "this month"
                    "Year"  -> "this year"
                    else    -> "yet"
                }
                text = if (activeFilter == "All")
                    "No trips recorded yet.\nStart the overlay and tap the green button when a trip begins."
                else
                    "No trips recorded $filterLabel."
                textSize = 14f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, 60, 0, 60)
                gravity = android.view.Gravity.CENTER
            }
            tripListContainer.addView(empty)
            return
        }

        val isDarkMode = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        // v4.1-alpha: track last date to insert date separator headers between days
        var lastDate = ""

        trips.forEachIndexed { index, trip ->
            // Date separator — shown when date changes between trips
            if (trip.date != lastDate) {
                lastDate = trip.date
                // Format: "Monday, 25 May 2026" or "Today" / "Yesterday"
                val todayStr     = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
                val cal          = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val yesterdayStr = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(cal.time)
                val headerLabel  = when (trip.date) {
                    todayStr     -> "Today  ·  ${trip.date}"
                    yesterdayStr -> "Yesterday  ·  ${trip.date}"
                    else         -> try {
                        val d = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).parse(trip.date)!!
                        SimpleDateFormat("EEEE  ·  dd MMM yyyy", Locale.ENGLISH).format(d)
                    } catch (_: Exception) { trip.date }
                }

                val header = TextView(this).apply {
                    text = headerLabel.uppercase()
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (isDarkMode) 0xFF5A8A6A.toInt() else 0xFF6B7280.toInt())
                    setPadding(16.dpToPx(), 20.dpToPx(), 16.dpToPx(), 8.dpToPx())
                    letterSpacing = 0.08f
                }
                tripListContainer.addView(header)
            }
            val row = layoutInflater.inflate(R.layout.item_trip, tripListContainer, false)
            val num = trips.size - index

            row.findViewById<TextView>(R.id.tvTripMeta)?.text =
                "Trip #$num  ·  ${trip.date}  ·  ${trip.startTimeStr} → ${trip.endTimeStr}"

            row.findViewById<TextView>(R.id.tvTripRoute)?.text =
                if (trip.cancelled) "Cancelled"
                else if (trip.pickup.isNotEmpty() || trip.dropoff.isNotEmpty())
                    "${trip.pickup.take(30)}${if (trip.dropoff.isNotEmpty()) " → ${trip.dropoff.take(30)}" else ""}"
                else trip.serviceType

            val fareStr = when {
                trip.cancelled -> "—"
                trip.fare != null && trip.fare > 0 -> "S$%.2f".format((trip.fare) + trip.tip)
                else -> "Add fare"
            }
            val fareTv = row.findViewById<TextView>(R.id.tvTripFare)
            fareTv?.text = fareStr
            fareTv?.setTextColor(when {
                trip.cancelled -> 0xFF6B7280.toInt()
                trip.fare == null || trip.fare == 0.0 -> 0xFFD97706.toInt()
                isDarkMode -> 0xFFA7C4A0.toInt()
                else -> 0xFF111827.toInt()
            })

            val cancelledBg = if (isDarkMode) 0xFF141F18.toInt() else 0xFFFAFAFA.toInt()
            val dividerCol  = if (isDarkMode) 0xFF2D4A38.toInt() else 0xFFE5E9E6.toInt()

            row.findViewById<TextView>(R.id.tvChipDist)?.text =
                if (trip.distanceKm > 0) "%.2f km".format(trip.distanceKm) else ""

            val durParts = mutableListOf<String>()
            if (trip.durationMin > 0) durParts.add("·  ${trip.durationMin.toInt()} min")
            if (trip.waitTimeMins > 0) durParts.add("·  Wait ${trip.waitTimeMins.toInt()}min")
            if (trip.stopCount > 0) durParts.add("·  ${trip.stopCount} stop${if (trip.stopCount > 1) "s" else ""}")
            if (trip.destinationChanged) durParts.add("·  Dest ⤳")
            row.findViewById<TextView>(R.id.tvChipDur)?.text = durParts.joinToString("  ")

            if (trip.cancelled) { row.setBackgroundColor(cancelledBg); row.alpha = 0.6f }

            if (!trip.cancelled) row.setOnClickListener { showEditDialog(trip) }

            tripListContainer.addView(row)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(dividerCol)
            }
            tripListContainer.addView(divider)
        }
    }

    // ── Edit trip dialog ──────────────────────────────────────────────────────

    private fun showEditDialog(trip: Trip) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_trip, null)

        view.findViewById<EditText>(R.id.etEditFare)?.setText(trip.fare?.let { "%.2f".format(it) } ?: "")
        view.findViewById<EditText>(R.id.etEditTip)?.setText(if (trip.tip > 0) "%.2f".format(trip.tip) else "")
        view.findViewById<EditText>(R.id.etEditStart)?.setText(trip.startTimeStr)
        view.findViewById<EditText>(R.id.etEditEnd)?.setText(trip.endTimeStr)
        view.findViewById<EditText>(R.id.etEditPickup)?.setText(trip.pickup)
        view.findViewById<EditText>(R.id.etEditDropoff)?.setText(trip.dropoff)
        view.findViewById<EditText>(R.id.etEditNotes)?.setText(trip.notes)

        val serviceOptions = arrayOf("Premium","Standard","Standard | 6 seats",
            "Premium | 4 seats","Premium | 6 seats","Standard (JustGrab)")
        val serviceSpinner = view.findViewById<Spinner>(R.id.spinEditService)
        serviceSpinner?.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, serviceOptions)
        serviceSpinner?.setSelection(serviceOptions.indexOfFirst { it == trip.serviceType }.coerceAtLeast(0))

        val paymentOptions = arrayOf("GrabPay", "Cash")
        val paymentSpinner = view.findViewById<Spinner>(R.id.spinEditPayment)
        paymentSpinner?.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, paymentOptions)
        paymentSpinner?.setSelection(if (trip.payment == "Cash") 1 else 0)

        val dialog = AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Edit Trip  ·  ${trip.startTimeStr} → ${trip.endTimeStr}")
            .setView(view)
            .create()

        view.findViewById<Button>(R.id.btnEditSave)?.setOnClickListener {
            val fareVal  = view.findViewById<EditText>(R.id.etEditFare)?.text?.toString()?.toDoubleOrNull()
            val tipVal   = view.findViewById<EditText>(R.id.etEditTip)?.text?.toString()?.toDoubleOrNull() ?: 0.0
            val startStr = view.findViewById<EditText>(R.id.etEditStart)?.text?.toString() ?: trip.startTimeStr
            val endStr   = view.findViewById<EditText>(R.id.etEditEnd)?.text?.toString() ?: trip.endTimeStr
            val pickup   = view.findViewById<EditText>(R.id.etEditPickup)?.text?.toString()?.trim() ?: ""
            val dropoff  = view.findViewById<EditText>(R.id.etEditDropoff)?.text?.toString()?.trim() ?: ""
            val notes    = view.findViewById<EditText>(R.id.etEditNotes)?.text?.toString()?.trim() ?: ""
            val service  = serviceSpinner?.selectedItem?.toString() ?: trip.serviceType
            val payment  = paymentSpinner?.selectedItem?.toString() ?: trip.payment

            val updated = trip.copy(
                fare = if (fareVal != null && fareVal > 0) fareVal else null,
                tip = tipVal, startTimeStr = startStr, endTimeStr = endStr,
                pickup = pickup, dropoff = dropoff, notes = notes,
                serviceType = service, payment = payment
            )

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.tripDao().update(updated)
                    storeSync.smartPushTrips(db.tripDao().getAll())
                }
                refreshTrips()
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Trip updated", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btnEditDelete)?.setOnClickListener {
            AlertDialog.Builder(this, R.style.TripTracker_Dialog)
                .setTitle("Delete this trip?")
                .setMessage("${trip.date}  ·  ${trip.startTimeStr} → ${trip.endTimeStr}" +
                    (if (trip.fare != null) "  ·  S$%.2f".format(trip.fare) else ""))
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.tripDao().delete(trip)
                            storeSync.smartPushTrips(db.tripDao().getAll())
                        }
                        refreshTrips()
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Trip deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }

        dialog.show()
    }

    // ── CSV Import ────────────────────────────────────────────────────────────

    private fun launchCsvImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select CSV export"), REQ_CSV_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CSV_IMPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri -> importCsvFromUri(uri) }
        }
    }

    private fun importCsvFromUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val reader = BufferedReader(InputStreamReader(contentResolver.openInputStream(uri)))
                    val lines = reader.readLines()
                    reader.close()
                    if (lines.size < 2) return@withContext Pair(0, "File is empty or has no data rows")

                    val headers = parseCsvLine(lines[0])
                    fun col(name: String) = headers.indexOfFirst {
                        it.trim().equals(name, ignoreCase = true)
                    }

                    val iDate    = col("Date")
                    val iDay     = col("Day")
                    val iStart   = col("Start Time")
                    val iEnd     = col("End Time")
                    val iPickup  = col("Pickup")
                    val iDropoff = col("Drop-off")
                    val iService = col("Service Type")
                    val iPayment = col("Payment")
                    val iDist    = col("Distance (km)")
                    val iDur     = col("Duration (mins)")
                    val iFare    = col("Net Earnings (S\$)")
                    val iTip     = col("Tip (S\$)")
                    val iWait    = col("Wait Time (mins)")
                    val iNotes   = col("Notes")
                    val iAvgSpd  = col("Avg Speed (km/h)")
                    val iMaxSpd  = col("Max Speed (km/h)")
                    val iSLat    = col("Start Lat")
                    val iSLon    = col("Start Lon")
                    val iELat    = col("End Lat")
                    val iELon    = col("End Lon")

                    if (iDate < 0 || iFare < 0) {
                        return@withContext Pair(0, "CSV format not recognised — make sure it was exported from Trip Tracker")
                    }

                    var imported = 0
                    for (line in lines.drop(1)) {
                        if (line.isBlank()) continue
                        val cols = parseCsvLine(line)
                        fun get(i: Int) = if (i >= 0 && i < cols.size) cols[i].trim() else ""

                        val dateStr = get(iDate)
                        if (dateStr.isEmpty()) continue

                        val trip = Trip(
                            date         = dateStr,
                            dayName      = get(iDay),
                            startTimeStr = get(iStart),
                            endTimeStr   = get(iEnd),
                            startMs      = 0L,
                            endMs        = 0L,
                            pickup       = get(iPickup),
                            dropoff      = get(iDropoff),
                            serviceType  = get(iService).ifEmpty { "Premium" },
                            payment      = get(iPayment).ifEmpty { "GrabPay" },
                            distanceKm   = get(iDist).toDoubleOrNull() ?: 0.0,
                            durationMin  = get(iDur).toDoubleOrNull() ?: 0.0,
                            fare         = get(iFare).toDoubleOrNull(),
                            tip          = get(iTip).toDoubleOrNull() ?: 0.0,
                            waitTimeMin  = get(iWait).toIntOrNull() ?: 0,
                            notes        = get(iNotes),
                            avgSpeedKmh  = get(iAvgSpd).toDoubleOrNull() ?: 0.0,
                            maxSpeedKmh  = get(iMaxSpd).toDoubleOrNull() ?: 0.0,
                            startLat     = get(iSLat).toDoubleOrNull(),
                            startLon     = get(iSLon).toDoubleOrNull(),
                            endLat       = get(iELat).toDoubleOrNull(),
                            endLon       = get(iELon).toDoubleOrNull()
                        )
                        db.tripDao().insert(trip)
                        imported++
                    }

                    storeSync.smartPushTrips(db.tripDao().getAll())
                    Pair(imported, null)
                } catch (e: Exception) {
                    Pair(0, "Error reading file: ${e.message}")
                }
            }

            val (count, error) = result
            if (error != null) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            } else {
                refreshTrips()
                Toast.makeText(this@MainActivity,
                    "$count trips imported successfully", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    // ── CSV Export ────────────────────────────────────────────────────────────

    private fun buildCsv(trips: List<Trip>): String {
        val headers = listOf("Date","Day","Start Time","End Time","Pickup","Drop-off",
            "Service Type","Payment","Distance (km)","Duration (mins)",
            "Net Earnings (S\$)","Tip (S\$)","Wait Time (mins)","Promo","Notes",
            "S\$/km","S\$/min","Avg Speed (km/h)","Max Speed (km/h)",
            "Start Lat","Start Lon","End Lat","End Lon")
        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(","))
        trips.forEach { t ->
            val epk = if (t.distanceKm > 0 && t.fare != null) "%.2f".format(t.fare / t.distanceKm) else ""
            val epm = if (t.durationMin > 0 && t.fare != null) "%.2f".format(t.fare / t.durationMin) else ""
            sb.appendLine(listOf(
                csv(t.date), csv(t.dayName), csv(t.startTimeStr), csv(t.endTimeStr),
                csv(t.pickup), csv(t.dropoff), csv(t.serviceType), csv(t.payment),
                t.distanceKm, t.durationMin, t.fare ?: "", t.tip, t.waitTimeMin,
                csv(t.promo), csv(t.notes), epk, epm,
                t.avgSpeedKmh, t.maxSpeedKmh,
                t.startLat ?: "", t.startLon ?: "", t.endLat ?: "", t.endLon ?: ""
            ).joinToString(","))
        }
        return sb.toString()
    }

    private fun csv(v: String) =
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            "\"${v.replace("\"", "\"\"")}\"" else v

    private fun shareCsv(csv: String) {
        val dateStr = SimpleDateFormat("dd_MMM_yyyy", Locale.ENGLISH).format(Date())
        val file = File(cacheDir, "trips_$dateStr.csv")
        file.writeText(csv)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export trips CSV"))
    }

    // ── Central Store ─────────────────────────────────────────────────────────

    private fun showSyncLoginDialog() {
        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Connect to Central Store")
            .setMessage("Do you have an existing Trip Tracker account, or do you need to create one?")
            .setPositiveButton("I have an account") { _, _ -> showSignInDialog() }
            .setNeutralButton("Create account") { _, _ -> showRegisterDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSignInDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8)
        }
        val userIdInput = EditText(this).apply {
            hint = "Username (e.g. roy)"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pinInput = EditText(this).apply {
            hint = "PIN"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply {
            text = "Username"; textSize = 12f
            setTextColor(0xFF6B7280.toInt()); setPadding(0, 0, 0, 4)
        })
        layout.addView(userIdInput)
        layout.addView(TextView(this).apply {
            text = "PIN"; textSize = 12f
            setTextColor(0xFF6B7280.toInt()); setPadding(0, 16, 0, 4)
        })
        layout.addView(pinInput)

        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Sign In")
            .setView(layout)
            .setPositiveButton("Sign In") { _, _ ->
                val userId = userIdInput.text.toString().trim().lowercase()
                val pin    = pinInput.text.toString().trim()
                if (userId.isEmpty() || pin.isEmpty()) {
                    Toast.makeText(this, "Enter your username and PIN", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performSignIn(userId, pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRegisterDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8)
        }
        val userIdInput = EditText(this).apply {
            hint = "Choose a username (e.g. roy)"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pinInput = EditText(this).apply {
            hint = "Choose a PIN (min 4 digits)"; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply {
            text = "Username  ·  3-32 characters, letters and numbers only"
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 0, 0, 4)
        })
        layout.addView(userIdInput)
        layout.addView(TextView(this).apply {
            text = "PIN"; textSize = 12f
            setTextColor(0xFF6B7280.toInt()); setPadding(0, 16, 0, 4)
        })
        layout.addView(pinInput)
        layout.addView(TextView(this).apply {
            text = "Save your reset code after registering - it will only be shown once."
            textSize = 11f; setTextColor(0xFFD97706.toInt()); setPadding(0, 12, 0, 0)
        })

        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Create Account")
            .setView(layout)
            .setPositiveButton("Register") { _, _ ->
                val userId = userIdInput.text.toString().trim().lowercase()
                val pin    = pinInput.text.toString().trim()
                if (userId.length < 3) {
                    Toast.makeText(this, "Username must be at least 3 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performRegister(userId, pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSignIn(userId: String, pin: String) {
        Toast.makeText(this, "Signing in...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { storeSync.authenticate(userId, pin) }
            if (result.success) {
                storeSync.saveCredentials(userId, pin, result.token, result.expiryUnixSeconds)
                val remoteTrips = withContext(Dispatchers.IO) {
                    try { storeSync.fetchTrips() } catch (_: Exception) { null }
                }
                if (remoteTrips != null && remoteTrips.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.tripDao().deleteAll()
                        remoteTrips.forEach { db.tripDao().insert(it) }
                    }
                    refreshTrips()
                    Toast.makeText(this@MainActivity,
                        "${remoteTrips.size} trips restored from store",
                        Toast.LENGTH_LONG).show()
                } else {
                    val localTrips = withContext(Dispatchers.IO) { db.tripDao().getAll() }
                    if (localTrips.isNotEmpty()) {
                        withContext(Dispatchers.IO) { storeSync.pushTrips(localTrips) }
                        Toast.makeText(this@MainActivity,
                            "Connected - ${localTrips.size} trips pushed to store",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Connected to store", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this@MainActivity, result.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performRegister(userId: String, pin: String) {
        Toast.makeText(this, "Creating account...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { storeSync.register(userId, pin) }
            if (result.success) {
                storeSync.saveCredentials(userId, pin, result.token, result.expiryUnixSeconds)
                val localTrips = withContext(Dispatchers.IO) { db.tripDao().getAll() }
                if (localTrips.isNotEmpty()) {
                    withContext(Dispatchers.IO) { storeSync.pushTrips(localTrips) }
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Account created")
                    .setMessage("Your reset code is:\n\n${result.resetCode}\n\nWrite this down. If you forget your PIN, you will need this code to reset it. It will NOT be shown again.")
                    .setPositiveButton("I've saved it", null)
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, result.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncToStore() {
        Toast.makeText(this, "Syncing...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val remoteTrips = withContext(Dispatchers.IO) {
                try { storeSync.fetchTrips() } catch (_: Exception) { null }
            }
            if (remoteTrips != null && remoteTrips.isNotEmpty()) {
                val localCount = withContext(Dispatchers.IO) { db.tripDao().getAll().size }
                if (remoteTrips.size > localCount) {
                    withContext(Dispatchers.IO) {
                        db.tripDao().deleteAll()
                        remoteTrips.forEach { db.tripDao().insert(it) }
                    }
                    refreshTrips()
                    Toast.makeText(this@MainActivity,
                        "${remoteTrips.size} trips loaded from store",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            val ok = withContext(Dispatchers.IO) { storeSync.smartPushTrips(db.tripDao().getAll()) }
            updateSyncStatus(ok)
            Toast.makeText(this@MainActivity,
                if (ok) "Synced to store" else "Sync failed - check connection or sign in again",
                Toast.LENGTH_LONG).show()
        }
    }

    // ── Settings menu ─────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val isDark    = prefs.getBoolean("dark_mode", false)
        val connected = storeSync.isAuthenticated()
        val userId    = storeSync.getUserId()
        val hasNotifAccess = isNotificationListenerEnabled()

        val options = arrayOf(
            if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
            "Import CSV (restore trips)",
            if (connected) "Disconnect store (${userId})" else "Connect to Central Store",
            if (hasNotifAccess) "Auto-start (Notification access granted)"
                else "Enable auto-start (Notification access)",
            "Reset AS capture data",
            "Clear all trip data"
        )
        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleDarkMode()
                    1 -> launchCsvImport()
                    2 -> if (connected) {
                        storeSync.clearCredentials()
                        Toast.makeText(this, "Store disconnected", Toast.LENGTH_SHORT).show()
                    } else {
                        showSyncLoginDialog()
                    }
                    3 -> if (!hasNotifAccess) showNotificationPermissionDialog()
                        else Toast.makeText(this,
                            "Auto-start is active - Grab bookings will auto-start trips",
                            Toast.LENGTH_LONG).show()
                    4 -> confirmClearMetadata()
                    5 -> confirmClearAll()
                }
            }.show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Enable Auto-Start")
            .setMessage("Trip Tracker can automatically start tracking when it detects a Grab booking notification.\n\nTo enable:\n1. Tap Open Settings\n2. Find Trip Tracker in the list\n3. Toggle it ON\n\nTrip Tracker will only read Grab notifications - nothing else.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(android.content.Intent(
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun toggleDarkMode() {
        val isDark = prefs.getBoolean("dark_mode", false)
        val newMode = !isDark
        prefs.edit().putBoolean("dark_mode", newMode).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        Toast.makeText(this,
            if (newMode) "Dark mode on" else "Light mode on",
            Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearMetadata() {
        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Reset AS capture data?")
            .setMessage("This clears all Accessibility Service captured data (addresses, passenger names, estimated fares).\n\nYour trip records and earnings are NOT affected.")
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.tripMetadataDao().deleteAll() }
                    Toast.makeText(this@MainActivity,
                        "AS capture data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this, R.style.TripTracker_Dialog)
            .setTitle("Delete all trip data?")
            .setMessage("This permanently deletes ALL trips. Export CSV first if you need the data.")
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.tripDao().deleteAll() }
                    refreshTrips()
                    Toast.makeText(this@MainActivity, "All trips deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val REQ_CSV_IMPORT = 2001
    }
}

