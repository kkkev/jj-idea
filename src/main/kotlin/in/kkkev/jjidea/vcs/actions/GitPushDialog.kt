package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.*
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import javax.swing.JComponent

/**
 * Result of the push dialog — the user's chosen parameters.
 */
data class GitPushSpec(
    val remote: String?,
    val bookmark: String?,
    val allBookmarks: Boolean
)

/**
 * Dialog for configuring a `jj git push` operation.
 *
 * Options:
 * - Remote selector (populated from pre-loaded remote list)
 * - Push scope: default tracking bookmarks, specific bookmark, or all bookmarks
 * - Bookmark selector (when "specific bookmark" is selected)
 *
 * **Important**: Remotes and bookmarks must be loaded off EDT before constructing this dialog.
 * Use [loadDialogData] to load data on a background thread.
 */
class GitPushDialog(
    project: Project,
    private val remotes: List<String>,
    private val bookmarks: List<String>
) : DialogWrapper(project) {
    var result: GitPushSpec? = null
        private set

    private var selectedRemote: String = remotes.firstOrNull() ?: ""
    private var pushScope = PushScope.DEFAULT
    private var selectedBookmark: String = bookmarks.firstOrNull() ?: ""

    private enum class PushScope { DEFAULT, BOOKMARK, ALL }

    init {
        title = JujutsuBundle.message("dialog.git.push.title")
        setOKButtonText(JujutsuBundle.message("dialog.git.push.button"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (remotes.size > 1) {
            row(JujutsuBundle.message("dialog.git.push.remote.label")) {
                comboBox(remotes).bindItem(::selectedRemote.toNullableProperty())
            }
        }

        buttonsGroup {
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.default"), PushScope.DEFAULT)
            }
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.bookmark"), PushScope.BOOKMARK)
                comboBox(bookmarks).bindItem(::selectedBookmark.toNullableProperty())
            }
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.all"), PushScope.ALL)
            }
        }.bind(::pushScope)
    }

    override fun doOKAction() {
        result = GitPushSpec(
            remote = selectedRemote.takeIf { remotes.size > 1 },
            bookmark = selectedBookmark.takeIf { pushScope == PushScope.BOOKMARK },
            allBookmarks = pushScope == PushScope.ALL
        )
        super.doOKAction()
    }

    companion object {
        /**
         * Load dialog data (remotes and bookmarks) from a repository. Call off EDT.
         */
        fun loadDialogData(repo: JujutsuRepository): Pair<List<String>, List<String>> {
            val remotes = repo.commandExecutor.gitRemoteList().let { result ->
                if (result.isSuccess) {
                    result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
            }
            val bookmarks = repo.commandExecutor.bookmarkList().let { result ->
                if (result.isSuccess) {
                    result.stdout.lines()
                        .map { it.trim().substringBefore(':').substringBefore(' ') }
                        .filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
            }
            return remotes to bookmarks
        }
    }
}
