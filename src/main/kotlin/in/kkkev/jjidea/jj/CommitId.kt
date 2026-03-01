package `in`.kkkev.jjidea.jj

class CommitId(full: String, short: String? = null) : Shortenable(full, short), Revision {
    override val short get() = super<Shortenable>.short
}
