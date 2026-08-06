package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.vcs.VcsUserImpl
import kotlinx.datetime.Instant

data class Signature(val name: String, val email: String, val timestamp: Instant) {
    val user get() = VcsUserImpl(name, email)
}
