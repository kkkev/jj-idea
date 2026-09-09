package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.VcsException

/**
 * [repo]'s working copy could not be resolved because [health] describes why (stale workspace,
 * unreadable store, ...). Thrown by [JujutsuRepositoryImpl.workingCopy] instead of a bare
 * [VcsException] so callers that care can catch specifically and offer [health]'s remedy
 * ([in.kkkev.jjidea.jj.runRecoverable], [in.kkkev.jjidea.jj.whenWorkingCopyAvailable]) rather than
 * treating it as an arbitrary VCS failure. Still a [VcsException], so every existing
 * `catch (e: VcsException)` at the older call sites (e.g. [in.kkkev.jjidea.vcs.changes.JujutsuChangeProvider],
 * [in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider]) behaves exactly as before.
 */
class WorkingCopyUnavailableException(val repo: JujutsuRepository, val health: RepositoryHealth) :
    // repo.directory.path, not "$repo"/repo.toString() - JujutsuRepositoryImpl.toString() calls
    // guessProjectDir(), which needs a real Project (crashes a mocked one in unit tests, and is
    // needless work here anyway).
    VcsException("Working copy not found for ${repo.directory.path}: ${health.detail}")
