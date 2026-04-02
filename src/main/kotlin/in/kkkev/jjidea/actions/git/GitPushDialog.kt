package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.GitPushDialog.Companion.loadDialogData
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.ui.components.TextCanvas
import `in`.kkkev.jjidea.ui.components.TextListCellRenderer
import `in`.kkkev.jjidea.ui.components.append
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * Dialog for configuring a `jj git push` operation.
 *
 * Options:
 * - Remote selector (populated from pre-loaded remote list)
 * - Push scope: default tracking bookmarks, specific bookmark, or all bookmarks
 * - Bookmark selector (when "specific bookmark" is selected, filtered by tracked bookmarks for the selected remote)
 *
 * **Important**: Remotes and bookmarks must be loaded off EDT before constructing this dialog.
 * Use [loadDialogData] to load data on a background thread.
 */
class GitPushDialog(project: Project, private val data: DialogData) : DialogWrapper(project) {
    /**
     * Result of the push dialog — the user's chosen parameters.
     */
    data class GitPushSpec(val remote: Remote?, val bookmark: Bookmark?, val allBookmarks: Boolean)

    /**
     * Pre-loaded dialog data: remotes and tracked bookmarks per remote.
     */
    data class DialogData(
        val remotes: List<Remote>,
        val trackedByRemote: Map<Remote, List<Bookmark>>,
        val allLocal: List<Bookmark>
    )

    var result: GitPushSpec? = null
        private set

    private var selectedRemote = data.remotes.firstOrNull()
    private var pushScope = PushScope.DEFAULT
    private var selectedBookmark = currentBookmarks().firstOrNull()
    private val bookmarkModel = DefaultComboBoxModel(currentBookmarks().toTypedArray())

    private fun currentBookmarks(): List<Bookmark> {
        val tracked = data.trackedByRemote[selectedRemote] ?: emptyList()
        val untracked = data.allLocal.filter { it !in tracked }
        return tracked + untracked
    }

    private enum class PushScope { DEFAULT, BOOKMARK, ALL }

    private class BookmarkRenderer : TextListCellRenderer<Bookmark>() {
        override fun render(canvas: TextCanvas, value: Bookmark) {
            canvas.append(value)
            if (!value.tracked) {
                canvas.grey {
                    canvas.italic { append(" (new)") }
                }
            }
        }
    }

    init {
        title = JujutsuBundle.message("dialog.git.push.title")
        setOKButtonText(JujutsuBundle.message("dialog.git.push.button"))
        init()
    }

    private fun updateBookmarks() {
        val bookmarks = currentBookmarks()
        bookmarkModel.removeAllElements()
        bookmarkModel.addAll(bookmarks)
        selectedBookmark = bookmarks.firstOrNull()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (data.remotes.size > 1) {
            row(JujutsuBundle.message("dialog.git.push.remote.label")) {
                comboBox(data.remotes)
                    .bindItem(::selectedRemote.toNullableProperty())
                    .applyToComponent {
                        addActionListener {
                            selectedRemote = selectedItem as? Remote
                            updateBookmarks()
                        }
                    }
            }
        }

        buttonsGroup {
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.default"), PushScope.DEFAULT)
            }
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.bookmark"), PushScope.BOOKMARK)
                cell(ComboBox(bookmarkModel).apply { renderer = BookmarkRenderer() })
                    .bindItem(::selectedBookmark.toNullableProperty())
            }
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.all"), PushScope.ALL)
            }
        }.bind(::pushScope)
    }

    override fun doOKAction() {
        applyFields()
        result = GitPushSpec(
            remote = selectedRemote,
            bookmark = selectedBookmark.takeIf { pushScope == PushScope.BOOKMARK },
            allBookmarks = pushScope == PushScope.ALL
        )
        super.doOKAction()
    }

    companion object {
        private val LOCAL_BOOKMARK_TEMPLATE =
            """if(present, if(remote, "", name ++ "\n"))"""

        /**
         * Load dialog data (remotes and tracked bookmarks per remote) from a repository. Call off EDT.
         */
        fun loadDialogData(repo: JujutsuRepository): DialogData {
            val remotes = repo.commandExecutor.gitRemoteList().let { result ->
                if (result.isSuccess) {
                    result.stdout.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.substringBefore(' ') }
                        .map { Remote(it) }
                } else {
                    emptyList()
                }
            }
            val trackedByRemote = remotes.associateWith { remote ->
                repo.commandExecutor.bookmarkList(LOCAL_BOOKMARK_TEMPLATE, remote, true).let { result ->
                    if (result.isSuccess) {
                        result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Bookmark(it, true) }
                    } else {
                        emptyList()
                    }
                }
            }
            val allLocal = repo.commandExecutor.bookmarkList(LOCAL_BOOKMARK_TEMPLATE).let { result ->
                if (result.isSuccess) {
                    result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Bookmark(it, false) }
                } else {
                    emptyList()
                }
            }
            return DialogData(remotes, trackedByRemote, allLocal)
        }
    }
}
