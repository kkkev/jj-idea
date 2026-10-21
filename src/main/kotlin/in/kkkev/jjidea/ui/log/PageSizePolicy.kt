package `in`.kkkev.jjidea.ui.log

/**
 * Pure policy for how many rows the next paged-log fetch asks for (jj-idea-2570.9).
 *
 * Every page lands as one table apply that costs O(loaded rows) however small the page is, so deep
 * scrolling wants big pages. But a page's fetch holds the repo lock (a write's `refresh()` waits behind
 * it) and is what the user waits for, so the real limit is a *time*, not a row count - and that varies
 * with hardware and graph width (0.3 s per 500 rows on git/git here, several times that on a slow disk).
 * So the loader times each fetch and this sizes the next one to take about [TARGET_MS].
 *
 * Page 1 is always the fixed first-page size, so [UnifiedJujutsuLogDataLoader.refresh]'s page-1 splice
 * stays sound. Same shape as [TricklePolicy]: the caller measures, this only decides.
 */
internal object PageSizePolicy {
    /**
     * How long one page fetch should take. A starting point to be tuned against the scale fixtures; override
     * with `-Djj.log.pageTargetMs=<ms>` to sweep it without rebuilding.
     */
    val TARGET_MS: Long = System.getProperty("jj.log.pageTargetMs")?.toLongOrNull()?.coerceAtLeast(1) ?: 700L

    /**
     * Safety valve whatever the timings say: bounds memory per fetch and the damage of a bad measurement.
     * Override with `-Djj.log.pageMaxRows=<rows>`. Swept on r80k-git (2000/4000/8000): total apply 2.0/1.1/0.7 s and
     * layout 3.5/2.1/1.6 s over a full load, worst fetch 505/560/465 ms - per-call cost dominates, so bigger pages
     * are cheaper per row; slow hardware is protected by [TARGET_MS], not by this ceiling.
     */
    val MAX_PAGE_ROWS: Int = System.getProperty("jj.log.pageMaxRows")?.toIntOrNull()?.coerceAtLeast(1) ?: 8000

    /**
     * Rows for the page after one that asked for [lastRows] and took [lastMillis]: scaled to hit [TARGET_MS],
     * at most double (a noisy fast fetch can't jump straight to the ceiling), never below [floor] (the
     * first-page size) or above [ceiling].
     */
    fun next(lastRows: Int, lastMillis: Long, floor: Int, ceiling: Int = MAX_PAGE_ROWS): Int {
        val scaled = lastRows * TARGET_MS / lastMillis.coerceAtLeast(1)
        return scaled.coerceAtMost(lastRows * 2L).coerceIn(floor.toLong(), maxOf(floor, ceiling).toLong()).toInt()
    }
}
