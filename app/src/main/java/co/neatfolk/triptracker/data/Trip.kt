package co.neatfolk.triptracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Core time fields
    val date: String = "",
    val dayName: String = "",
    val startTimeStr: String = "",
    val endTimeStr: String = "",
    val startMs: Long = 0,
    val endMs: Long = 0,

    // Trip measurements
    val durationMin: Double = 0.0,
    val distanceKm: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,

    // Financials
    val fare: Double? = null,
    val tip: Double = 0.0,
    val promo: String = "No",

    // Status
    val waitTimeMin: Int = 0,
    val cancelled: Boolean = false,

    // Route
    val pickup: String = "",
    val dropoff: String = "",
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,

    // Booking details
    val serviceType: String = "Premium",
    val payment: String = "GrabPay",
    val notes: String = "",

    // ── NEW: Passenger picked up milestone ────────────────────────────────────
    val passengerPickedUpMs: Long? = null,       // epoch ms when pickup tapped
    val passengerPickedUpTimeStr: String = "",   // "HH:mm" display
    val waitTimeMins: Double = 0.0,              // time from trip start to pickup tap
    val pickupLat: Double? = null,               // GPS at moment of pickup
    val pickupLon: Double? = null,
    val pickupAutoDetected: Boolean = false,     // v4.3-alpha: true if GPS stop/resume detected it, false if manual tap

    // ── NEW: Multi-stop support ───────────────────────────────────────────────
    val isMultiStop: Boolean = false,
    val stopCount: Int = 0,
    val stopsJson: String = "",                  // JSON array of stop objects

    // ── NEW: Destination change ───────────────────────────────────────────────
    val destinationChanged: Boolean = false,
    val originalDropoff: String = "",            // original dropoff from booking
    val actualDropoff: String = "",              // actual dropoff at end

    // ── NEW: Auto-start source ────────────────────────────────────────────────
    val autoStarted: Boolean = false,            // true if started via notification listener

    // Sync state
    val synced: Boolean = false
) {
    fun netEarnings(): Double = (fare ?: 0.0) + tip
    fun earningsPerKm(): Double? =
        if (distanceKm > 0 && fare != null && fare > 0) fare / distanceKm else null
    fun earningsPerMin(): Double? =
        if (durationMin > 0 && fare != null && fare > 0) fare / durationMin else null
}
