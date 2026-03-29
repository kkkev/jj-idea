package `in`.kkkev.jjidea.clone

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.Icon

class JujutsuCloneExtension : VcsCloneDialogExtension {
    override fun getName(): String = JujutsuBundle.message("clone.extension.name")

    override fun getIcon(): Icon = AllIcons.Vcs.Branch

    override fun getTooltip(): String = JujutsuBundle.message("clone.extension.tooltip")

    override fun createMainComponent(project: Project, modalityState: ModalityState): VcsCloneDialogExtensionComponent =
        JujutsuCloneComponent(project)
}
