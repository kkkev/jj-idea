package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.actions.StandardVcsGroup
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * Action group for Jujutsu VCS in editor context menu
 */
class JujutsuEditorActionGroup : StandardVcsGroup() {
    override fun getVcs(project: Project?) = project?.possibleJujutsuVcs
    override fun getVcsName(project: Project?) = JujutsuVcs.VCS_NAME
}
