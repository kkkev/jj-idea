package `in`.kkkev.jjidea.jj

/**
 * Identifies one entry in `jj op log`. Never a [Revision] - it never goes through `-r`, only
 * through `jj op revert <id>` / `jj op log --at-operation`.
 */
@JvmInline
value class OperationId(private val id: String) {
    override fun toString() = id
}

/**
 * One parsed entry from `jj op log`. [time] and [user] are carried as raw jj-formatted strings
 * (not parsed into an [kotlinx.datetime.Instant]) - Stage 1 doesn't need them structured; they're
 * captured now so a future op-log browser (jj-idea-aii0) doesn't need a data-model migration.
 * [args] is the operation's raw argv (via the `tags` template keyword) - see
 * [in.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE] for why `tags` rather than `self.attributes()`.
 */
data class OperationEntry(
    val id: OperationId,
    val parents: List<OperationId>,
    val description: String,
    val time: String,
    val user: String,
    val isSnapshot: Boolean,
    val isRoot: Boolean,
    val args: String
)
