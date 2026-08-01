package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsType
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider
import `in`.kkkev.jjidea.vcs.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.vcs.diff.JujutsuDiffProvider
import `in`.kkkev.jjidea.vcs.history.JujutsuHistoryProvider
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider

/**
 * Main VCS implementation for Jujutsu.
 *
 * `open` so the platform-version-gated `JujutsuVcs` subclass (`src/main/kotlin-commitModeOld`/
 * `kotlin-commitModeNew`, switched in build.gradle.kts) can add [AbstractVcs.getForcedCommitMode]
 * — its signature, and the shape of `com.intellij.vcs.commit.CommitMode` it returns, changed
 * incompatibly between platform build 253 (2025.3) and 261 (2026.1), with no compat shim on
 * either side, so it can't live in this version-agnostic base class (jj-idea-r5jf).
 */
open class JujutsuVcsBase(project: Project) : AbstractVcs(project, VCS_NAME) {
    private val lazyChangeProvider by lazy { JujutsuChangeProvider(this) }
    private val lazyDiffProvider by lazy { JujutsuDiffProvider(project) }
    private val lazyHistoryProvider by lazy { JujutsuHistoryProvider(project) }
    private val lazyAnnotationProvider by lazy { JujutsuAnnotationProvider(myProject, this) }
    private val lazyMergeProvider by lazy { JujutsuMergeProvider(myProject) }

    override fun getChangeProvider() = lazyChangeProvider

    override fun getDiffProvider() = lazyDiffProvider

    override fun getVcsHistoryProvider() = lazyHistoryProvider

    override fun getVcsBlockHistoryProvider() = lazyHistoryProvider

    override fun getAnnotationProvider() = lazyAnnotationProvider

    override fun getMergeProvider() = lazyMergeProvider

    override fun getDisplayName(): String = JujutsuBundle.message("vcs.displayname")

    override fun getType() = VcsType.distributed

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
    override fun createCheckinEnvironment() = JujutsuCheckinEnvironment()

    override fun allowsNestedRoots() = true

    /**
     * Disables the standard IDEA commit dialog (Ctrl+K) for Jujutsu projects.
     * Jujutsu has no "commit" concept — changes auto-snapshot to the working copy.
     * Returning true here prevents the commit dialog from opening in Jujutsu-only projects.
     * In mixed VCS projects (e.g. Jujutsu + Git), the platform skips this check when
     * multiple VCS roots are active, so Git roots remain unaffected.
     */
    override fun isCommitActionDisabled() = true

    /**
     * The roots for this VCS.
     * Note: This calls ProjectLevelVcsManager which may be slow - avoid calling on EDT.
     */
    val roots: List<VirtualFile>
        get() = myProject.projectLevelVcsManager.getRootsUnderVcs(this).toList()

    companion object {
        const val VCS_NAME = "Jujutsu"
        const val DOT_JJ = ".jj"

        private val log = Logger.getInstance(JujutsuVcsBase::class.java)

        private val KEY = createKey(VCS_NAME)

        fun getKey(): VcsKey = KEY
    }
}
