package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId

/**
 * Template for `jj op log`, NUL-separated per contributing.md's log-parsing convention -
 * descriptions/argv can contain newlines and special characters, so `\0` (never present in text)
 * is the field separator, not `separate()`, which skips empty values and would misalign fields.
 *
 * `tags` is used for the operation's raw argv, not `self.attributes()`: `attributes()` doesn't
 * exist on jj 0.39 ([in.kkkev.jjidea.jj.JjVersion.MINIMUM] is 0.37), while `tags` works on
 * 0.37 through 0.44 - 0.44 prints a "deprecated" hint to *stderr* only, stdout parses clean. See
 * docs/design/undo-support-roadmap.md for the verification.
 */
internal const val OP_LOG_TEMPLATE = "id ++ \"\\0\" ++ " +
    "self.parents().map(|p| p.id()).join(\",\") ++ \"\\0\" ++ " +
    "description ++ \"\\0\" ++ " +
    "time.start() ++ \"\\0\" ++ " +
    "user ++ \"\\0\" ++ " +
    "if(snapshot, \"1\", \"0\") ++ \"\\0\" ++ " +
    "if(root, \"1\", \"0\") ++ \"\\0\" ++ " +
    "tags ++ \"\\0\""

private const val OP_LOG_FIELD_COUNT = 8

/**
 * The `--config` key used to tag an invocation for undo identification. jj accepts (and ignores)
 * unknown config keys - verified on 0.37 and 0.44 - so this is inert to the command itself and
 * exists purely to appear, verbatim, in that operation's recorded argv.
 */
internal const val UNDO_TOKEN_CONFIG_KEY = "jj-idea.undo-token"

/** The result of matching an undo token against a window of recent operations. */
internal sealed interface TaggedOperationMatch {
    /** Exactly one non-snapshot operation carried [id]'s token. */
    data class Found(val id: OperationId) : TaggedOperationMatch

    /** No operation in the window carried the token - it wrote nothing (a no-op command), or the
     *  window was too small. */
    data object NotFound : TaggedOperationMatch

    /** More than one non-snapshot operation carried the token. Should not happen with a
     *  freshly-generated UUID, but a corrupted/replayed op log is not impossible - withhold
     *  rather than guess. */
    data object Ambiguous : TaggedOperationMatch
}

/**
 * Finds the operation that carries [token] in its recorded argv, among [entries]. Snapshot
 * operations are always excluded: jj snapshots a dirty working copy as its *own* operation
 * before running the real command, and that paired snapshot carries the identical argv (same
 * `--config jj-idea.undo-token=...`, since it's the same invocation) - so without this exclusion,
 * a dirty working copy would make the match ambiguous every time, or worse, silently prefer
 * whichever entry `parseOpLog` happened to place first.
 */
internal fun findTaggedOperation(entries: List<OperationEntry>, token: String): TaggedOperationMatch {
    // Matches the full `jj-idea.undo-token=<token>` config assignment, not the bare token - a
    // plain substring check on the token alone would false-positive if one UUID happened to be a
    // prefix of another (e.g. token "abc" would match an op tagged "abc-def").
    val needle = "$UNDO_TOKEN_CONFIG_KEY=$token"
    val matches = entries.filter { !it.isSnapshot && needle in it.args }
    return when {
        matches.size == 1 -> TaggedOperationMatch.Found(matches.single().id)
        matches.isEmpty() -> TaggedOperationMatch.NotFound
        else -> TaggedOperationMatch.Ambiguous
    }
}

/**
 * Parses `jj op log -T [OP_LOG_TEMPLATE]` stdout into entries, in jj's own most-recent-first
 * order. Malformed/trailing NUL-split chunks (e.g. the empty tail after the final field's `\0`)
 * are dropped rather than throwing - an op log the caller can't fully make sense of should
 * degrade to "found nothing", never crash the command that asked for it.
 */
internal fun parseOpLog(stdout: String): List<OperationEntry> {
    val fields = stdout.split('\u0000')
    val entries = mutableListOf<OperationEntry>()
    var i = 0
    while (i + OP_LOG_FIELD_COUNT <= fields.size) {
        val id = fields[i]
        if (id.isEmpty()) break
        val parentsRaw = fields[i + 1]
        entries += OperationEntry(
            id = OperationId(id),
            parents = if (parentsRaw.isEmpty()) emptyList() else parentsRaw.split(",").map(::OperationId),
            description = fields[i + 2],
            time = fields[i + 3],
            user = fields[i + 4],
            isSnapshot = fields[i + 5] == "1",
            isRoot = fields[i + 6] == "1",
            args = fields[i + 7]
        )
        i += OP_LOG_FIELD_COUNT
    }
    return entries
}
