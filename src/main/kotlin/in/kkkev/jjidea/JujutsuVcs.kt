package `in`.kkkev.jjidea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import `in`.kkkev.jjidea.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.checkin.JujutsuCheckinEnvironment
import `in`.kkkev.jjidea.commands.JujutsuCliExecutor
import `in`.kkkev.jjidea.commands.JujutsuCommandExecutor
import `in`.kkkev.jjidea.diff.JujutsuDiffProvider

/**
 * Main VCS implementation for Jujutsu
 */
class JujutsuVcs(project: Project) : AbstractVcs(project, VCS_NAME) {

    private val log = Logger.getInstance(JujutsuVcs::class.java)

    val commandExecutor: JujutsuCommandExecutor = JujutsuCliExecutor()
    private val _changeProvider by lazy { JujutsuChangeProvider(myProject, this) }
    private val _diffProvider by lazy { JujutsuDiffProvider(myProject, this) }
    private val _checkinEnvironment by lazy { JujutsuCheckinEnvironment(this) }

    override fun getChangeProvider() = _changeProvider

    override fun getDiffProvider() = _diffProvider

    override fun getCheckinEnvironment() = _checkinEnvironment

    override fun getConfigurable(): Configurable? {
        // TODO: Add configuration UI if needed
        return null
    }

    override fun getDisplayName(): String = VCS_DISPLAY_NAME

    override fun activate() {
        log.info("Jujutsu VCS activated for project: ${myProject.name}")
        super.activate()
    }

    override fun deactivate() {
        log.info("Jujutsu VCS deactivated for project: ${myProject.name}")
        super.deactivate()
    }

    companion object {
        const val VCS_NAME = "Jujutsu"
        const val VCS_DISPLAY_NAME = "Jujutsu"
    }
}
