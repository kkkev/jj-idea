package `in`.kkkev.jjidea.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.jj.cli.Config
import `in`.kkkev.jjidea.jj.cli.config
import `in`.kkkev.jjidea.jj.cli.rootlessConfig
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.util.runLaterInModal
import java.awt.Font
import java.awt.datatransfer.StringSelection

/**
 * Settings panel for Jujutsu plugin configuration.
 *
 * Appears under Settings → Version Control → Jujutsu
 */
class JujutsuConfigurable(private val project: Project) : BoundConfigurable(JujutsuBundle.message("settings.title")) {
    private val log = Logger.getInstance(javaClass)
    private val settings = JujutsuSettings.getInstance(project)
    private val appSettings = JujutsuApplicationSettings.getInstance()
    private var previousPath = appSettings.state.jjExecutablePath
    private var previousLogLimit = settings.state.logChangeLimit
    private val finder = JjExecutableFinder()

    // UI components for validation feedback
    private lateinit var pathField: Cell<TextFieldWithBrowseButton>
    private val validationLabel = JBLabel()

    // Global identity — backing properties for bindText(); async-loaded from jj config
    private var globalNameBinding = ""
    private var globalEmailBinding = ""
    private var globalNameField: JBTextField? = null
    private var globalEmailField: JBTextField? = null

    // Per-repo settings
    private val repos: List<JujutsuRepository> = project.stateModel.initializedRoots.value.toList()
    private var repoSettingsDirty = false

    private data class RepoSettingsPanel(
        val repo: JujutsuRepository,
        val identityCb: JBCheckBox,
        val nameField: JBTextField,
        val emailField: JBTextField,
        val limitCb: JBCheckBox,
        val limitField: JBTextField
    )

    private val repoSettingsPanels = mutableListOf<RepoSettingsPanel>()

    override fun createPanel(): DialogPanel = panel {
        group(JujutsuBundle.message("settings.group.executable")) {
            row(JujutsuBundle.message("settings.jj.path.label")) {
                pathField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory
                        .createSingleFileOrExecutableAppDescriptor()
                        .withTitle(JujutsuBundle.message("settings.jj.path.chooser.title")),
                    project
                ).bindText(appSettings.state::jjExecutablePath)
                    .columns(COLUMNS_LARGE)
                    .comment(JujutsuBundle.message("settings.jj.path.comment"))

                button(JujutsuBundle.message("settings.jj.path.test")) {
                    testExecutable()
                }
            }
            row("") {
                cell(validationLabel)
            }
        }

        collapsibleGroup(JujutsuBundle.message("settings.group.install")) {
            // Check current status to decide install vs upgrade
            val status = JjAvailabilityChecker.getInstance(project).status.value
            val isUpgrade = status is JjAvailabilityStatus.VersionTooOld
            val detectedMethod = (status as? JjAvailabilityStatus.VersionTooOld)?.installMethod

            row {
                val descriptionKey = if (isUpgrade) {
                    "settings.upgrade.description"
                } else {
                    "settings.install.description"
                }
                label(JujutsuBundle.message(descriptionKey))
            }

            // Show upgrade for detected method first if applicable
            if (isUpgrade &&
                detectedMethod != null &&
                detectedMethod !is InstallMethod.Manual &&
                detectedMethod !is InstallMethod.Unknown
            ) {
                commandRow(detectedMethod.name, detectedMethod.upgradeCommand)
            }

            // Show all available methods (excluding Manual and the already-shown detected method for upgrades)
            val methods = InstallMethod.allAvailable.filter {
                it !is InstallMethod.Manual && !(isUpgrade && it == detectedMethod)
            }
            methods.forEach { method ->
                val command = if (isUpgrade) method.upgradeCommand else method.installCommand
                commandRow(method.name, command)
            }

            // Manual method just shows a message
            if (InstallMethod.Manual in InstallMethod.allAvailable) {
                row {
                    comment(JujutsuBundle.message("settings.install.method.manual"))
                }
            }

            row {
                link(JujutsuBundle.message("settings.install.documentation")) {
                    BrowserUtil.browse(InstallMethod.INSTALL_DOCS)
                }
            }
        }.apply { expanded = false }

