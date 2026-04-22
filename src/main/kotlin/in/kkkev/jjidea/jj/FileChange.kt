package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.vcs.changeId
import `in`.kkkev.jjidea.vcs.filePath

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
    val before: FileAtVersion?
    val after: FileAtVersion?

    data class Modified(override val before: FileAtVersion, override val after: FileAtVersion) : FileChange {
        override val status = Status.MODIFIED

        val filePath = after.filePath

        init {
            require(before.filePath == after.filePath) {
                "MODIFIED change must not change file path (before=${before.filePath}, after=${after.filePath})"
            }
        }
    }

    data class Added(override val after: FileAtVersion) : FileChange {
        override val status = Status.ADDED
        override val before = null
    }

    data class Deleted(
        override val before: FileAtVersion
    ) : FileChange {
        override val status = Status.DELETED
        override val after = null
    }

    data class Renamed(override val before: FileAtVersion, override val after: FileAtVersion) : FileChange {
        override val status = Status.RENAMED

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
            return when {
                after == null -> Deleted(
                    change.filePath.fileAt(requireNotNull(before) { "Change has no before or after revision" }.changeId)
                )

                before == null -> Added(after.fileAtVersion)
                before.file == after.file -> Modified(before.fileAtVersion, after.fileAtVersion)
                else -> Renamed(before.fileAtVersion, after.fileAtVersion)
            }
        }
    }
}
