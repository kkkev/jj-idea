package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.impl.HashImpl

class CommitId(full: String, shortLength: Int? = null) : ShortenableId(full, shortLength) {
    constructor(full: String, short: String) : this(full, calculateShortLength(full, short))

    /**
     * Lazy initialization of Hash to allow tests without IntelliJ Platform.
     * Only accessed when integrating with IntelliJ VCS framework.
     */
    val hash: Hash by lazy {
        HashImpl.build(full)
    }
}
