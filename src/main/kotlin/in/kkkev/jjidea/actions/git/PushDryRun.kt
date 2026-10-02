package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.diagnostic.Logger

private val log = Logger.getInstance("in.kkkev.jjidea.actions.git.PushDryRun")

/** What jj says a `git push` would do to one bookmark or tag on the remote. */
internal data class PushAction(val kind: Kind, val name: String, val isTag: Boolean = false) {
    enum class Kind { ADD, MOVE_FORWARD, MOVE_BACKWARD, MOVE_SIDEWAYS, DELETE }

    /** Non-fast-forward moves rewrite remote history and must be confirmed. */
    val needsForce get() = kind == Kind.MOVE_BACKWARD || kind == Kind.MOVE_SIDEWAYS
}

// jj <= ~0.39: "  Move sideways bookmark main from abc to def", "  Delete bookmark main from abc",
// "  Add bookmark main to def". Newer jj: "  bookmark: main [move sideways from abc to def]",
// "  bookmark: main [delete from abc]", "  tag: v1 [add to def]". Both are matched so the safety
// confirmations keep working across the supported jj range (jj-idea-spwt).
private val LEGACY_LINE = Regex("""^\s*(Add|Move forward|Move backward|Move sideways|Delete) bookmark (\S+)""")
private val BRACKETED_LINE =
    Regex(
        """^\s*(bookmark|tag): (\S+) \[(add to|move forward from|move backward from|move sideways from|delete from)\b"""
    )

private val CHANGES_HEADER = Regex("""Changes to push to \S+""")

private fun kindOf(word: String) = when (word.lowercase().removeSuffix(" from").removeSuffix(" to")) {
    "add" -> PushAction.Kind.ADD
    "move forward" -> PushAction.Kind.MOVE_FORWARD
    "move backward" -> PushAction.Kind.MOVE_BACKWARD
    "move sideways" -> PushAction.Kind.MOVE_SIDEWAYS
    "delete" -> PushAction.Kind.DELETE
    else -> null
}

private var warnedUnparsed = false

/**
 * Parses the "Changes to push to <remote>:" section of `jj git push --dry-run` output (stdout and
 * stderr together - jj writes it to stderr, but nothing depends on that). O(output lines).
 *
 * If a changes section is present but no action line is recognised, jj's wording has probably
 * drifted again: log it once per session with the raw text, so it is noticed rather than
 * silently skipping the force-push/delete confirmations (jj-idea-spwt).
 */
internal fun parsePushPlan(text: String): List<PushAction> {
    val actions = text.lines().mapNotNull { line ->
        LEGACY_LINE.find(line)?.let { m ->
            return@mapNotNull kindOf(m.groupValues[1])?.let { PushAction(it, m.groupValues[2]) }
        }
        BRACKETED_LINE.find(line)?.let { m ->
            val isTag = m.groupValues[1] == "tag"
            return@mapNotNull kindOf(m.groupValues[3])?.let { PushAction(it, m.groupValues[2], isTag) }
        }
        null
    }
    if (actions.isEmpty() && CHANGES_HEADER.containsMatchIn(text) && !warnedUnparsed) {
        warnedUnparsed = true
        log.warn("jj push dry-run listed changes but none were recognised; output format may have changed:\n$text")
    }
    return actions
}
