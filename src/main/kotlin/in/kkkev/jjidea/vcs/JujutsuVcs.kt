package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages.showErrorDialog
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider
import `in`.kkkev.jjidea.vcs.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.vcs.checkin.JujutsuCheckinEnvironment
import `in`.kkkev.jjidea.vcs.diff.JujutsuDiffProvider
import `in`.kkkev.jjidea.vcs.history.JujutsuHistoryProvider

/**
 * Main VCS implementation for Jujutsu
 */
class JujutsuVcs(project: Project) : AbstractVcs(project, VCS_NAME) {
    private val lazyChangeProvider by lazy { JujutsuChangeProvider(this) }
    private val lazyDiffProvider by lazy { JujutsuDiffProvider() }
    private val lazyCheckinEnvironment by lazy { JujutsuCheckinEnvironment(this) }
    private val lazyHistoryProvider by lazy { JujutsuHistoryProvider() }
    private val lazyAnnotationProvider by lazy { JujutsuAnnotationProvider(myProject, this) }

    override fun getChangeProvider() = lazyChangeProvider

    override fun getDiffProvider() = lazyDiffProvider

    override fun getCheckinEnvironment() = lazyCheckinEnvironment

    override fun getVcsHistoryProvider() = lazyHistoryProvider

    override fun getAnnotationProvider() = lazyAnnotationProvider

    override fun getDisplayName(): String = JujutsuBundle.message("vcs.displayname")

    override fun activate() {
        log.info("Jujutsu VCS activated for project: ${myProject.name}")
        super.activate()
    }

    override fun deactivate() {
        log.info("Jujutsu VCS deactivated for project: ${myProject.name}")
        super.deactivate()
    }

    /**
     * Returns true to indicate Jujutsu supports nested repositories.
     * This also bypasses a slow FileIndexFacade.isValidAncestor() check in MappingsToRoots
     * that would otherwise cause EDT slow operation warnings.
     */
    override fun allowsNestedRoots() = true

    /**
     * The roots for this VCS.
     * Note: This calls ProjectLevelVcsManager which may be slow - avoid calling on EDT.
     */
    val roots: List<VirtualFile>
        get() = ProjectLevelVcsManager.getInstance(myProject).getRootsUnderVcs(this).toList()

    /**
     * Determines the repository that contains the specified file.
     */
    fun jujutsuRepositoryFor(file: VirtualFile): JujutsuRepository? {
        val root = VcsUtil.getVcsRootFor(project, file)
        return root?.let { project.jujutsuRepositoryFor(it) }
    }

    companion object {
        const val VCS_NAME = "Jujutsu"

        private val log = Logger.getInstance(JujutsuVcs::class.java)

        private val KEY = createKey(VCS_NAME)

        fun getKey(): VcsKey = KEY

        /**
         * Get VCS with user-friendly error handling.
         * Use in user-facing actions where VCS might not be configured (e.g., context menus, toolbar actions).
         *
         * If VCS is not found:
         * - Logs at INFO level (user error, not plugin error)
         * - Shows user-friendly error dialog
         * - Returns null
         *
         * Call from background thread to avoid EDT slow operations.
         *
         * @param project The project to find VCS for
         * @param actionName Name of the action for logging (e.g., "New Change", "Compare with Branch")
         * @return JujutsuVcs instance or null if not found
         */
        fun getVcsWithUserErrorHandling(project: Project, actionName: String) = project.possibleJujutsuVcs ?: run {
            log.info("User attempted '$actionName' in non-Jujutsu project: ${project.name}")
            ApplicationManager.getApplication().invokeLater {
                showErrorDialog(
                    project,
                    "This project is not configured for Jujutsu version control.\n\nTo use Jujutsu, ensure the project has a .jj directory.",
                    "Jujutsu Not Available"
                )
            }
            null
        }
    }
}
