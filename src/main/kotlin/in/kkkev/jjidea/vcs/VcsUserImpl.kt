package `in`.kkkev.jjidea.vcs

import com.intellij.vcs.log.VcsUser

data class VcsUserImpl(private val name: String, private val email: String) : VcsUser {
    override fun getName() = name
    override fun getEmail() = email
}
