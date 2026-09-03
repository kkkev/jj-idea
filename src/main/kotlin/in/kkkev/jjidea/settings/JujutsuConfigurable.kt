package `in`.kkkev.jjidea.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.GitPushDialog
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.jj.cli.Config
import `in`.kkkev.jjidea.jj.cli.config
import `in`.kkkev.jjidea.jj.cli.rootlessConfig
import `in`.kkkev.jjidea.ui.services.SPONSORS_URL
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.util.runLaterInModal
import `in`.kkkev.jjidea.vcs.diffbase.DiffbaseService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoredFilesService
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Settings panel for Jujutsu plugin configuration.
 *
 * Appears under Settings → Version Control → Jujutsu
 */
class JujutsuConfigurable(
    private val project: Project,
    private val repos: Collection<JujutsuRepository>
) : BoundConfigurable(JujutsuBundle.message("settings.title")) {
    // The platform instantiates Configurables via reflection looking for a single-Project
    // constructor; a Kotlin default parameter on the primary constructor doesn't expose that
    // overload to Java on its own (this broke Settings entirely — jj-idea-ye1x). A secondary
    // constructor is the plain-Kotlin fix, and doubles as the test seam so a width-guard test
    // can exercise the per-repo "Repository Settings" group without a real jj repository on disk.
    constructor(project: Project) : this(project, project.stateModel.initialisedRepositories.value.values)

    private val log = Logger.getInstance(javaClass)
    private val settings = JujutsuSettings.getInstance(project)
    private val appSettings = JujutsuApplicationSettings.getInstance()
    private var previousPath = appSettings.state.jjExecutablePath
    private var previousLogLimit = settings.state.logChangeLimit
    private var previousLogRevset = settings.state.logRevset
    private var previousHideStandardCommitToolWindow = settings.state.hideStandardCommitToolWindow
    private var previousDisableIgnoredFileScanning = appSettings.state.disableIgnoredFileScanning
    private var previousDiffbaseStrategy = settings.state.diffbaseStrategy
    private var previousCustomDiffbaseRevset = settings.state.customDiffbaseRevset
    private val finder = JjExecutableFinder()

    // UI components for validation feedback
    private lateinit var pathField: Cell<TextFieldWithBrowseButton>
    private val validationLabel = JBLabel()

    // Revset validation
    private lateinit var revsetField: Cell<JBTextArea>
    private val revsetValidationPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private var revsetError: String? = null

    // jj-idea-fwea: diff-base validation
    private lateinit var diffbaseCustomOption: Cell<JBRadioButton>
    private lateinit var diffbaseRevsetField: Cell<JBTextField>
    private val diffbaseValidationLabel = JBLabel()
    private var diffbaseError: String? = null

    // jj-idea-258c: so its expansion can react live to a jj-availability status change (see the
    // status.connect wiring in createPanel) — never auto-collapse a group the user may already
    // have opened themselves.
    private lateinit var installGroupRow: CollapsibleRow

    // Global identity — backing properties for bindText(); async-loaded from jj config
    private var globalNameBinding = ""
    private var globalEmailBinding = ""
    private var globalNameField: JBTextField? = null
    private var globalEmailField: JBTextField? = null

    // Per-repo settings
    private var repoSettingsDirty = false

    private data class RepoSettingsPanel(
        val repo: JujutsuRepository,
        val identityCb: JBCheckBox,
        val nameField: JBTextField,
        val emailField: JBTextField,
        val limitCb: JBCheckBox,
        val limitField: JBTextField,
        val revsetCb: JBCheckBox,
        val revsetField: JBTextField,
        // jj-idea-ixju: split into an override toggle + value checkbox, since the global default
        // can now itself be true - "unchecked value" must be distinguishable from "no override".
        val disableScanOverrideCb: JBCheckBox,
        val disableScanCb: JBCheckBox,
        // jj-idea-isnf: per-repo context-window override.
        val contextCb: JBCheckBox,
        val contextField: JBTextField,
        // jj-idea-fwea: per-repo diff-base override.
        val diffbaseOverrideCb: JBCheckBox,
        val diffbaseCombo: ComboBox<DiffbaseStrategy>,
        val diffbaseRevsetField: JBTextField,
        val revsetValidationLabel: JBLabel = JBLabel(),
        var revsetError: String? = null
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
                    // jj-idea-bslw: was COLUMNS_MEDIUM — this row (path field + browse icon +
                    // Test button) was the widest in the panel and overflowed a narrower Settings
                    // window. Still resizable/FILL, so it still expands to fill available width;
                    // this only lowers its minimum/preferred contribution.
                    .columns(COLUMNS_SHORT)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(
                        JujutsuBundle.message("settings.jj.path.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )

                button(JujutsuBundle.message("settings.jj.path.test")) {
                    testExecutable()
                }
            }
            row("") {
                cell(validationLabel)
            }
        }

        installGroupRow = collapsibleGroup(JujutsuBundle.message("settings.group.install")) {
            // Check current status to decide install vs upgrade, and which features (if any) are
            // gated. This content is a one-shot snapshot at panel build time, not rebuilt on
            // later status changes — only installGroupRow.expanded reacts live (see the
            // status.connect wiring below). So a status change mid-session (e.g. editing the
            // path and clicking Apply without closing Settings) opens this group but may still
            // show stale wording until Settings is reopened.
            val status = JjAvailabilityChecker.getInstance(project).status.value
            val isUpgrade = installHelpIsUpgradeFor(status)
            val detectedMethod = detectedInstallMethodFor(status)
            // jj-idea-258c: folded in from the former standalone "Feature Availability" group —
            // Scenario A (BelowMinimum) deliberately excludes this list, same rule
            // unsupportedFeatures(status) already encodes: it has its own balloon and
            // JjNotInstalledPanel and must not be double-reported here.
            val gatedFeatures = (featureAvailabilityFor(status) as? FeatureAvailability.Gated)?.features.orEmpty()

            row {
                val descriptionKey = if (gatedFeatures.isNotEmpty()) {
                    "settings.upgrade.description.features"
                } else if (isUpgrade) {
                    "settings.upgrade.description"
                } else {
                    "settings.install.description"
                }
                label(JujutsuBundle.message(descriptionKey))
            }

            if (gatedFeatures.isNotEmpty()) {
                indent {
                    gatedFeatures.forEach { feature ->
                        row {
                            cell(featureLine(feature))
                        }
                    }
                }
            }
            if (gatedFeatures.isNotEmpty()) {
                row {
                    label(JujutsuBundle.message("settings.upgrade.using"))
                }
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

        // jj-idea-258c: auto-expand Installation Help the moment jj is gated or below minimum —
        // never auto-collapse a group the user may already have opened themselves. `disposable`
        // is null when a test builds the panel directly via createPanel() (it's set by
        // DslConfigurableBase before createPanel() runs in the real Settings dialog); the initial
        // `expanded = false` above already covers that case with a one-time snapshot.
        disposable?.let { parent ->
            JjAvailabilityChecker.getInstance(project).status.connect(parent) { status ->
                if (installHelpIsUpgradeFor(status)) {
                    installGroupRow.expanded = true
                }
            }
        }

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

        group(JujutsuBundle.message("settings.group.general")) {
            row {
                checkBox(JujutsuBundle.message("settings.general.hide.commit.toolwindow"))
                    .bindSelected(settings.state::hideStandardCommitToolWindow)
                    .comment(
                        JujutsuBundle.message("settings.general.hide.commit.toolwindow.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
            }
            row {
                checkBox(JujutsuBundle.message("settings.general.disable.ignore.scan"))
                    .bindSelected(appSettings.state::disableIgnoredFileScanning)
                    .comment(
                        JujutsuBundle.message("settings.general.disable.ignore.scan.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
            }
            row(JujutsuBundle.message("settings.general.default.push.scope.label")) {
                comboBox(GitPushDialog.PushScope.entries.toList())
                    .applyToComponent {
                        // Replacement (textListCellRenderer) unavailable until 2026.2
                        @Suppress("removal")
                        renderer = SimpleListCellRenderer.create("") { pushScopeLabel(it) }
                    }
                    .bindItem(
                        { defaultPushScope() },
                        { settings.state.defaultPushScope = (it ?: GitPushDialog.PushScope.DEFAULT).name }
                    )
                    .comment(
                        JujutsuBundle.message("settings.general.default.push.scope.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
            }
        }

        group(JujutsuBundle.message("settings.group.log")) {
            row(JujutsuBundle.message("settings.log.limit.label")) {
                intTextField(range = 1..10000)
                    .bindIntText(settings.state::logChangeLimit)
                    .columns(COLUMNS_TINY)
                    .comment(
                        JujutsuBundle.message("settings.log.limit.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
            }
            row(JujutsuBundle.message("settings.log.context.window.label")) {
                intTextField(range = 0..1000)
                    .bindIntText(settings.state::logContextWindow)
                    .columns(COLUMNS_TINY)
                    .comment(
                        JujutsuBundle.message("settings.log.context.window.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
            }
            row {
                // Built manually rather than via row(label) { } so the label can be pinned to
                // the top of the row instead of centering against the multi-line field
                // (jj-idea-bwdk) — layout(LABEL_ALIGNED) keeps this row's label/field columns
                // sharing width with the "Changes to show:"/"Context window:" rows above.
                layout(RowLayout.LABEL_ALIGNED)
                val revsetLabel = label(JujutsuBundle.message("settings.log.revset.label"))
                    .align(AlignY.TOP)
                    .component
                revsetLabel.putClientProperty(DslComponentProperty.ROW_LABEL, true)

                // A multi-line field (jj-idea-bwdk) so its left edge lines up with the fields
                // above instead of being indented under its own label, and so long expressions
                // have room to be read without horizontal scrolling.
                revsetField = textArea()
                    .bindText(settings.state::logRevset)
                    .rows(REVSET_FIELD_ROWS)
                    .columns(COLUMNS_MEDIUM)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    // Word-wrap only — the field holds one logical expression; Enter still
                    // inserts a literal newline, which jj's revset parser tolerates as
                    // insignificant whitespace, but nothing here encourages typing one.
                    .applyToComponent {
                        lineWrap = true
                        wrapStyleWord = true
                    }
                    .validationOnApply {
                        revsetError?.let { error(it) }
                    }
                    .also {
                        it.component.document.addDocumentListener(clearErrorListener { revsetError = null })
                    }
                    .comment(
                        JujutsuBundle.message("settings.log.revset.comment"),
                        maxLineLength = NARROW_COMMENT_WIDTH
                    )
                revsetLabel.labelFor = revsetField.component
                button(JujutsuBundle.message("settings.log.revset.test")) {
                    testRevset()
                }.align(AlignY.TOP)
            }
            row("") {
                cell(revsetValidationPanel).align(AlignX.FILL)
            }
        }

        group(JujutsuBundle.message("settings.group.diffbase")) {
            buttonsGroup {
                row {
                    radioButton(JujutsuBundle.message("settings.diffbase.workingcopy"))
                        .bindSelected(
                            { settings.state.diffbaseStrategy == DiffbaseStrategy.WORKING_COPY_PARENT },
                            { if (it) settings.state.diffbaseStrategy = DiffbaseStrategy.WORKING_COPY_PARENT }
                        )
                    contextHelp(JujutsuBundle.message("settings.diffbase.workingcopy.help"))
                }
                row {
                    radioButton(JujutsuBundle.message("settings.diffbase.immutable"))
                        .bindSelected(
                            { settings.state.diffbaseStrategy == DiffbaseStrategy.IMMUTABLE_ANCESTOR },
                            { if (it) settings.state.diffbaseStrategy = DiffbaseStrategy.IMMUTABLE_ANCESTOR }
                        )
                    contextHelp(
                        JujutsuBundle.message(
                            "settings.diffbase.immutable.help",
                            DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET
                        )
                    )
                }
                row {
                    diffbaseCustomOption = radioButton(JujutsuBundle.message("settings.diffbase.custom"))
                        .bindSelected(
                            { settings.state.diffbaseStrategy == DiffbaseStrategy.CUSTOM_REVSET },
                            { if (it) settings.state.diffbaseStrategy = DiffbaseStrategy.CUSTOM_REVSET }
                        )
                }
                indent {
                    row {
                        diffbaseRevsetField = textField()
                            .bindText(settings.state::customDiffbaseRevset)
                            .columns(COLUMNS_MEDIUM)
                            .align(AlignX.FILL)
                            .resizableColumn()
                            .enabledIf(diffbaseCustomOption.selected)
                            .also {
                                it.component.document.addDocumentListener(clearErrorListener { diffbaseError = null })
                            }
                            .validationOnApply {
                                if (diffbaseCustomOption.component.isSelected && it.text.isBlank()) {
                                    error(JujutsuBundle.message("settings.diffbase.error.empty"))
                                } else {
                                    diffbaseError?.let { message -> error(message) }
                                }
                            }
                        button(JujutsuBundle.message("settings.log.revset.test")) {
                            testDiffbaseRevset()
                        }
                    }
                    row("") {
                        cell(diffbaseValidationLabel)
                    }
                }
            }
            row {
                comment(
                    JujutsuBundle.message("settings.diffbase.comment"),
                    maxLineLength = NARROW_COMMENT_WIDTH
                )
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
                    val revsetCb = JBCheckBox(JujutsuBundle.message("settings.repo.logrevset.override"))
                    val revsetField = JBTextField()
                    val disableScanOverrideCb =
                        JBCheckBox(JujutsuBundle.message("settings.repo.disableignorescan.override"))
                    val disableScanCb = JBCheckBox(JujutsuBundle.message("settings.repo.disableignorescan"))
                    val contextCb = JBCheckBox(JujutsuBundle.message("settings.repo.contextwindow.override"))
                    val contextField = JBTextField()
                    val diffbaseOverrideCb = JBCheckBox(JujutsuBundle.message("settings.repo.diffbase.override"))
                    val diffbaseCombo = ComboBox(DiffbaseStrategy.entries.toTypedArray()).apply {
                        @Suppress("removal")
                        renderer = SimpleListCellRenderer.create("") { diffbaseStrategyLabel(it) }
                    }
                    val diffbaseRevsetField = JBTextField()

                    fun updateIdentityEnabled() {
                        nameField.isEnabled = identityCb.isSelected
                        emailField.isEnabled = identityCb.isSelected
                    }

                    fun updateLimitEnabled() {
                        limitField.isEnabled = limitCb.isSelected
                    }

                    fun updateRevsetEnabled() {
                        revsetField.isEnabled = revsetCb.isSelected
                    }

                    fun updateDisableScanEnabled() {
                        disableScanCb.isEnabled = disableScanOverrideCb.isSelected
                    }

                    fun updateContextEnabled() {
                        contextField.isEnabled = contextCb.isSelected
                    }

                    fun updateDiffbaseEnabled() {
                        diffbaseCombo.isEnabled = diffbaseOverrideCb.isSelected
                        diffbaseRevsetField.isEnabled =
                            diffbaseOverrideCb.isSelected &&
                            diffbaseCombo.selectedItem == DiffbaseStrategy.CUSTOM_REVSET
                    }

                    identityCb.addActionListener {
                        updateIdentityEnabled()
                        repoSettingsDirty = true
                    }
                    limitCb.addActionListener {
                        updateLimitEnabled()
                        repoSettingsDirty = true
                    }
                    revsetCb.addActionListener {
                        updateRevsetEnabled()
                        repoSettingsDirty = true
                    }
                    disableScanOverrideCb.addActionListener {
                        updateDisableScanEnabled()
                        repoSettingsDirty = true
                    }
                    disableScanCb.addActionListener { repoSettingsDirty = true }
                    contextCb.addActionListener {
                        updateContextEnabled()
                        repoSettingsDirty = true
                    }
                    diffbaseOverrideCb.addActionListener {
                        updateDiffbaseEnabled()
                        repoSettingsDirty = true
                    }
                    diffbaseCombo.addActionListener {
                        updateDiffbaseEnabled()
                        repoSettingsDirty = true
                    }
                    nameField.document.addDocumentListener(dirtyListener)
                    emailField.document.addDocumentListener(dirtyListener)
                    limitField.document.addDocumentListener(dirtyListener)
                    revsetField.document.addDocumentListener(dirtyListener)
                    contextField.document.addDocumentListener(dirtyListener)
                    diffbaseRevsetField.document.addDocumentListener(dirtyListener)

                    // Load limit, revset, ignore-scan, context-window, and diff-base overrides (synchronous)
                    val repoPath = repo.directory.path
                    val repoConfig = settings.state.repositoryOverrides[repoPath]
                    limitCb.isSelected = repoConfig?.logChangeLimit != null
                    limitField.text = repoConfig?.logChangeLimit?.toString() ?: ""
                    revsetCb.isSelected = repoConfig?.logRevset != null
                    revsetField.text = repoConfig?.logRevset ?: ""
                    disableScanOverrideCb.isSelected = repoConfig?.disableIgnoredFileScanning != null
                    disableScanCb.isSelected = repoConfig?.disableIgnoredFileScanning == true
                    contextCb.isSelected = repoConfig?.logContextWindow != null
                    contextField.text = repoConfig?.logContextWindow?.toString() ?: ""
                    diffbaseOverrideCb.isSelected = repoConfig?.diffbaseStrategy != null
                    diffbaseCombo.selectedItem = repoConfig?.diffbaseStrategy ?: DiffbaseStrategy.WORKING_COPY_PARENT
                    diffbaseRevsetField.text = repoConfig?.customDiffbaseRevset ?: ""

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
                    updateRevsetEnabled()
                    updateDisableScanEnabled()
                    updateContextEnabled()
                    updateDiffbaseEnabled()

                    val repoPanel = RepoSettingsPanel(
                        repo,
                        identityCb,
                        nameField,
                        emailField,
                        limitCb,
                        limitField,
                        revsetCb,
                        revsetField,
                        disableScanOverrideCb,
                        disableScanCb,
                        contextCb,
                        contextField,
                        diffbaseOverrideCb,
                        diffbaseCombo,
                        diffbaseRevsetField
                    )
                    revsetField.document.addDocumentListener(clearErrorListener { repoPanel.revsetError = null })
                    repoSettingsPanels.add(repoPanel)

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
                        row {
                            cell(revsetCb)
                        }
                        indent {
                            row {
                                cell(revsetField).columns(COLUMNS_MEDIUM)
                                    .align(AlignX.FILL)
                                    .resizableColumn()
                                    .validationOnApply {
                                        if (revsetCb.isSelected) {
                                            repoPanel.revsetError?.let { error(it) }
                                        } else {
                                            null
                                        }
                                    }
                                button(JujutsuBundle.message("settings.log.revset.test")) {
                                    testRepoRevset(repoPanel)
                                }
                            }
                            row {
                                cell(repoPanel.revsetValidationLabel)
                            }
                        }
                        row {
                            cell(contextCb)
                            cell(contextField).columns(COLUMNS_TINY)
                        }
                        row {
                            cell(disableScanOverrideCb)
                        }
                        indent {
                            row {
                                cell(disableScanCb)
                                    .comment(
                                        JujutsuBundle.message("settings.repo.disableignorescan.comment"),
                                        maxLineLength = NARROW_COMMENT_WIDTH
                                    )
                            }
                        }
                        row {
                            cell(diffbaseOverrideCb)
                        }
                        indent {
                            row {
                                cell(diffbaseCombo)
                            }
                            // jj-idea-ye1x: on its own row, not sharing one with diffbaseCombo —
                            // combo + a COLUMNS_MEDIUM field together overflowed the settings
                            // panel's width budget (jj-idea-bwdk). Mirrors the project-level
                            // Diff Base group above, which already keeps its custom-revset field
                            // on its own row (`:296-352`).
                            row {
                                cell(diffbaseRevsetField).columns(COLUMNS_MEDIUM)
                                    .align(AlignX.FILL)
                                    .resizableColumn()
                                    .validationOnApply {
                                        if (diffbaseOverrideCb.isSelected &&
                                            diffbaseCombo.selectedItem == DiffbaseStrategy.CUSTOM_REVSET &&
                                            diffbaseRevsetField.text.isBlank()
                                        ) {
                                            error(JujutsuBundle.message("settings.diffbase.error.empty"))
                                        } else {
                                            null
                                        }
                                    }
                            }
                            row("") {
                                comment(
                                    JujutsuBundle.message(
                                        "settings.repo.diffbase.revsets.hint",
                                        DiffbaseStrategy.IMMUTABLE_ANCESTOR_REVSET
                                    ),
                                    maxLineLength = NARROW_COMMENT_WIDTH
                                )
                            }
                        }
                    }.apply { expanded = repos.size == 1 }
                }
            }
        }

        group(JujutsuBundle.message("settings.group.support")) {
            row {
                link(JujutsuBundle.message("settings.support.link")) {
                    BrowserUtil.browse(SPONSORS_URL)
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

    /**
     * One "Advance Bookmark (needs jj 0.39.0)" line inside Installation Help's gated-feature
     * list (jj-idea-258c, folded in from the former standalone "Feature Availability" group).
     * The version clause is baked in as grey secondary text — matching the DSL's `comment()`
     * styling — via an inline HTML `<span>`, since Swing's HTML renderer has no equivalent of a
     * CSS variable/`currentColor` to defer that color to a stylesheet.
     */
    private fun featureLine(feature: JjFeature): JBLabel {
        val greyHex = String.format("#%06x", UIUtil.getContextHelpForeground().rgb and 0xFFFFFF)
        val needs = JujutsuBundle.message("settings.upgrade.feature.needs", feature.minVersion.toString())
        return JBLabel(
            "<html>• ${StringUtil.escapeXmlEntities(feature.displayName)} " +
                "<span style='color:$greyHex'>${StringUtil.escapeXmlEntities(needs)}</span></html>"
        )
    }

    override fun isModified() = super.isModified() || repoSettingsDirty

    override fun apply() {
        super.apply()

        // Re-evaluate the Commit tool window's visibility immediately, without requiring a
        // project reopen: CommitModeManager subscribes to this topic and recomputes the
        // CommitMode from JujutsuVcs.getForcedCommitMode() (jj-idea-wb5l).
        if (settings.state.hideStandardCommitToolWindow != previousHideStandardCommitToolWindow) {
            previousHideStandardCommitToolWindow = settings.state.hideStandardCommitToolWindow
            project.messageBus.syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED).directoryMappingChanged()
        }

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
        var diffbaseOverrideChanged = false
        repoSettingsPanels.forEach { panel ->
            val repoPath = panel.repo.directory.path

            // Save log limit, revset, ignore-scan, context-window, and diff-base overrides to
            // plugin settings
            var currentOverride = settings.state.repositoryOverrides[repoPath]
            val newLimit = if (panel.limitCb.isSelected) panel.limitField.text.trim().toIntOrNull() else null
            val newRevset = if (panel.revsetCb.isSelected) panel.revsetField.text.trim() else null
            // jj-idea-ixju: overrideCb, not disableScanCb.isSelected, decides whether this is an
            // override at all - disableScanCb's value is meaningful (true or false) whenever
            // overrideCb is checked, since the global default can itself be true.
            val newDisableScan = if (panel.disableScanOverrideCb.isSelected) panel.disableScanCb.isSelected else null
            val newContextWindow =
                if (panel.contextCb.isSelected) panel.contextField.text.trim().toIntOrNull() else null
            // jj-idea-fwea: same overrideCb-gates-the-value shape as ignore-scan above.
            val newDiffbaseStrategy =
                if (panel.diffbaseOverrideCb.isSelected) panel.diffbaseCombo.selectedItem as DiffbaseStrategy else null
            val newCustomDiffbaseRevset =
                if (panel.diffbaseOverrideCb.isSelected) panel.diffbaseRevsetField.text.trim() else null

            if (newDiffbaseStrategy != currentOverride?.diffbaseStrategy ||
                newCustomDiffbaseRevset != currentOverride?.customDiffbaseRevset
            ) {
                diffbaseOverrideChanged = true
            }

            if (newLimit != currentOverride?.logChangeLimit ||
                newRevset != currentOverride?.logRevset ||
                newDisableScan != currentOverride?.disableIgnoredFileScanning ||
                newContextWindow != currentOverride?.logContextWindow ||
                newDiffbaseStrategy != currentOverride?.diffbaseStrategy ||
                newCustomDiffbaseRevset != currentOverride?.customDiffbaseRevset
            ) {
                val updated = (currentOverride ?: RepositoryConfig()).copy(
                    logChangeLimit = newLimit,
                    logRevset = newRevset,
                    disableIgnoredFileScanning = newDisableScan,
                    logContextWindow = newContextWindow,
                    diffbaseStrategy = newDiffbaseStrategy,
                    customDiffbaseRevset = newCustomDiffbaseRevset
                )
                if (updated.isEmpty()) {
                    settings.state.repositoryOverrides.remove(repoPath)
                } else {
                    settings.state.repositoryOverrides[repoPath] = updated
                }
            }

            // jj-idea-ixju: the scan engine only re-checks disableIgnoredFileScanning(repo) when
            // it (re)scans, and nothing else triggers a rescan on a settings change - without
            // this, flipping the override live shows stale results until the project reloads.
            if (newDisableScan != currentOverride?.disableIgnoredFileScanning) {
                JujutsuIgnoredFilesService.getInstance(project).invalidate(panel.repo)
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

        // jj-idea-ixju: the global default affects every repo without its own override, so a
        // change here must rescan all of them - same reasoning as the per-repo case above.
        if (appSettings.state.disableIgnoredFileScanning != previousDisableIgnoredFileScanning) {
            previousDisableIgnoredFileScanning = appSettings.state.disableIgnoredFileScanning
            repos.forEach { JujutsuIgnoredFilesService.getInstance(project).invalidate(it) }
        }

        // If executable path changed, recheck availability and refresh downstream state
        val newPath = appSettings.state.jjExecutablePath
        if (newPath != previousPath) {
            previousPath = newPath
            val checker = JjAvailabilityChecker.getInstance(project)
            checker.recheck()
            // Directly trigger downstream refresh — initializedRoots.invalidate() alone
            // may produce data-class-equal repos (same project+directory), suppressing the
            // change notification. Explicitly refreshing workingCopies and logRefresh
            // ensures the UI picks up the new executable immediately.
            project.stateModel.workingCopies.invalidate()
            project.stateModel.logRefresh.notify(Unit)
        }

        // If log limit or revset changed, reload the log
        val newLogLimit = settings.state.logChangeLimit
        val newLogRevset = settings.state.logRevset
        if (newLogLimit != previousLogLimit || newLogRevset != previousLogRevset) {
            previousLogLimit = newLogLimit
            previousLogRevset = newLogRevset
            project.stateModel.logRefresh.notify(Unit)
        }

        // jj-idea-fwea: if the project-level diff base changed, or any per-repo override did
        // (diffbaseOverrideChanged, computed above), refresh every open editor's gutter markers
        // and Annotate cache so the new base takes effect without a restart.
        val newDiffbaseStrategy = settings.state.diffbaseStrategy
        val newCustomDiffbaseRevset = settings.state.customDiffbaseRevset
        if (newDiffbaseStrategy != previousDiffbaseStrategy ||
            newCustomDiffbaseRevset != previousCustomDiffbaseRevset ||
            diffbaseOverrideChanged
        ) {
            previousDiffbaseStrategy = newDiffbaseStrategy
            previousCustomDiffbaseRevset = newCustomDiffbaseRevset
            DiffbaseService.getInstance(project).notifyDiffbaseChanged()
        }
    }

    private fun testRevset() {
        val expression = revsetField.component.text.trim()
        revsetValidationPanel.removeAll()
        revsetValidationPanel.add(iconLabel(null, JujutsuBundle.message("settings.log.revset.test.testing")))
        revsetValidationPanel.revalidate()
        revsetValidationPanel.repaint()

        runInBackground {
            val results = repos.map { repo ->
                val panel = repoSettingsPanels.find { it.repo == repo }
                val hasOverride = panel?.revsetCb?.isSelected == true
                val effectiveRevset = if (hasOverride) panel.revsetField.text.trim() else expression
                val result = runRevsetTest(repo, effectiveRevset)
                Triple(repo, result, hasOverride)
            }

            runLater {
                revsetValidationPanel.removeAll()
                revsetError = null
                results.forEach { (repo, result, hasOverride) ->
                    val (icon, msg) = if (result !is CommandExecutor.CommandResult.Success) {
                        revsetError = result.stderr.trim()
                        AllIcons.General.Error to JujutsuBundle.message(
                            "settings.log.revset.test.error",
                            repo.displayName,
                            result.stderr.trim()
                        )
                    } else {
                        val count = result.stdout.length
                        val key = if (hasOverride) {
                            "settings.log.revset.test.valid.override"
                        } else {
                            "settings.log.revset.test.valid"
                        }
                        AllIcons.General.InspectionsOK to JujutsuBundle.message(key, repo.displayName, count)
                    }
                    revsetValidationPanel.add(iconLabel(icon, msg))
                }
                revsetValidationPanel.revalidate()
                revsetValidationPanel.repaint()
            }
        }
    }

    private fun testRepoRevset(panel: RepoSettingsPanel) {
        val expression = panel.revsetField.text.trim()
        showRevsetResult(panel.revsetValidationLabel, null, JujutsuBundle.message("settings.log.revset.test.testing"))
        panel.revsetError = null

        runInBackground {
            val result = runRevsetTest(panel.repo, expression)
            runLater {
                if (result is CommandExecutor.CommandResult.Success) {
                    val count = result.stdout.length
                    showRevsetResult(
                        panel.revsetValidationLabel,
                        true,
                        JujutsuBundle.message("settings.log.revset.test.valid", panel.repo.displayName, count)
                    )
                    panel.revsetError = null
                } else {
                    val errorMsg = result.stderr.trim()
                    showRevsetResult(
                        panel.revsetValidationLabel,
                        false,
                        JujutsuBundle.message("settings.log.revset.test.error.single", errorMsg)
                    )
                    panel.revsetError = errorMsg
                }
            }
        }
    }

    private fun runRevsetTest(repo: JujutsuRepository, expression: String): CommandExecutor.CommandResult {
        val revset = if (expression.isEmpty()) Revset.Default else Expression(expression)
        return repo.commandExecutor.log(revset = revset, template = "'.'", limit = 10000)
    }

    /**
     * Resolves the project-level custom diff-base revset against every repo (jj-idea-fwea), the
     * same shape as [testRevset] for the log revset — but unlike the log revset (which is fine
     * matching many changes), a diff base needs to resolve to *exactly one* revision, so a
     * revset matching more than one is flagged as an error here rather than reported as valid.
     * [DiffbaseService.resolve] applies the same "not exactly one is a failure" rule at
     * resolution time, falling back to `@-` rather than picking one of the matches arbitrarily.
     * A per-repo diff-base override, if checked, is tested with its own revset instead of the
     * project-level field's value.
     */
    private fun testDiffbaseRevset() {
        val expression = diffbaseRevsetField.component.text.trim()
        showRevsetResult(diffbaseValidationLabel, null, JujutsuBundle.message("settings.log.revset.test.testing"))
        diffbaseError = null

        runInBackground {
            val results = repos.map { repo ->
                val panel = repoSettingsPanels.find { it.repo == repo }
                val hasOverride = panel?.diffbaseOverrideCb?.isSelected == true &&
                    panel.diffbaseCombo.selectedItem == DiffbaseStrategy.CUSTOM_REVSET
                val effectiveRevset = if (hasOverride) panel.diffbaseRevsetField.text.trim() else expression
                repo to runRevsetTest(repo, effectiveRevset)
            }

            runLater {
                val failure = results.firstOrNull { (_, result) -> result !is CommandExecutor.CommandResult.Success }
                val ambiguous = results.firstOrNull { (_, result) ->
                    result is CommandExecutor.CommandResult.Success &&
                        result.stdout.length > 1
                }
                when {
                    failure != null -> {
                        val errorMsg = failure.second.stderr.trim()
                        diffbaseError = errorMsg
                        showRevsetResult(
                            diffbaseValidationLabel,
                            false,
                            JujutsuBundle.message("settings.log.revset.test.error.single", errorMsg)
                        )
                    }
                    ambiguous != null -> {
                        val (repo, result) = ambiguous
                        val errorMsg = JujutsuBundle.message(
                            "settings.diffbase.error.ambiguous",
                            repo.displayName,
                            result.stdout.length
                        )
                        diffbaseError = errorMsg
                        showRevsetResult(diffbaseValidationLabel, false, errorMsg)
                    }
                    else -> {
                        diffbaseError = null
                        showRevsetResult(
                            diffbaseValidationLabel,
                            true,
                            JujutsuBundle.message("settings.diffbase.test.valid")
                        )
                    }
                }
            }
        }
    }

    private fun diffbaseStrategyLabel(strategy: DiffbaseStrategy): String = when (strategy) {
        DiffbaseStrategy.WORKING_COPY_PARENT -> JujutsuBundle.message("settings.diffbase.workingcopy")
        DiffbaseStrategy.IMMUTABLE_ANCESTOR -> JujutsuBundle.message("settings.diffbase.immutable")
        DiffbaseStrategy.CUSTOM_REVSET -> JujutsuBundle.message("settings.diffbase.custom")
    }

    private fun iconLabel(icon: javax.swing.Icon?, text: String) = JBLabel(wrapped(text), icon, JBLabel.LEADING)

    /**
     * Wraps [text] (which may contain raw `jj` stderr or a long path) in HTML with a fixed
     * body width, so validation-result labels wrap instead of driving the settings panel's
     * preferred width past the page (jj-idea-bwdk).
     */
    private fun wrapped(text: String, width: Int = VALIDATION_MESSAGE_WIDTH): String =
        "<html><body width='${JBUI.scale(width)}'>" +
            StringUtil.escapeXmlEntities(text) +
            "</body></html>"

    private fun defaultPushScope(): GitPushDialog.PushScope =
        GitPushDialog.parsePushScope(settings.state.defaultPushScope)

    private fun pushScopeLabel(scope: GitPushDialog.PushScope): String = when (scope) {
        GitPushDialog.PushScope.DEFAULT -> JujutsuBundle.message("dialog.git.push.scope.default")
        GitPushDialog.PushScope.BOOKMARK -> JujutsuBundle.message("dialog.git.push.scope.bookmark")
        GitPushDialog.PushScope.ALL -> JujutsuBundle.message("dialog.git.push.scope.all")
        GitPushDialog.PushScope.CHANGE -> JujutsuBundle.message("settings.general.default.push.scope.change")
    }

    private fun showRevsetResult(label: JBLabel, success: Boolean?, message: String) {
        label.text = wrapped(message)
        label.icon = when (success) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> AllIcons.Process.Step_1
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
                            // If the tested path matches the currently applied path,
                            // trigger a recheck so the plugin picks up an upgraded binary
                            if (path == appSettings.state.jjExecutablePath) {
                                JjAvailabilityChecker.getInstance(project).recheck()
                            }
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
        validationLabel.text = wrapped(message)
        validationLabel.icon = when (success) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> AllIcons.Process.Step_1 // Loading indicator
        }
    }

    /** Test seam for jj-idea-bwdk's panel-width regression guard. */
    internal fun showValidationResultForTest(message: String) = showValidationResult(false, message)

    /**
     * Test seam for jj-idea-258c's label/field gap regression guard: a collapsed
     * [CollapsibleRow]'s content isn't actually positioned by layout (only measured for
     * preferred size), so a test that lays the panel out for real to check rendered gaps needs
     * this expanded first.
     */
    internal fun expandInstallGroupForTest() {
        installGroupRow.expanded = true
    }

    /** Creates a row with method name, monospace command in a box, and copy button. */
    private fun Panel.commandRow(methodName: String, command: String) {
        row {
            // jj-idea-bslw/258c: built manually (not the row(label) shorthand) so `.gap()` can
            // override the label's gap to the field. The DSL auto-applies RightGap.SMALL after a
            // row label, which for a short label like "Homebrew:" reads as flush against the
            // field. A JBUI.Borders-based approach doesn't help here: the grid treats a
            // component's own border as "visual padding" and compensates the *next* cell's
            // position to absorb it, so extra border width is invisible in the actual gap
            // (confirmed empirically — border insets showed up in preferredSize but not on
            // screen). RightGap.COLUMNS is the real inter-cell gap the grid actually uses.
            layout(RowLayout.LABEL_ALIGNED)
            label("$methodName:").gap(RightGap.COLUMNS)
            cell(createCommandField(command))
                .align(AlignX.FILL)
                .resizableColumn()
            button(JujutsuBundle.message("settings.install.copy")) {
                CopyPasteManager.getInstance().setContents(StringSelection(command))
            }
        }
    }

    private fun clearErrorListener(clear: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = clear()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = clear()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = clear()
    }

    companion object {
        /** Max width (unscaled px) for wrapped validation-result labels (jj-idea-bwdk). */
        private const val VALIDATION_MESSAGE_WIDTH = 320

        /**
         * Narrower alternative to the DSL's [com.intellij.ui.dsl.builder.DEFAULT_COMMENT_WIDTH]
         * (70 chars), for a comment cell sharing its row with a wide control and a button —
         * at 70 chars the comment itself becomes the row's widest element (jj-idea-bwdk).
         */
        private const val NARROW_COMMENT_WIDTH = 50

        /** Visible height of the revset expression field, in text rows (jj-idea-bwdk). */
        private const val REVSET_FIELD_ROWS = 3
    }

    /** Creates a read-only text field with monospace font for displaying commands. */
    private fun createCommandField(command: String): JBTextField {
        val consoleFontName = EditorColorsManager.getInstance().globalScheme.consoleFontName
        return JBTextField(command, COLUMNS_SHORT).apply {
            isEditable = false
            font = Font(consoleFontName, Font.PLAIN, font.size)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 6)
            )
        }
    }
}
