package `in`.kkkev.jjidea.jj

/**
 * A specific commit. Unlike [ChangeId] it never resolves to a different commit when the change is rewritten, so it is
 * what reads of historical content should use. [toString] is the short form: use [full] when building jj arguments.
 */
class CommitId(full: String, short: String? = null) :
    ShortenableImpl(full, short),
    Revision,
    ContentLocator,
    Shortenable {
    override val short get() = super<ShortenableImpl>.short
    override val title get() = short
}
