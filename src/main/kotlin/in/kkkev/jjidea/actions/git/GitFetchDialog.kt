package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selected
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.GitFetchDialog.Companion.loadAllDialogData
import `in`.kkkev.jjidea.actions.git.GitFetchDialog.Companion.loadDialogData
import `in`.kkkev.jjidea.jj.Displayable
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Dialog for configuring a `jj git fetch` operation.
 *
 * Options:
 * - Repository selector (only shown when multiple repos are provided; includes "All repositories" sentinel)
 * - Remote scope: specific remote (pre-selected to first remote) or all remotes
 *
 * The dialog is only shown when there is a real choice to make (multiple repos or multiple remotes).
 * **Important**: Remotes must be loaded off EDT before constructing this dialog.
 * Use [loadDialogData] to load data on a background thread, or [loadAllDialogData] for multiple repos.
 */
class GitFetchDialog(project: Project, private val allData: Map<JujutsuRepository, FetchDialogData>) :
    DialogWrapper(project) {
    data class GitFetchSpec(
        val repos: List<JujutsuRepository>,
        val remote: Remote?,
        val allRemotes: Boolean
    )

    data class FetchDialogData(val remotes: List<Remote>)

    var result: GitFetchSpec? = null
        private set

    private object AllRepos : Displayable {
        override val displayName = JujutsuBundle.message("dialog.git.fetch.repository.all")
    }

    // selectedRepoOrAll: either a JujutsuRepository or AllRepos sentinel
    private var selectedRepoOrAll: Any = AllRepos
    private var fetchScope = FetchScope.SPECIFIC
    private var selectedRemote: Remote? = currentRemotes().firstOrNull()
    private val remoteModel = DefaultComboBoxModel(currentRemotes().toTypedArray())

    // Exposed for tests (see SplitDialog for the same pattern) so a test can drive the real
    // addActionListener callback below rather than reimplementing it.
    internal var repoComboBox: JComboBox<*>? = null
        private set

    private fun currentRepos(): List<JujutsuRepository> =
        if (selectedRepoOrAll is AllRepos) {
            allData.keys.toList()
        } else {
            listOf(selectedRepoOrAll as JujutsuRepository)
        }

    private fun currentRemotes(): List<Remote> =
        if (selectedRepoOrAll is AllRepos) {
            allData.values.flatMap { it.remotes }.map { it.name }.distinct().map { Remote(it) }
        } else {
            allData[selectedRepoOrAll as JujutsuRepository]?.remotes ?: emptyList()
        }

    private val showAllRemotesOption get() = allData.values.any { it.remotes.size > 1 }

    private enum class FetchScope { SPECIFIC, ALL }

    init {
        title = JujutsuBundle.message("dialog.git.fetch.title")
        setOKButtonText(JujutsuBundle.message("dialog.git.fetch.button"))
        init()
    }

    private fun updateForRepoChange() {
        val remotes = currentRemotes()
        val previousName = selectedRemote?.name
        selectedRemote = remotes.firstOrNull { it.name == previousName } ?: remotes.firstOrNull()
        remoteModel.replaceContents(remotes, selectedRemote)
    }

    override fun createCenterPanel(): JComponent = panel {
        if (allData.size > 1) {
            val items: List<Displayable> = listOf(AllRepos) + allData.keys.toList()
            row(JujutsuBundle.message("dialog.git.fetch.repository.label")) {
                comboBox(items)
                    .applyToComponent {
                        renderer = textListCellRenderer("", Displayable::displayName)
                        selectedItem = AllRepos
                        addActionListener {
                            selectedRepoOrAll = selectedItem ?: AllRepos
                            updateForRepoChange()
                        }
                        repoComboBox = this
                    }
            }
        }

        if (showAllRemotesOption) {
            buttonsGroup {
                row {
                    val rb = radioButton(JujutsuBundle.message("dialog.git.fetch.scope.specific"))
                        .bindScope(::fetchScope, FetchScope.SPECIFIC)
                    comboBox(remoteModel)
                        // Deliberately not `.toNullableProperty()`: its `!!` NPEs whenever the
                        // combo's selection is transiently null, e.g. mid-repopulate (jj-idea-idm0).
                        // `bindItem(KMutableProperty0<T?>)` handles a null selection safely.
                        .bindItem(::selectedRemote)
                        .enabledIf(rb.component.selected)
                }
                row {
                    radioButton(JujutsuBundle.message("dialog.git.fetch.scope.all"))
                        .bindScope(::fetchScope, FetchScope.ALL)
                }
            }
        } else {
            row(JujutsuBundle.message("dialog.git.fetch.remote.label")) {
                comboBox(remoteModel)
                    .bindItem(::selectedRemote)
            }
        }
    }

    override fun doOKAction() {
        applyFields()
        result = GitFetchSpec(
            repos = currentRepos(),
            remote = selectedRemote.takeIf { fetchScope == FetchScope.SPECIFIC },
            allRemotes = fetchScope == FetchScope.ALL
        )
        super.doOKAction()
    }

    companion object {
        fun loadDialogData(repo: JujutsuRepository): FetchDialogData =
            FetchDialogData(GitPushDialog.loadRemotes(repo))

        fun loadAllDialogData(repos: Collection<JujutsuRepository>): Map<JujutsuRepository, FetchDialogData> =
            repos.associateWith { loadDialogData(it) }
    }
}
