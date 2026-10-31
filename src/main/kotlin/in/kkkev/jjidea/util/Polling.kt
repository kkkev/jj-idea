package `in`.kkkev.jjidea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

private val log = Logger.getInstance("in.kkkev.jjidea.util.Polling")

/** Default [launchPolling] gate: only poll while the IDE has focus. */
internal fun ideIsActive() = ApplicationManager.getApplication().isActive

/**
 * Runs [task] repeatedly, [interval] apart, until the returned [Job] (or this scope) is cancelled.
 *
 * Generic periodic-work primitive with no jj knowledge; call it from any service that has a
 * [CoroutineScope] (a constructor-injected `cs` on a `@Service` is cancelled with its project).
 * Consumers that need change detection keep their own last value inside [task].
 *
 * - **Sequential:** the next wait starts after [task] finishes, so a slow task delays the next run
 *   and never overlaps itself.
 * - **Gated:** while [shouldRun] is false a tick is skipped (default: IDE inactive), costing nothing.
 * - **Backoff:** [task] returning false, or throwing, is a failure. The wait then doubles per
 *   consecutive failure, capped at [maxBackoff] × [interval]; a success resets it. A distinct
 *   exception message is logged at DEBUG once, so a flapping network does not spam the log.
 * - [interval] is re-read every tick, so a settings change applies without restarting.
 *
 * [waiter] is the test seam: replace it to run on virtual time.
 */
fun CoroutineScope.launchPolling(
    name: String,
    interval: () -> Duration,
    shouldRun: () -> Boolean = ::ideIsActive,
    maxBackoff: Int = 10,
    waiter: suspend (Duration) -> Unit = { delay(it) },
    task: suspend () -> Boolean
): Job = launch(Dispatchers.IO + CoroutineName(name)) {
    var failures = 0
    var lastError: String? = null
    while (isActive) {
        val base = interval()
        val factor = if (failures == 0) 1L else minOf(1L shl minOf(failures, 20), maxBackoff.toLong())
        waiter(base * factor.toInt())
        if (!shouldRun()) continue
        val ok = try {
            task()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message != lastError) log.debug("Polling '$name' failed", e)
            lastError = e.message
            false
        }
        if (ok) {
            failures = 0
            lastError = null
        } else {
            failures++
        }
    }
}
