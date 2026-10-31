package `in`.kkkev.jjidea.util

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import java.time.Duration
import java.time.Instant

/** "just now", "5 min ago", "3 h ago", "2 d ago": coarse, for tooltips that are rebuilt on every hover. */
fun relativeAgo(then: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> JujutsuBundle.message("time.ago.now")
        d.toHours() < 1 -> JujutsuBundle.message("time.ago.minutes", d.toMinutes())
        d.toDays() < 1 -> JujutsuBundle.message("time.ago.hours", d.toHours())
        else -> JujutsuBundle.message("time.ago.days", d.toDays())
    }
}

/** The plugin's standard "Today 14:32" / "Yesterday 20:36" / locale date-time wording for a point in time. */
fun fetchTimeLabel(at: Instant): String =
    DateTimeFormatter.formatRelative(kotlinx.datetime.Instant.fromEpochMilliseconds(at.toEpochMilli()))
