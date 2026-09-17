package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Memoises the one [DragContext] a gesture needs, built once from whichever surface's bean
 * provider first sees the payload - the log table's own row/chip drag
 * ([in.kkkev.jjidea.ui.log.JujutsuLogTableDnD]) or, since jj-idea-yvry/-b2oi, a changes tree's
 * file drag ([in.kkkev.jjidea.ui.common.installFilesDragSource]). Before this existed,
 * `JujutsuLogTableDnD` built its `DragContext` only inside its own bean provider - a drag that
 * started in a different component never populated it, so [DragContext.rejectionReason] (called
 * from the log table's target checker on every mouse-move, per design section 9) had nothing to
 * consult and every cross-surface drop silently resolved to nothing.
 *
 * [forPayload] is called once per bean-provider invocation (drag start) and then repeatedly from
 * the target checker's per-mouse-move resolution - identity-comparing against the last-seen
 * payload keeps [DragContext.forDrag]'s guard-state build to once per gesture, not once per pixel
 * of pointer movement, the same invariant [DragContextScaleTest] already asserts for `forDrag`
 * itself.
 */
class DragContextHolder {
    private var lastPayload: DragPayload? = null
    private var lastContext: DragContext? = null

    /**
     * The [DragContext] for [payload], reusing the memoised one if [payload] is reference-equal
     * to the last one seen (the log table's bean provider constructs the payload once per drag,
     * so identity is a valid cache key - no two gestures ever share a [DragPayload] instance).
     * [allEntries] is only invoked on a cache miss.
     */
    fun forPayload(payload: DragPayload, allEntries: () -> List<LogEntry>): DragContext {
        lastContext?.let { if (lastPayload === payload) return it }
        val context = DragContext.forDrag(allEntries(), payload)
        lastPayload = payload
        lastContext = context
        return context
    }

    /** Clear the memo - called when a gesture ends, so a stale context never leaks into the next one. */
    fun reset() {
        lastPayload = null
        lastContext = null
    }
}
