package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.DataKey
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
}
