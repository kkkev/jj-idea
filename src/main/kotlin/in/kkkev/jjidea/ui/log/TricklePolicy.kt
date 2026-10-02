package `in`.kkkev.jjidea.ui.log

/**
 * Pure policy for the paged log's idle "trickle" (jj-idea-2570.5): how long to wait between
 * background page fetches, and when to stop.
 *
 * The trickle only bounds *unprompted* loading. Scrolling toward the bottom still loads
 * unthrottled and uncapped through the demand path ([UnifiedJujutsuLogDataLoader.loadMore]).
 */
internal object TricklePolicy {
    /**
     * Stop trickling once this many rows are loaded across all repos (the #69 reporter already runs
     * a 10,000-row log). Keeps a huge repo from being pulled into memory while nobody is looking.
     */
    const val ROW_CAP = 10_000

    /** Wait this many times the work just done: work / (work + wait) = 1/4, i.e. ~25% duty. */
    const val DUTY_FACTOR = 3L

    /** Floor, so a trivially fast page (and the unmeasured UI update after it) can't spin. */
    const val MIN_DELAY_MS = 200L

    /** Ceiling, so one slow fetch doesn't stall the trickle for minutes. */
    const val MAX_DELAY_MS = 5_000L

    fun delayAfter(workMillis: Long): Long = (workMillis * DUTY_FACTOR).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    fun wantsMore(loadedRows: Int, anyRepoPageable: Boolean, cap: Int = ROW_CAP): Boolean =
        anyRepoPageable && loadedRows < cap
}
