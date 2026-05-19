package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.selected
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import javax.swing.DefaultComboBoxModel
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
class GitFetchDialog(
    project: Project,
    private val allData: Map<JujutsuRepository, FetchDialogData>,
    initialRepo: JujutsuRepository
) : DialogWrapper(project) {
    data class GitFetchSpec(
        val repos: List<JujutsuRepository>,
        val remote: Remote?,
        val allRemotes: Boolean
    )

    data class FetchDialogData(val remotes: List<Remote>)

    var result: GitFetchSpec? = null
        private set

    private object AllRepos

    // selectedRepoOrAll: either a JujutsuRepository or AllRepos sentinel
    private var selectedRepoOrAll: Any = AllRepos
    private var fetchScope = FetchScope.SPECIFIC
    private var selectedRemote: Remote? = currentRemotes().firstOrNull()
    private val remoteModel = DefaultComboBoxModel(currentRemotes().toTypedArray())

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
        remoteModel.removeAllElements()
        remoteModel.addAll(remotes)
        selectedRemote = remotes.firstOrNull { it.name == previousName } ?: remotes.firstOrNull()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (allData.size > 1) {
            val items: List<Any> = listOf(AllRepos) + allData.keys.toList()
            row(JujutsuBundle.message("dialog.git.fetch.repository.label")) {
                comboBox(items)
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create<Any>("") { item ->
                            if (item is AllRepos) {
                                JujutsuBundle.message("dialog.git.fetch.repository.all")
                            } else {
                                (item as JujutsuRepository).displayName
                            }
                        }
                        selectedItem = AllRepos
                        addActionListener {
                            selectedRepoOrAll = selectedItem ?: AllRepos
                            updateForRepoChange()
                        }
                    }
            }
        }

        if (showAllRemotesOption) {
            buttonsGroup {
                row {
                    val rb = radioButton(JujutsuBundle.message("dialog.git.fetch.scope.specific"), FetchScope.SPECIFIC)
                    comboBox(remoteModel)
                        .bindItem(::selectedRemote.toNullableProperty())
                        .enabledIf(rb.component.selected)
                }
                row {
                    radioButton(JujutsuBundle.message("dialog.git.fetch.scope.all"), FetchScope.ALL)
                }
            }.bind(::fetchScope)
        } else {
            row(JujutsuBundle.message("dialog.git.fetch.remote.label")) {
                comboBox(remoteModel)
                    .bindItem(::selectedRemote.toNullableProperty())
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
