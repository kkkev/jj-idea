package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
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

    /**
     * Determines the repository that contains the specified file path.
     */
    fun jujutsuRepositoryFor(filePath: FilePath): JujutsuRepository? {
        val root = VcsUtil.getVcsRootFor(project, filePath)
        return root?.let { project.jujutsuRepositoryFor(it) }
    }

    companion object {
        const val VCS_NAME = "Jujutsu"

        private val log = Logger.getInstance(JujutsuVcs::class.java)

        private val KEY = createKey(VCS_NAME)

        fun getKey(): VcsKey = KEY

    }
}
