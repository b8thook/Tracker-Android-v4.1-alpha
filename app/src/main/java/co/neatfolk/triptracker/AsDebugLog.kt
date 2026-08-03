package co.neatfolk.triptracker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.2-alpha: Simple file-based debug log for Accessibility Service diagnostics.
 * - Screen 3 captures log all extracted fields for field validation.
 * - Failed post-trip fare parses dump the exact screen text the AS saw.
 * Viewable via Settings -> View AS debug log. Capped at MAX_LINES.
 */
object AsDebugLog {

    private const val FILE_NAME = "as_debug.log"
    private const val MAX_LINES = 300

    @Synchronized
    fun log(context: Context, tag: String, lines: List<String>) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            val ts = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
            val newLines = mutableListOf("[$ts] $tag")
            for (line in lines) {
                if (line.isNotBlank()) newLines.add("  - " + line.take(140))
            }
            val existing = if (f.exists()) f.readLines() else emptyList()
            val combined = (existing + newLines).takeLast(MAX_LINES)
            f.writeText(combined.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) {
                val text = f.readText()
                if (text.isBlank()) "Log is empty - no AS events recorded yet."
                else text
            } else {
                "Log is empty - no AS events recorded yet."
            }
        } catch (_: Exception) {
            "Could not read log."
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
