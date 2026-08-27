package `in`.kkkev.jjidea.jj

class CommitId(full: String, short: String? = null) : ShortenableImpl(full, short), Revision, Shortenable {
    override val short get() = super<ShortenableImpl>.short

    companion object {
        /**
         * A synthetic all-zero commit id for parent links that don't correspond to a real
         * commit - e.g. [in.kkkev.jjidea.ui.rebase.RebaseSimulator]'s simulated reparenting,
         * which only ever needs [ChangeId]-level identity and fabricates this for the
         * [LogEntry.Identifiers] it's required to produce.
         */
        val PLACEHOLDER = CommitId("0000000000000000000000000000000000000000")
    }
}
