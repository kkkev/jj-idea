package `in`.kkkev.jjidea.ui

import com.intellij.util.text.DateFormatUtil
import kotlinx.datetime.Instant
import java.util.*

/**
 * Formats date/times consistently across the plugin, matching Git plugin style.
 *
 * Format:
 * - "Today HH:MM" for today's commits
 * - "Yesterday HH:MM" for yesterday's commits
 * - Localized date/time format for older commits
 */
object DateTimeFormatter {

    /**
     * Format timestamp in Git plugin style with proper locale support.
     */
    fun formatRelative(instant: Instant): String {
        val millis = instant.toEpochMilliseconds()
        val now = System.currentTimeMillis()

        return when {
            isSameDay(now, millis) -> {
                val time = DateFormatUtil.formatTime(millis)
                "Today $time"
            }

            isYesterday(now, millis) -> {
                val time = DateFormatUtil.formatTime(millis)
                "Yesterday $time"
            }

            else -> {
                // Use IntelliJ's locale-aware date/time formatting
                DateFormatUtil.formatDateTime(millis)
            }
        }
    }

    fun formatAbsolute(instant: Instant): String = DateFormatUtil.formatPrettyDateTime(instant.toEpochMilliseconds())

    /**
     * Check if two timestamps are on the same day.
     */
    private fun isSameDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Check if timestamp is yesterday relative to now.
     */
    private fun isYesterday(now: Long, millis: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = now }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis }
        cal1.add(Calendar.DAY_OF_YEAR, -1)
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
