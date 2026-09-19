package `in`.kkkev.jjidea.ui.editor

import com.intellij.util.Alarm

/**
 * The 300ms-class debounce pattern [in.kkkev.jjidea.jj.JujutsuStateModel.scheduleRepositoryRefresh]
 * uses (cancel any pending request, schedule a fresh one), pulled out here so
 * [JujutsuConflictEditorNotificationProvider]'s `DocumentListener` callback stays O(1) per
 * contributing.md's refresh-path rules - it must never re-scan the whole document per keystroke.
 *
 * [cancelPending] and [scheduleScan] are the two halves of an [Alarm]'s
 * `cancelAllRequests`/`addRequest`, injected separately (rather than taking an `Alarm` directly)
 * so the debounce behaviour - only the last of a burst of calls within one window actually
 * scans - is unit-testable without real threading or wall-clock delay.
 */
internal class DebouncedDocumentScan(
    private val delayMs: Int = 300,
    private val cancelPending: () -> Unit,
    private val scheduleScan: (delayMs: Int, action: () -> Unit) -> Unit,
    private val scan: () -> Unit
) {
    fun onDocumentChanged() {
        cancelPending()
        scheduleScan(delayMs) { scan() }
    }
}

/** Wires a [DebouncedDocumentScan] to a real [Alarm]. */
internal fun debouncedDocumentScan(alarm: Alarm, delayMs: Int = 300, scan: () -> Unit) = DebouncedDocumentScan(
    delayMs = delayMs,
    cancelPending = alarm::cancelAllRequests,
    scheduleScan = { delay, action -> alarm.addRequest({ action() }, delay) },
    scan = scan
)
