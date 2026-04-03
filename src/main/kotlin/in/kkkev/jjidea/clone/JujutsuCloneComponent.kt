package `in`.kkkev.jjidea.clone

import com.intellij.dvcs.ui.CloneDvcsValidationUtils
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithHistory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.PathUtil
import com.intellij.util.io.URLUtil
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class JujutsuCloneComponent(private val project: Project) : VcsCloneDialogExtensionComponent() {
    private val urlField = TextFieldWithHistory()
    private val directoryField = TextFieldWithBrowseButton()
    private val colocateCheckbox = JCheckBox(JujutsuBundle.message("clone.colocate"), true)

    private val mainPanel = panel {
        row(JujutsuBundle.message("clone.url.label")) {
            cell(urlField).align(AlignX.FILL)
        }
        row(JujutsuBundle.message("clone.directory.label")) {
            cell(directoryField).align(AlignX.FILL)
        }.bottomGap(BottomGap.SMALL)
        row {
            cell(colocateCheckbox)
                .comment(JujutsuBundle.message("clone.colocate.tooltip"))
        }
    }

    init {
        directoryField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(JujutsuBundle.message("clone.directory.chooser.title"))
                .withShowFileSystemRoots(true)
                .withHideIgnored(false)
        )

        // Set default parent directory
        directoryField.text = ProjectUtil.getBaseDir()

        // Auto-populate directory name from URL
        urlField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val url = urlField.text.trim()
                if (url.isNotEmpty()) {
                    val dirName = extractDirectoryNameFromUrl(url)
                    val parentDir = File(directoryField.text).let {
                        if (it.isDirectory) it.path else it.parent ?: ProjectUtil.getBaseDir()
                    }
                    directoryField.text = "$parentDir/$dirName"
                }
                updateOkActionState()
            }
        })

        val insets = UIUtil.PANEL_REGULAR_INSETS
        mainPanel.border = JBEmptyBorder(insets.top / 2, insets.left, insets.bottom, insets.right)
    }

    private fun extractDirectoryNameFromUrl(url: String): String {
        val sanitizedUrl = sanitizeCloneUrl(url)
        val encoded = PathUtil.getFileName(sanitizedUrl)
            .removeSuffix(".git")
            .removeSuffix(".jj")
        return try {
            URLUtil.decode(encoded)
        } catch (_: Exception) {
            encoded
        }
    }

    private fun updateOkActionState() {
        dialogStateListener.onOkActionEnabled(urlField.text.isNotBlank())
    }

    override fun getView(): JComponent = mainPanel

    override fun getPreferredFocusedComponent(): JComponent = urlField

    override fun doValidateAll(): List<ValidationInfo> = buildList {
        val url = urlField.text.trim()
        val directory = directoryField.text.trim()

        CloneDvcsValidationUtils.checkRepositoryURL(urlField, url)?.let { add(it) }
        CloneDvcsValidationUtils.checkDirectory(directory, directoryField.textField as JComponent)?.let { add(it) }
    }

    override fun onComponentSelected() {
        dialogStateListener.onOkActionNameChanged(JujutsuBundle.message("clone.button"))
        dialogStateListener.onOkActionEnabled(urlField.text.isNotBlank())
    }

    override fun doClone(checkoutListener: CheckoutProvider.Listener) {
        val url = sanitizeCloneUrl(urlField.text.trim())
        val directory = directoryField.text.trim()
        val colocate = colocateCheckbox.isSelected

        // Create the parent directory if needed
        val destDir = File(directory)
        val parentDir = destDir.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        object : Task.Backgroundable(project, JujutsuBundle.message("clone.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = JujutsuBundle.message("clone.progress.starting")

                val appSettings = JujutsuApplicationSettings.getInstance()
                val executor = CliExecutor.forRootlessOperations { appSettings.state.jjExecutablePath }
                val result = executor.gitCloneWithProgress(url, directory, colocate, indicator)

                if (result.isSuccess) {
                    // Refresh VFS so IntelliJ sees the new directory
                    LocalFileSystem.getInstance().findFileByIoFile(parentDir)?.refresh(true, true)

                    // Call listener on background thread (required by CheckoutListener contract)
                    checkoutListener.directoryCheckedOut(destDir, JujutsuVcs.getKey())
                    checkoutListener.checkoutCompleted()
                } else {
                    invokeLater(ModalityState.defaultModalityState()) {
                        JujutsuNotifications.notify(
                            project,
                            JujutsuBundle.message("clone.error.title"),
                            JujutsuBundle.message("clone.error.message", result.stderr),
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }.queue()
    }

    companion object {
        /**
         * Sanitize clone URL by removing common prefixes like "git clone" or "jj git clone".
         */
        private fun sanitizeCloneUrl(urlText: String) = urlText.trim()
            .removePrefix("jj git clone")
            .removePrefix("git clone")
            .removePrefix("hg clone")
            .trim()
    }
}
