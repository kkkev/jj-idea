package `in`.kkkev.jjidea.jj

class CommitId(full: String, short: String? = null) : ShortenableImpl(full, short), Revision, Shortenable {
    override val short get() = super<ShortenableImpl>.short
}
