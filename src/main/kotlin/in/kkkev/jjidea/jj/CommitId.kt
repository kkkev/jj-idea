package `in`.kkkev.jjidea.jj

class CommitId(full: String, short: String? = null) : ShortenableImpl(full, short), Revision {
    override val short get() = super<ShortenableImpl>.short
}
