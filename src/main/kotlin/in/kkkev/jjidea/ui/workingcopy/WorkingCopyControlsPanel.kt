package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.StringBuilderHtmlTextCanvas
import `in`.kkkev.jjidea.ui.append
import `in`.kkkev.jjidea.vcs.actions.requestDescription
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Per-repository working copy controls: description editor, current change info, and action buttons.
 * This panel is bound to a specific repository and updates when the bound repository changes.
 */
class WorkingCopyControlsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val log = Logger.getInstance(javaClass)

    /** Provider for per-repo description state */
    var stateProvider: ((JujutsuRepository) -> DescriptionState)? = null

    /** Callback when user selects a different repo from dropdown */
    var onRepositorySelected: ((JujutsuRepository) -> Unit)? = null

    /** Currently bound repository */
    var boundRepository: JujutsuRepository? = null
        set(value) {
            if (field != value) {
                // Save current state before switching
                field?.let { saveCurrentState(it) }
                field = value
                updateForRepository(value)
                // Update dropdown selection without triggering callback
                updateDropdownSelection(value)
            }
        }

    // Track whether description has been modified since last load
    private var isDescriptionModified = false
    private var persistedDescription = Description.EMPTY

    // Flag to prevent dropdown selection changes from triggering callbacks during programmatic updates
    private var updatingDropdown = false

    // UI Components
    private val repoSelector = JComboBox<RepoItem>().apply {
        isVisible = false // Hidden until we know there are multiple repos
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                text = (value as? RepoItem)?.displayName ?: ""
            }
        }
        addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updatingDropdown) {
                (e.item as? RepoItem)?.repo?.let { repo ->
                    onRepositorySelected?.invoke(repo)
                }
            }
        }
    }

    private val descriptionArea = JBTextArea().apply {
        rows = 4
        columns = 50
        lineWrap = true
        wrapStyleWord = true
        isEditable = true
        toolTipText = JujutsuBundle.message("toolwindow.description.tooltip")

        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = checkModified()
            override fun removeUpdate(e: DocumentEvent?) = checkModified()
            override fun changedUpdate(e: DocumentEvent?) = checkModified()

            private fun checkModified() {
                val newModified = text != persistedDescription.actual
                if (newModified != isDescriptionModified) {
                    isDescriptionModified = newModified
                    updateDescriptionLabel()
                }
            }
        })
    }

    private val currentChangeLabel = JBLabel().apply {
        font = font.deriveFont(13f)
    }

    private val descriptionLabel = JBLabel(JujutsuBundle.message("toolwindow.description.label"))

    private lateinit var describeButton: JButton
    private lateinit var revertButton: JButton

    init {
        createUI()
    }

    private fun createUI() {
        border = JBUI.Borders.empty(8)

        val topPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2)
        }

        // Repository selector (only visible in multi-root projects)
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        topPanel.add(repoSelector, gbc)

        // Current change label (shows repo:changeId format)
        gbc.gridy = 1
        topPanel.add(currentChangeLabel, gbc)

        // Description label with inline action buttons
        val descriptionHeaderPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(descriptionLabel)
            add(Box.createHorizontalStrut(8))
            add(createDescribeButton())
            add(Box.createHorizontalStrut(4))
            add(createRevertButton())
            add(Box.createHorizontalGlue())
        }

        gbc.gridy = 2
        topPanel.add(descriptionHeaderPanel, gbc)

        // Description text area with scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(descriptionArea).apply {
            minimumSize = JBUI.size(200, 70)
            preferredSize = JBUI.size(400, 90)
        }
        gbc.gridy = 3
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        topPanel.add(scrollPane, gbc)

        add(topPanel, BorderLayout.CENTER)
    }

    /** Update the list of available repositories in the dropdown */
    fun updateAvailableRepositories(repos: List<JujutsuRepository>) {
        updatingDropdown = true
        try {
            var selectedRepo = boundRepository
            repoSelector.removeAllItems()
            repos.forEach { repo ->
                repoSelector.addItem(RepoItem(repo))
            }
            if ((selectedRepo == null) || (selectedRepo !in repos)) {
                selectedRepo = repos.first()
            }
            // Hide dropdown if only one repo
            repoSelector.isVisible = repos.size > 1
            // Restore selection
            updateDropdownSelection(selectedRepo)
        } finally {
            updatingDropdown = false
        }
    }

    private fun updateDropdownSelection(repo: JujutsuRepository?) {
        if (repo == null) return
        updatingDropdown = true
        try {
            for (i in 0 until repoSelector.itemCount) {
                if (repoSelector.getItemAt(i).repo == repo) {
                    repoSelector.selectedIndex = i
                    break
                }
            }
        } finally {
            updatingDropdown = false
        }
    }

    fun createToolbar(owner: JComponent): ActionToolbar {
        val group = DefaultActionGroup()

        // New Change action - primary workflow operation
        group.add(object : DumbAwareAction(
            JujutsuBundle.message("button.newchange"),
            JujutsuBundle.message("button.newchange.tooltip"),
            AllIcons.General.Add
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                createNewChange()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = boundRepository != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        return ActionManager.getInstance()
            .createActionToolbar("JujutsuWorkingCopyToolbar", group, true).apply {
                targetComponent = owner
            }
    }

    private fun createDescribeButton(): JButton {
        describeButton = JButton(JujutsuBundle.message("button.describe")).apply {
            toolTipText = JujutsuBundle.message("button.describe.tooltip")
            icon = AllIcons.Actions.MenuSaveall
            addActionListener { describeCurrentChange() }
            isEnabled = false
        }
        return describeButton
    }

    private fun createRevertButton(): JButton {
        revertButton = JButton("Revert").apply {
            toolTipText = "Reload description from working copy"
            icon = AllIcons.Actions.Rollback
            addActionListener { revertDescription() }
            isEnabled = false
        }
        return revertButton
    }

    private fun saveCurrentState(repo: JujutsuRepository) {
        stateProvider?.invoke(repo)?.let { state ->
            state.persisted = persistedDescription
            state.isModified = isDescriptionModified
        }
    }

    private fun updateForRepository(repo: JujutsuRepository?) {
        if (repo == null) {
            descriptionArea.text = ""
            descriptionArea.isEnabled = false
            currentChangeLabel.text = ""
            isDescriptionModified = false
            persistedDescription = Description.EMPTY
            updateDescriptionLabel()
            return
        }

        descriptionArea.isEnabled = true

        // Restore state from provider
        val state = stateProvider?.invoke(repo)
        if (state != null) {
            persistedDescription = state.persisted
            isDescriptionModified = state.isModified
            descriptionArea.text = persistedDescription.actual
        } else {
            persistedDescription = Description.EMPTY
            isDescriptionModified = false
            descriptionArea.text = ""
        }

        updateDescriptionLabel()
        loadCurrentDescription(repo)
    }

    /**
     * Update from model data (called when state model changes).
     */
    fun update(logEntry: LogEntry) {
        if (logEntry.repo != boundRepository) return

        ApplicationManager.getApplication().invokeLater {
            persistedDescription = logEntry.description
            if (!isDescriptionModified) {
                descriptionArea.text = persistedDescription.actual
            }
            isDescriptionModified = descriptionArea.text != persistedDescription.actual
            updateDescriptionLabel()
            updateWorkingCopyLabel(logEntry)
        }
    }

    private fun updateDescriptionLabel() {
        val baseLabel = JujutsuBundle.message("toolwindow.description.label")
        descriptionLabel.text = if (isDescriptionModified) "$baseLabel *" else baseLabel
        describeButton.isEnabled = isDescriptionModified && boundRepository != null
        revertButton.isEnabled = isDescriptionModified && boundRepository != null
    }

    private fun revertDescription() {
        descriptionArea.text = persistedDescription.actual
        isDescriptionModified = false
        updateDescriptionLabel()
    }

    private fun describeCurrentChange() {
        val repo = boundRepository ?: return
        val description = Description(descriptionArea.text.trim())

        repo.commandExecutor
            .createCommand { describe(description) }
            .onSuccess {
                persistedDescription = description
                isDescriptionModified = false
                updateDescriptionLabel()
                repo.invalidate()
            }.onFailure {
                JOptionPane.showMessageDialog(
                    this@WorkingCopyControlsPanel,
                    JujutsuBundle.message("dialog.describe.error.message", stderr),
                    JujutsuBundle.message("dialog.describe.error.title"),
                    JOptionPane.ERROR_MESSAGE
                )
            }.executeAsync()
    }

    private fun createNewChange() {
        val repo = boundRepository ?: return
        val description = project.requestDescription("dialog.newchange.input") ?: return

        repo.commandExecutor.createCommand {
            new(description = description)
        }.onSuccess {
            persistedDescription = Description.EMPTY
            descriptionArea.text = ""
            isDescriptionModified = false
            updateDescriptionLabel()
            repo.invalidate(select = WorkingCopy)
        }.onFailure {
            JOptionPane.showMessageDialog(
                this@WorkingCopyControlsPanel,
                JujutsuBundle.message("dialog.newchange.error.message", stderr),
                JujutsuBundle.message("dialog.newchange.error.title"),
                JOptionPane.ERROR_MESSAGE
            )
        }.executeAsync()
    }

    private fun updateWorkingCopyLabel(entry: LogEntry) {
        val labelText = buildString {
            val canvas = StringBuilderHtmlTextCanvas(this)

            append("<html>")
            // Show repo:changeId format
            append("<b>${entry.repo.displayName}</b>:")
            canvas.append(entry.id)
            append(" (")
            canvas.append(entry.commitId)
            append(")")

            if (entry.parentIds.isNotEmpty()) {
                append("<br>")
                append("<font size=-1>")
                append(JujutsuBundle.message("toolwindow.parents.label"))
                append(" ")
                entry.parentIds.forEachIndexed { index, parentId ->
                    if (index > 0) append(", ")
                    canvas.append(parentId)
                }
                append("</font>")
            }

            append("</html>")
        }
        currentChangeLabel.text = labelText
    }

    private fun loadCurrentDescription(repo: JujutsuRepository) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = repo.logService.getLog(WorkingCopy)
            ApplicationManager.getApplication().invokeLater {
                if (boundRepository != repo) return@invokeLater // Repo changed while loading

                result.onSuccess { entries ->
                    entries.firstOrNull()?.let { entry ->
                        persistedDescription = entry.description
                        if (!isDescriptionModified) {
                            descriptionArea.text = persistedDescription.actual
                        }
                        isDescriptionModified = descriptionArea.text != persistedDescription.actual
                        updateDescriptionLabel()
                        updateWorkingCopyLabel(entry)
                    }
                }.onFailure { error ->
                    log.error("Failed to load current description", error)
                }
            }
        }
    }

    /** Wrapper for displaying repository in dropdown */
    private data class RepoItem(val repo: JujutsuRepository) {
        val displayName: String get() = repo.relativePath
    }
}

/**
 * Holds per-repository description editing state.
 */
data class DescriptionState(
    var persisted: Description = Description.EMPTY,
    var isModified: Boolean = false
) {
    /** Stable key for description state (survives repo object recreation) */
    data class Key(val repoPath: String)
}
