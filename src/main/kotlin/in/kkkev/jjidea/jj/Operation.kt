package `in`.kkkev.jjidea.jj

/**
 * Identifies one entry in `jj op log`. Never a [Revision] - it never goes through `-r`, only
 * through `jj op revert <id>` / `jj op log --at-operation`.
 */
@JvmInline
value class OperationId(private val id: String) {
    override fun toString() = id
}
