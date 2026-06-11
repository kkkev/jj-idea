package `in`.kkkev.jjidea.util

import com.intellij.openapi.diagnostic.Logger

/** WARN threshold: operations longer than this emit WARN instead of INFO. */
const val PERF_DURATION_WARN_MS = 500L

/** WARN threshold: any work count above this emits WARN instead of INFO. */
const val PERF_COUNT_WARN = 50_000L

/**
 * Accumulates named work counts during a measured operation.
 * Counts are assigned from primitive locals by the caller; no per-iteration allocation.
 */
class PerfReport {
    private val counts = LinkedHashMap<String, Long>()

    fun count(name: String, value: Long) {
        counts[name] = value
    }

    @PublishedApi internal fun maxCount() = counts.values.maxOrNull() ?: 0L

    @PublishedApi internal fun format() =
        if (counts.isEmpty()) "" else counts.entries.joinToString(", ") { (k, v) -> "$k=%,d".format(v) }
}

/**
 * Times [block], then logs `perf: <operation> took <ms>ms [<counts>] (<context>)`.
 *
 * Logs at WARN when duration exceeds [durationWarnMs] or any count exceeds [countWarnThreshold];
 * INFO otherwise. Format is greppable on `perf:` and WARN lines flag scale regressions.
 *
 * Usage:
 * ```kotlin
 * val result = log.measurePerf("ignore-scan", repo.directory.name) { report ->
 *     val stats = cache.collectIgnored(root, checkCanceled, onIgnored)
 *     report.count("visited", stats.visited)
 *     report.count("ignored", stats.ignored)
 *     result
 * }
 * ```
 */
inline fun <T> Logger.measurePerf(
    operation: String,
    context: String = "",
    durationWarnMs: Long = PERF_DURATION_WARN_MS,
    countWarnThreshold: Long = PERF_COUNT_WARN,
    block: (PerfReport) -> T
): T {
    val report = PerfReport()
    val start = System.currentTimeMillis()
    val result = block(report)
    val durationMs = System.currentTimeMillis() - start
    val counts = report.format()
    val message = buildString {
        append("perf: $operation took ${durationMs}ms")
        if (counts.isNotEmpty()) append(" [$counts]")
        if (context.isNotEmpty()) append(" ($context)")
    }
    if (durationMs > durationWarnMs || report.maxCount() > countWarnThreshold) warn(message) else info(message)
    return result
}