        group(JujutsuBundle.message("settings.group.identity")) {
            row(JujutsuBundle.message("settings.identity.name.label")) {
                globalNameField = textField()
                    .bindText(::globalNameBinding)
                    .focused()
                    .columns(COLUMNS_MEDIUM)
                    .component
            }
            row(JujutsuBundle.message("settings.identity.email.label")) {
                globalEmailField = textField()
                    .bindText(::globalEmailBinding)
                    .columns(COLUMNS_MEDIUM)
                    .component
            }
            row {
                comment(JujutsuBundle.message("settings.identity.comment"))
            }
        }

        group(JujutsuBundle.message("settings.group.ui")) {
            row {
                checkBox(JujutsuBundle.message("settings.autorefresh.label"))
                    .bindSelected(settings.state::autoRefreshEnabled)
                    .comment(JujutsuBundle.message("settings.autorefresh.comment"))
            }
            row {
                checkBox(JujutsuBundle.message("settings.shortformat.label"))
                    .bindSelected(settings.state::showChangeIdsInShortFormat)
                    .comment(JujutsuBundle.message("settings.shortformat.comment"))
            }
            row {
                checkBox(JujutsuBundle.message("settings.autoopenlog.label"))
                    .bindSelected(settings.state::autoOpenCustomLogTab)
                    .comment(JujutsuBundle.message("settings.autoopenlog.comment"))
            }
        }

        group(JujutsuBundle.message("settings.group.log")) {
            row(JujutsuBundle.message("settings.log.limit.label")) {
                intTextField(range = 1..10000)
                    .bindIntText(settings.state::logChangeLimit)
                    .columns(COLUMNS_TINY)
                    .comment(JujutsuBundle.message("settings.log.limit.comment"))
            }
        }

