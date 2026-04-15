package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.util.Key
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
}
