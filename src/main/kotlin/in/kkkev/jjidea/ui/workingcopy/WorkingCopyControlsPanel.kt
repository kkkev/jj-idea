package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmark.advanceClosestBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.createBookmarkAction
import `in`.kkkev.jjidea.actions.change.abandonChangeAction
import `in`.kkkev.jjidea.actions.change.splitAction
import `in`.kkkev.jjidea.actions.change.squashAction
import `in`.kkkev.jjidea.actions.change.squashableEntry
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.actions.tag.setTagAction
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.components.appendParents
import `in`.kkkev.jjidea.ui.components.appendSummary
import `in`.kkkev.jjidea.ui.components.htmlString
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
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
            value?.let { update(it.workingCopy) }
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
        val area = this
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
                isDescriptionModified = text != persistedDescription.actual
                updateDescriptionLabel()
            }
        })

        // Explicitly bind Enter to insert a newline rather than relying on JTextArea's default
        // key binding. On platform 2026.2 (build 262) something higher up the focus/action chain
        // now consumes VK_ENTER before it reaches the text area (jj-idea-qa8i / GitHub #57), so we
        // bind it directly at the component level (WHEN_FOCUSED takes priority while this text
        // area has focus) and consume the event here.
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jjidea-insert-newline")
        actionMap.put(
            "jjidea-insert-newline",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    area.replaceSelection("\n")
                }
            }
        )
    }

    private val currentChangeLabel = IconAwareHtmlPane(project)

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

        // Current change label (shows repo:changeId format). The repository selector used to sit
        // above this (gridy=0) but moved up into createTopBar() (jj-idea-xsa8 follow-up) so it's
        // adjacent to the action toolbar it also governs, rather than ~60% down the tool window
        // from it.
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        gbc.weightx = 1.0
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

        gbc.gridy = 1
        topPanel.add(descriptionHeaderPanel, gbc)

        // Description text area with scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(descriptionArea).apply {
            minimumSize = JBUI.size(200, 70)
            preferredSize = JBUI.size(400, 90)
        }
        gbc.gridy = 2
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

    /** Toolbar row plus [repoSelector] (jj-idea-xsa8), so "which repo" sits next to the buttons acting on it. */
    fun createTopBar(owner: JComponent): JComponent {
        val toolbar = createActionToolbar(owner)
        return JPanel(BorderLayout()).apply {
            add(repoSelector, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
    }

    /**
     * The action-only half of [createTopBar] — actions anchored at `@` (New Change plus the
     * working copy's share of the log context menu). Built once, each action added exactly once:
     * `ActionToolbarImpl` requires stable action *instances* across refreshes to reuse each
     * button's `JComponent`, so `update()` on each just flips `isEnabled` from live state, the
     * same way New Change always has. Split out from [createTopBar] so tests can inspect the
     * [ActionToolbar] directly.
     */
    internal fun createActionToolbar(owner: JComponent): ActionToolbar {
        val group = object : DefaultActionGroup() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        group.add(newChangeAction())
        group.add(
            stableAction(
                JujutsuBundle.message("log.action.split"),
                JujutsuBundle.message("log.action.split.tooltip"),
                JujutsuIcons.Split,
                isEnabled = { currentWorkingCopy() != null },
                delegate = { splitAction(project, currentWorkingCopy()) }
            )
        )
        group.add(
            stableAction(
                JujutsuBundle.message("log.action.squash.into.parent"),
                JujutsuBundle.message("log.action.squash.into.parent.tooltip"),
                JujutsuIcons.Squash,
                isEnabled = { squashableEntry(currentWorkingCopy()) != null },
                delegate = { squashAction(project, squashableEntry(currentWorkingCopy())) }
            )
        )
        group.add(
            stableAction(
                JujutsuBundle.message("log.action.abandon"),
                JujutsuBundle.message("log.action.abandon.tooltip"),
                AllIcons.General.Delete,
                isEnabled = { currentWorkingCopy() != null },
                delegate = { abandonChangeAction(project, currentWorkingCopy()) }
            )
        )
        group.addSeparator()
        group.add(
            stableAction(
                JujutsuBundle.message("action.bookmark.create"),
                JujutsuBundle.message("action.bookmark.create.tooltip"),
                JujutsuIcons.BookmarkAdd,
                isEnabled = { currentWorkingCopy() != null },
                delegate = { createBookmarkAction(currentWorkingCopy()) }
            )
        )
        group.add(
            advanceClosestBookmarkAction(
                { boundRepository },
                { boundRepository?.let { project.stateModel.closestBookmarks.value[it] } },
                confirmSingle = true // icon-only button, easier misclick than a labelled menu item
            )
        )
        group.add(
            stableAction(
                JujutsuBundle.message("action.tag.set"),
                JujutsuBundle.message("action.tag.set.tooltip"),
                JujutsuIcons.TagAdd,
                isEnabled = { currentWorkingCopy() != null },
                delegate = { setTagAction(currentWorkingCopy()) }
            )
        )

        return ActionManager.getInstance()
            .createActionToolbar("JujutsuWorkingCopyToolbar", group, true).apply {
                targetComponent = owner
            }
    }

    /** New Change - primary workflow operation, first in the toolbar. */
    private fun newChangeAction(): AnAction = object : DumbAwareAction(
        JujutsuBundle.message("button.newchange"),
        JujutsuBundle.message("button.newchange.tooltip"),
        JujutsuIcons.NewChange
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            createNewChange()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = boundRepository != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /**
     * The working copy log entry for [boundRepository], or `null` if none is bound. Reads
     * [in.kkkev.jjidea.jj.JujutsuStateModel.workingCopies] directly rather than
     * [JujutsuRepository.workingCopy], which throws when there's no entry yet. `@` is never
     * immutable, so unlike the log context menu's versions of these actions, no immutable check.
     */
    private fun currentWorkingCopy(): LogEntry? =
        boundRepository?.let { project.stateModel.workingCopies.value[it.directory.path] }

    /**
     * A stable toolbar [AnAction] whose [isEnabled] is recomputed from live state on every
     * [update]; [delegate] is invoked only at click time, to run an existing by-value action
     * factory (`splitAction`/`squashAction`/etc.) without duplicating its logic.
     */
    private fun stableAction(
        text: String,
        description: String,
        icon: Icon,
        isEnabled: () -> Boolean,
        delegate: () -> AnAction
    ): AnAction = object : DumbAwareAction(text, description, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isEnabled()
        }

        // Must not call delegate().actionPerformed(e) directly: AnAction.actionPerformed is
        // @ApiStatus.OverrideOnly (the platform's own contract, not one we impose) — the platform
        // marketplace's compatibility checker flags direct calls as a violation even though our
        // own verifyPlugin (which excludes that category) does not. ActionManager.tryToExecute is
        // the long-standing public, non-deprecated way to invoke an AnAction programmatically.
        override fun actionPerformed(e: AnActionEvent) {
            ActionManager.getInstance().tryToExecute(delegate(), e.inputEvent, null, e.place, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun createDescribeButton(): JButton {
        describeButton = JButton(JujutsuBundle.message("button.describe")).apply {
            toolTipText = JujutsuBundle.message("button.describe.tooltip")
            icon = JujutsuIcons.Describe
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
    }

    /**
     * Update from model data (called when state model changes).
     */
    fun update(logEntry: LogEntry) {
        if (logEntry.repo != boundRepository) return

        runLater {
            if (logEntry.repo != boundRepository) return@runLater
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
        currentChangeLabel.text = htmlString {
            appendSummary(entry)
            appendParents(entry)
        }
    }

    /** Wrapper for displaying repository in dropdown */
    private data class RepoItem(val repo: JujutsuRepository) {
        val displayName: String get() = repo.displayName
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
