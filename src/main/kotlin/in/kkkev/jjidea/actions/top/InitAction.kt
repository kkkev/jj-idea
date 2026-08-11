package `in`.kkkev.jjidea.actions.top

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.*
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.jj.commandExecutorFactory
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker
import `in`.kkkev.jjidea.vcs.JujutsuVcsBase
import `in`.kkkev.jjidea.vcs.projectLevelVcsManager
import javax.swing.JComponent

class InitAction : DumbAwareAction(
    JujutsuBundle.message("action.init"),
    JujutsuBundle.message("action.init.description"),
    AllIcons.Actions.NewFolder
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Default to selected file's directory or project base
        val defaultDir = e.file?.let {
            if (it.isDirectory) it else it.parent
        } ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        val dialog = JujutsuInitDialog(project, defaultDir)
        if (!dialog.showAndGet()) return

        val newRoot = dialog.selectedDirectory ?: return
        val colocate = dialog.isColocate

        val commandExecutor = project.commandExecutorFactory.create(newRoot)
        commandExecutor.createCommand {
            val result = gitInit(colocate)

            VfsUtil.markDirtyAndRefresh(false, true, true, newRoot)

            val manager = project.projectLevelVcsManager
            val newMappings = VcsUtil.addMapping(manager.getDirectoryMappings(), newRoot.path, JujutsuVcsBase.VCS_NAME)
            manager.setDirectoryMappings(newMappings)

            // newRoot was very likely already mapped (as an uninitialized root) before this
            // action ran, so newMappings is often identical to the existing list and
            // setDirectoryMappings() is a no-op that won't re-validate the root now that .jj
            // exists. Force the platform to re-check root validity so it recognizes this
            // directory as an active VCS root (otherwise ChangeListManager/JujutsuChangeProvider
            // keep ignoring it and file statuses never update) (jj-idea-uw11).
            (manager as ProjectLevelVcsManagerEx).scheduleMappedRootsUpdate()

            result
        }.onSuccess {
            project.stateModel.initialisedRepositories.invalidate()
        }.onFailure { tellUser(project, "action.init.error") }
            .executeAsync()
    }
}

class JujutsuInitDialog(private val project: Project, defaultDirectory: VirtualFile?) : DialogWrapper(project) {
    private var directoryPath: String = defaultDirectory?.path ?: ""
    private var colocate: Boolean = false

    val selectedDirectory: VirtualFile?
        get() = LocalFileSystem.getInstance().findFileByPath(directoryPath)
    val isColocate: Boolean get() = colocate

    init {
        title = JujutsuBundle.message("action.init.title")
        setOKButtonText(JujutsuBundle.message("action.init.button"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(JujutsuBundle.message("action.init.directory.label")) {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(JujutsuBundle.message("action.init.directory.chooser.title")),
                project
            ).bindText(::directoryPath)
                .columns(COLUMNS_LARGE)
                .focused()
        }
        row {
            checkBox(JujutsuBundle.message("action.init.colocate"))
                .bindSelected(::colocate)
                .comment(JujutsuBundle.message("action.init.colocate.comment"))
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (directoryPath.isBlank()) {
            return ValidationInfo(JujutsuBundle.message("action.init.error.no.directory"))
        }
        val dir = LocalFileSystem.getInstance().findFileByPath(directoryPath)
        if (dir?.isDirectory != true) {
            return ValidationInfo(JujutsuBundle.message("action.init.error.invalid.directory"))
        }
        if (JujutsuRootChecker.isJujutsuRoot(dir)) {
            return ValidationInfo(JujutsuBundle.message("action.init.error.already.jj"))
        }
        return null
    }
}
