package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Data keys for Jujutsu-specific action context.
 */
object JujutsuDataKeys {
    /**
     * The log entry for the current context.
     * Present in custom log details panel to distinguish working copy vs historical context.
     * Actions can check `logEntry?.isWorkingCopy` to determine behavior.
     */
    @JvmField
    val LOG_ENTRY: DataKey<LogEntry> = DataKey.create("Jujutsu.LogEntry")

    /**
     * VirtualFile user-data: log entry pinned to a historical version opened by OpenRepositoryVersionAction.
     * Use [Project.possibleLogEntryFor] as the single access point rather than reading this key directly.
     */
    @JvmField
    val VIRTUAL_FILE_LOG_ENTRY: Key<LogEntry> = Key.create("Jujutsu.VirtualFileLogEntry")

    /**
     * DiffContent user-data: Jujutsu context for one side of a diff viewer.
     *
     * Set on [com.intellij.diff.contents.DiffContent] objects created by diff actions (e.g.
     * [in.kkkev.jjidea.actions.filechange.CompareWithLocalAction]).
     * [in.kkkev.jjidea.actions.file.OpenInRemoteFromEditorGroup] reads this from
     * [com.intellij.diff.tools.util.DiffDataKeys.CURRENT_CONTENT]
     * when [com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE] is not a real project file
     * (i.e. for the historical/text-based side of a diff).
     *
     * [commitId] is null for local (working-copy) sides — in that case the action falls back to the
     * latest pushed ancestor, matching the regular editor behaviour.
     */
    @JvmField
    val DIFF_CONTENT_INFO: Key<DiffContentInfo> = Key.create("Jujutsu.DiffContentInfo")

    data class DiffContentInfo(val repo: JujutsuRepository, val filePath: FilePath, val commitId: CommitId?)
}
