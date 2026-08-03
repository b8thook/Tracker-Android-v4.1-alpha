package co.neatfolk.triptracker

import android.view.accessibility.AccessibilityNodeInfo
import co.neatfolk.triptracker.data.TripMetadata

/**
 * Parses Grab Driver app screens from AccessibilityNodeInfo trees.
 * Three screen types: Screen1 (auto-accept alert), Screen3 (Route details), PostTrip (Rate experience).
 *
 * v4.0-alpha fixes:
 * - isPostTrip() now excludes Job Details history screen (has "Route taken" / "Job Details" text)
 * - isScreen3() tightened to avoid false positives on non-route screens
 */
object AccessibilityScreenParser {

    // ── Screen type detection ─────────────────────────────────────────────────

    fun isScreen1(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "Auto-accepted") != null
    }

    fun isScreen3(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "Route details") != null
    }

    fun isPostTrip(root: AccessibilityNodeInfo): Boolean {
        // Must have "Rate your experience" — the actual post-trip rating screen
        val hasRateText = findNodeWithText(root, "Rate your experience") != null
        // Must NOT be the Job Details history screen (which also shows fare amounts)
        val isHistoryScreen = findNodeWithText(root, "Job Details") != null ||
                              findNodeWithText(root, "Route taken") != null ||
                              findNodeWithText(root, "Suggested route") != null
        return hasRateText && !isHistoryScreen
    }

    // ── Screen 1 parser ───────────────────────────────────────────────────────

    fun parseScreen1(root: AccessibilityNodeInfo): TripMetadata {
        val meta = TripMetadata(capturedAtMs = System.currentTimeMillis())

        val autoNode = findNodeWithText(root, "Auto-accepted")
        val autoText = autoNode?.text?.toString() ?: ""
        val tripMin = Regex("""Total (\d+) min""").find(autoText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val pickupAbbrev  = extractLocationLine(root, isPickup = true)
        val dropoffAbbrev = extractLocationLine(root, isPickup = false)

        val allText = collectAllText(root)
        val requiresCarSeat     = allText.any { it.contains("Car seat", ignoreCase = true) }
        val requiresBoosterSeat = allText.any { it.contains("Booster seat", ignoreCase = true) }
        val passengerCount      = allText.firstOrNull { it.matches(Regex("\\d+")) }?.toIntOrNull() ?: 0

        return meta.copy(
            estimatedTripMin    = tripMin,
            pickupAbbrev        = pickupAbbrev,
            dropoffAbbrev       = dropoffAbbrev,
            requiresCarSeat     = requiresCarSeat,
            requiresBoosterSeat = requiresBoosterSeat,
            passengerCount      = passengerCount
        )
    }

    // ── Screen 3 parser ───────────────────────────────────────────────────────

    fun parseScreen3(root: AccessibilityNodeInfo, existing: TripMetadata? = null): TripMetadata {
        val base = existing ?: TripMetadata(capturedAtMs = System.currentTimeMillis())

        val earnNode = findNodeWithText(root, "You'll earn")
        val earnText = earnNode?.parent?.let { collectAllText(it) }?.joinToString(" ")
            ?: collectAllText(root).firstOrNull { it.contains("You'll earn") } ?: ""
        val estimatedFare = parseFareFrom(earnText)
        val hasSurge = earnText.contains("≈") || earnText.contains("surge", ignoreCase = true)

        val pickupSection  = extractSection(root, "Pick up")
        val pickupName     = pickupSection.getOrNull(0) ?: ""
        val pickupAddress  = pickupSection.getOrNull(1) ?: ""
        val passengerName  = pickupSection.getOrNull(2) ?: ""

        val serviceNode    = findNodeWithText(root, "Service")
        val serviceType    = serviceNode?.parent?.let { getNextSiblingText(it) } ?: ""

        val dropoffSection = extractSection(root, "Drop off")
        val dropoffName    = dropoffSection.getOrNull(0) ?: ""
        val dropoffAddress = dropoffSection.getOrNull(1) ?: ""

        val paymentNode    = findNodeWithText(root, "Payment method")
        val paymentRow     = paymentNode?.parent?.let { collectAllText(it) } ?: emptyList()
        val paymentMethod  = when {
            paymentRow.any { it.contains("GrabPay", ignoreCase = true) } -> "GrabPay"
            paymentRow.any { it.contains("Cash", ignoreCase = true) }    -> "Cash"
            else -> ""
        }
        val hasPromo = paymentRow.any { it.contains("Promo", ignoreCase = true) }

        return base.copy(
            passengerName  = passengerName.ifBlank { base.passengerName },
            pickupName     = pickupName.ifBlank { base.pickupName },
            pickupAddress  = pickupAddress.ifBlank { base.pickupAddress },
            dropoffName    = dropoffName.ifBlank { base.dropoffName },
            dropoffAddress = dropoffAddress.ifBlank { base.dropoffAddress },
            serviceType    = serviceType.ifBlank { base.serviceType },
            estimatedFare  = estimatedFare ?: base.estimatedFare,
            paymentMethod  = paymentMethod.ifBlank { base.paymentMethod },
            hasPromo       = hasPromo,
            hasSurge       = hasSurge
        )
    }

    // ── Post-trip parser ──────────────────────────────────────────────────────

    // v4.2-alpha: tolerant fare pattern — optional "S", optional spaces after "$",
    // comma or dot decimals. Handles "S$10.30", "S$ 10.30", "$10.30", "S$10,30".
    private val FARE_REGEX = Regex("""S?\$\s*([0-9]+(?:[.,][0-9]{1,2})?)""")

    private fun parseFareFrom(text: String): Double? {
        return FARE_REGEX.find(text)
            ?.groupValues?.get(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
    }

    fun parsePostTrip(root: AccessibilityNodeInfo): Double? {
        // 1) Preferred: anchor on the "net earnings" label and search text near it,
        //    so unrelated S$ figures elsewhere on screen can't win.
        findNodeWithText(root, "net earnings")?.let { anchor ->
            val container = anchor.parent ?: anchor
            val nearby = collectAllText(container).joinToString(" ")
            parseFareFrom(nearby)?.let { return it }
        }
        // 2) Fallback: scan the whole tree text.
        return parseFareFrom(collectAllText(root).joinToString(" "))
    }

    // v4.2-alpha: expose collected screen text for debug logging when parsing fails.
    fun dumpTexts(root: AccessibilityNodeInfo): List<String> = collectAllText(root)

    // ── Helper: find node containing text ────────────────────────────────────

    private fun findNodeWithText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText    = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) ||
            contentDesc.contains(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeWithText(child, text)?.let { return it }
            }
        }
        return null
    }

    // ── Helper: collect all text strings from a subtree ───────────────────────

    private fun collectAllText(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(collectAllText(it)) }
        }
        return result
    }

    // ── Helper: extract section content after a label ─────────────────────────

    private fun extractSection(root: AccessibilityNodeInfo, sectionLabel: String): List<String> {
        val labelNode = findNodeWithText(root, sectionLabel) ?: return emptyList()
        val parent = labelNode.parent ?: return emptyList()
        val results = mutableListOf<String>()
        var found = false
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val childText = collectAllText(child).joinToString(" ")
            if (!found) {
                if (childText.contains(sectionLabel, ignoreCase = true)) found = true
            } else {
                if (childText.contains("Pick up", ignoreCase = true) ||
                    childText.contains("Drop off", ignoreCase = true) ||
                    childText.contains("Payment method", ignoreCase = true)) break
                val text = childText.trim()
                if (text.isNotBlank()) results.add(text)
            }
        }
        if (results.isEmpty()) {
            val grandParent = parent.parent ?: return emptyList()
            var parentFound = false
            for (i in 0 until grandParent.childCount) {
                val sibling = grandParent.getChild(i) ?: continue
                if (!parentFound) {
                    if (collectAllText(sibling).any { it.contains(sectionLabel, ignoreCase = true) })
                        parentFound = true
                } else {
                    val sibText = collectAllText(sibling)
                    if (sibText.any {
                        it.contains("Pick up", ignoreCase = true) ||
                        it.contains("Drop off", ignoreCase = true) ||
                        it.contains("Payment", ignoreCase = true)
                    }) break
                    results.addAll(sibText.filter { it.isNotBlank() })
                }
            }
        }
        return results.take(4)
    }

    // ── Helper: get first/second location line from Screen 1 ─────────────────

    private fun extractLocationLine(root: AccessibilityNodeInfo, isPickup: Boolean): String {
        val allText = collectAllText(root)
        val locationLines = allText.filter { text ->
            text.length > 5 &&
            !text.contains("Auto-accepted", ignoreCase = true) &&
            !text.contains("Total", ignoreCase = true) &&
            !text.contains("min", ignoreCase = true) &&
            !text.contains("Cancel", ignoreCase = true) &&
            !text.contains("Continue", ignoreCase = true) &&
            !text.contains("Car seat", ignoreCase = true) &&
            !text.contains("Booster", ignoreCase = true) &&
            !text.matches(Regex("\\d+"))
        }
        return if (isPickup) locationLines.getOrNull(0) ?: ""
               else          locationLines.getOrNull(1) ?: ""
    }

    // ── Helper: get text of next sibling ─────────────────────────────────────

    private fun getNextSiblingText(node: AccessibilityNodeInfo): String {
        val parent = node.parent ?: return ""
        var found = false
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (found) return collectAllText(child).firstOrNull() ?: ""
            if (child == node) found = true
        }
        return ""
    }
}