        if (repos.isNotEmpty()) {
            group(JujutsuBundle.message("settings.group.repo")) {
                val dirtyListener = object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                        repoSettingsDirty = true
                    }

                    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                        repoSettingsDirty = true
                    }

                    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                        repoSettingsDirty = true
                    }
                }

                repos.forEach { repo ->
                    val identityCb = JBCheckBox(JujutsuBundle.message("settings.repo.identity.override"))
                    val nameField = JBTextField()
                    val emailField = JBTextField()
                    val limitCb = JBCheckBox(JujutsuBundle.message("settings.repo.loglimit.override"))
                    val limitField = JBTextField()

                    fun updateIdentityEnabled() {
                        nameField.isEnabled = identityCb.isSelected
                        emailField.isEnabled = identityCb.isSelected
                    }

                    fun updateLimitEnabled() {
                        limitField.isEnabled = limitCb.isSelected
                    }

                    identityCb.addActionListener {
                        updateIdentityEnabled()
                        repoSettingsDirty = true
                    }
                    limitCb.addActionListener {
                        updateLimitEnabled()
                        repoSettingsDirty = true
                    }
                    nameField.document.addDocumentListener(dirtyListener)
                    emailField.document.addDocumentListener(dirtyListener)
                    limitField.document.addDocumentListener(dirtyListener)

                    // Load limit override from plugin settings (synchronous)
                    val repoPath = repo.directory.path
                    val repoConfig = settings.state.repositoryOverrides[repoPath]
                    limitCb.isSelected = repoConfig?.logChangeLimit != null
                    limitField.text = repoConfig?.logChangeLimit?.toString() ?: ""

                    // Load identity from jj config (background): prefer repo-scoped, fall back to effective
                    runInBackground {
                        val config = repo.config
                        val name = config.repo[Config.Key.USER_NAME]
                        val email = config.repo[Config.Key.USER_EMAIL]
                        runLater {
                            identityCb.isSelected = (name != null) || (email != null)
                            nameField.text = name
                            emailField.text = email
                            updateIdentityEnabled()
                        }
                    }

                    updateIdentityEnabled()
                    updateLimitEnabled()

                    repoSettingsPanels.add(
                        RepoSettingsPanel(repo, identityCb, nameField, emailField, limitCb, limitField)
                    )

                    collapsibleGroup(repo.displayName) {
                        row { cell(identityCb) }
                        indent {
                            row(JujutsuBundle.message("settings.repo.identity.name.label")) {
                                cell(nameField)
                                    .columns(COLUMNS_MEDIUM)
                                    .validationOnApply {
                                        if (identityCb.isSelected && it.text.isBlank()) {
                                            error(JujutsuBundle.message("settings.repo.identity.error.name"))
                                        } else {
                                            null
                                        }
                                    }
                            }
                            row(JujutsuBundle.message("settings.repo.identity.email.label")) {
                                cell(emailField)
                                    .columns(COLUMNS_MEDIUM)
                                    .validationOnApply {
                                        if (identityCb.isSelected && it.text.isBlank()) {
                                            error(JujutsuBundle.message("settings.repo.identity.error.email"))
                                        } else {
                                            null
                                        }
                                    }
                            }
                        }
                        row {
                            cell(limitCb)
                            cell(limitField).columns(COLUMNS_TINY)
                        }
                    }.apply { expanded = repos.size == 1 }
                }
            }
        }

        // Load global identity values asynchronously
        loadGlobalIdentity()
    }

    private fun loadGlobalIdentity() {
        val config = rootlessConfig.effective
        runInBackground {
            val name = config[Config.Key.USER_NAME]
            val email = config[Config.Key.USER_EMAIL]
            runLater {
                // Update both the backing property (isModified baseline) and the visible field text.
                // Both must change together so the panel doesn't consider the load itself a modification.
                globalNameBinding = name ?: ""
                globalEmailBinding = email ?: ""
                globalNameField?.text = name
                globalEmailField?.text = email
            }
        }
    }

    override fun isModified() = super.isModified() || repoSettingsDirty

    override fun apply() {
        super.apply()

        // Save global identity — bindings were updated from the fields by super.apply()
        val config = rootlessConfig.user

        if (globalNameBinding.isNotBlank()) {
            runInBackground {
                config[Config.Key.USER_NAME] = globalNameBinding
            }
        }
        if (globalEmailBinding.isNotBlank()) {
            runInBackground {
                config[Config.Key.USER_EMAIL] = globalEmailBinding
            }
        }

        // Save per-repo settings
        repoSettingsPanels.forEach { panel ->
            val repoPath = panel.repo.directory.path

            // Save log limit override to plugin settings
            val currentOverride = settings.state.repositoryOverrides[repoPath]
            val newLimit = if (panel.limitCb.isSelected) panel.limitField.text.trim().toIntOrNull() else null
            if (newLimit != currentOverride?.logChangeLimit) {
                if (newLimit != null) {
                    settings.state.repositoryOverrides[repoPath] =
                        (currentOverride ?: RepositoryConfig()).copy(logChangeLimit = newLimit)
                } else {
                    val updated = currentOverride?.copy(logChangeLimit = null)
                    if (updated != null) {
                        settings.state.repositoryOverrides[repoPath] = updated
                    } else {
                        settings.state.repositoryOverrides.remove(repoPath)
                    }
                }
            }

            // Save identity override to jj repo config if checkbox is checked
            fun JBTextField.getValidText() = text.trim().takeIf { it.isNotBlank() && panel.identityCb.isSelected }
            runInBackground {
                val repoConfig = panel.repo.config.repo
                repoConfig[Config.Key.USER_NAME] = panel.nameField.getValidText()
                repoConfig[Config.Key.USER_EMAIL] = panel.emailField.getValidText()
            }
        }

        repoSettingsDirty = false

        // If executable path changed, recheck availability and refresh if now available
        val newPath = appSettings.state.jjExecutablePath
        if (newPath != previousPath) {
            previousPath = newPath
            val checker = JjAvailabilityChecker.getInstance(project)

            // One-shot subscription: dispose after callback fires
            val disposable = Disposer.newDisposable("JujutsuConfigurable.apply")
            checker.status.connect(disposable) { status ->
                if (status is JjAvailabilityStatus.Available) {
                    // Trigger full refresh of state model
                    project.stateModel.initializedRoots.invalidate()
                }
                Disposer.dispose(disposable)
            }

            // Trigger the recheck after subscribing
            checker.recheck()
        }

        // If log limit changed, reload the log
        val newLogLimit = settings.state.logChangeLimit
        if (newLogLimit != previousLogLimit) {
            previousLogLimit = newLogLimit
            project.stateModel.logRefresh.notify(Unit)
        }
    }

    private fun testExecutable() {
        val path = pathField.component.text.trim()
        log.info("Testing executable: '$path'")

        if (path.isEmpty()) {
            showValidationResult(false, JujutsuBundle.message("settings.jj.path.test.empty"))
            return
        }

        // Show testing message immediately
        showValidationResult(null, JujutsuBundle.message("settings.jj.path.test.testing"))

        // Run validation on background thread
        runInBackground {
            log.info("Running validation for: '$path'")
            val result = finder.validatePath(path)
            log.info("Validation result: $result")

            runLaterInModal(pathField.component) {
                when (result) {
                    is JjExecutableFinder.ValidationResult.Valid -> {
                        val exe = result.executable
                        if (exe.version.meetsMinimum()) {
                            showValidationResult(
                                true,
                                JujutsuBundle.message(
                                    "settings.jj.path.test.success",
                                    exe.version.toString(),
                                    exe.path.toString()
                                )
                            )
                        } else {
                            showValidationResult(
                                false,
                                JujutsuBundle.message(
                                    "settings.jj.path.test.version",
                                    exe.version.toString(),
                                    JjVersion.MINIMUM.toString()
                                )
                            )
                        }
                    }

                    is JjExecutableFinder.ValidationResult.Invalid -> {
                        // Use details if available, otherwise fall back to generic message
                        val message = result.details ?: when (result.reason) {
                            JjExecutableFinder.InvalidReason.NOT_FOUND ->
                                JujutsuBundle.message("settings.jj.path.test.notfound")

                            JjExecutableFinder.InvalidReason.IS_DIRECTORY ->
                                JujutsuBundle.message("settings.jj.path.test.isdirectory")

                            JjExecutableFinder.InvalidReason.NOT_EXECUTABLE ->
                                JujutsuBundle.message("settings.jj.path.test.notexecutable")

                            JjExecutableFinder.InvalidReason.NOT_JJ ->
                                JujutsuBundle.message("settings.jj.path.test.notjj")

                            JjExecutableFinder.InvalidReason.EXECUTION_FAILED ->
                                JujutsuBundle.message("settings.jj.path.test.failed")
                        }
                        showValidationResult(false, message)
                    }
                }
            }
        }
    }

    private fun showValidationResult(success: Boolean?, message: String) {
        validationLabel.text = message
        validationLabel.icon = when (success) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> AllIcons.Process.Step_1 // Loading indicator
        }
    }

    /** Creates a row with method name, monospace command in a box, and copy button. */
    private fun Panel.commandRow(methodName: String, command: String) {
        row(methodName + ":") {
            cell(createCommandField(command))
                .align(AlignX.FILL)
                .resizableColumn()
            button(JujutsuBundle.message("settings.install.copy")) {
                CopyPasteManager.getInstance().setContents(StringSelection(command))
            }
        }
    }

    /** Creates a read-only text field with monospace font for displaying commands. */
    private fun createCommandField(command: String): JBTextField {
        val consoleFontName = EditorColorsManager.getInstance().globalScheme.consoleFontName
        return JBTextField(command).apply {
            isEditable = false
            font = Font(consoleFontName, Font.PLAIN, font.size)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 6)
            )
        }
    }
}
