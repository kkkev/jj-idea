package `in`.kkkev.jjidea.ui.newchange

import `in`.kkkev.jjidea.jj.LogEntry

/** jj-idea-b0p2: matches git's `fmt-merge-msg` (only the source is quoted). */
const val DEFAULT_MERGE_DESCRIPTION_TEMPLATE = "Merge branch '{source}' into {destination}"

private const val SOURCE_PLACEHOLDER = "{source}"
private const val DESTINATION_PLACEHOLDER = "{destination}"

/** [primary] is prefilled; [alternatives] are the flipped direction, then the directionless form. */
data class MergeDescriptionSuggestion(val primary: String, val alternatives: List<String>) {
    val all get() = listOf(primary) + alternatives
}

fun isValidMergeTemplate(template: String) =
    SOURCE_PLACEHOLDER in template && DESTINATION_PLACEHOLDER in template

/**
 * Suggests a description for `jj new A B` when [targets] are exactly two changes that each carry
 * one unambiguous bookmark name; null (stay silent) otherwise. Direction cannot be inferred in jj,
 * so the older tip is pre-selected as the destination (newer work merged into the established
 * line) purely as a starting point - the flipped and directionless phrasings are alternatives.
 * An invalid [template] falls back to [DEFAULT_MERGE_DESCRIPTION_TEMPLATE].
 */
fun mergeDescriptionSuggestion(targets: List<LogEntry>, template: String): MergeDescriptionSuggestion? {
    if (targets.size != 2) return null
    val first = mergeBookmarkName(targets[0]) ?: return null
    val second = mergeBookmarkName(targets[1]) ?: return null
    if (first == second) return null

    val firstTime = targets[0].committerTimestamp ?: targets[0].authorTimestamp
    val secondTime = targets[1].committerTimestamp ?: targets[1].authorTimestamp
    // Older tip is the destination; ties and missing timestamps keep selection order.
    val secondIsOlder = firstTime != null && secondTime != null && secondTime < firstTime
    val (destination, source) = if (secondIsOlder) second to first else first to second

    val effective = template.takeIf(::isValidMergeTemplate) ?: DEFAULT_MERGE_DESCRIPTION_TEMPLATE
    fun render(src: String, dst: String) =
        effective.replace(SOURCE_PLACEHOLDER, src).replace(DESTINATION_PLACEHOLDER, dst)

    return MergeDescriptionSuggestion(
        primary = render(source, destination),
        alternatives = listOf(
            render(destination, source),
            "Merge branches '$first' and '$second'"
        )
    )
}

/**
 * The single bookmark name identifying [entry], or null if it has none or several. Local bookmarks
 * win; otherwise remote-only bookmarks count by their local name (`main@origin` -> `main`).
 */
private fun mergeBookmarkName(entry: LogEntry): String? {
    val (remote, local) = entry.bookmarks.partition { it.isRemote }
    val names = (local.ifEmpty { remote }).map { it.localName }.distinct()
    return names.singleOrNull()
}
