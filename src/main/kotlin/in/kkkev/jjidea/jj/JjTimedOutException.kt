package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.VcsException
import java.util.concurrent.TimeUnit

/**
 * A jj command was killed at its timeout (jj-idea-1bio, GitHub #123) - as opposed to jj running and
 * reporting an error. Carries the timeout so callers can tell "jj was slow" (system under load)
 * from "the repository is broken" without matching on message text. Still a [VcsException], so
 * every existing `catch (e: VcsException)` behaves exactly as before.
 */
class JjTimedOutException(message: String, val timeoutMillis: Long) : VcsException(message) {
    val timeoutSeconds: Long get() = TimeUnit.MILLISECONDS.toSeconds(timeoutMillis)
}
