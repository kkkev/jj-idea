package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.LogEntry

fun createBookmarkAction(logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "action.bookmark.create", AllIcons.General.Add) {
        CreateBookmarkDialog(target).show()
    }

class CreateBookmarkDialog(private val target: LogEntry) : BookmarkNameDialog(target.repo, "create") {
    override fun onSuccess() {
        log.info("Created bookmark $bookmark")
    }

    override fun execute(executor: CommandExecutor) = executor.bookmarkCreate(bookmark, target.id)
}
