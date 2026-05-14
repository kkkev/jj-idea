package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.locator

/**
 * Object representing a file change, containing information about file name/versions before/after and nature of the
 * change.
 */
sealed interface FileChange {
    enum class Status {
        MODIFIED,
        ADDED,
        DELETED,
        RENAMED,
        UNKNOWN
    }

    val status: Status
    val isConflicted: Boolean get() = false
    val before: FileAtVersion?
    val after: FileAtVersion?

    /**
     * "The" file path of the change. If there is an after file, then this is the path of that. If not, it is the file
     * path of the before file. This is therefore the file path to display in the diff window title.
     *
     * Note that for renames, this is the target file name (not the source).
     */
    val filePath: FilePath

    data class Modified(
        override val before: FileAtVersion,
        override val after: FileAtVersion,
        override val isConflicted: Boolean = false
    ) : FileChange {
        override val status = Status.MODIFIED

        override val filePath = after.filePath

        init {
            require(before.filePath == after.filePath) {
                "MODIFIED change must not change file path (before=${before.filePath}, after=${after.filePath})"
            }
        }
    }

    data class Added(override val after: FileAtVersion) : FileChange {
        override val status = Status.ADDED
        override val before = null
        override val filePath = after.filePath
    }

    data class Deleted(
        override val before: FileAtVersion
    ) : FileChange {
        override val status = Status.DELETED
        override val after = null
        override val filePath = before.filePath
    }

    data class Renamed(override val before: FileAtVersion, override val after: FileAtVersion) : FileChange {
        override val status = Status.RENAMED
        override val filePath = after.filePath

        init {
            require(before.filePath != after.filePath) {
                "RENAMED change must change file path (both=${before.filePath})"
            }
        }
    }

    companion object {
        fun from(change: Change): FileChange {
            val before = change.beforeRevision
            val after = change.afterRevision
            val conflicted = change.fileStatus == FileStatus.MERGED_WITH_CONFLICTS
            return when {
                after == null -> Deleted(
                    change.filePath.fileAt(requireNotNull(before) { "Change has no before or after revision" }.locator)
                )
                before == null -> Added(after.fileAtVersion)
                before.file == after.file -> Modified(before.fileAtVersion, after.fileAtVersion, conflicted)
                else -> Renamed(before.fileAtVersion, after.fileAtVersion)
            }
        }
    }
}
