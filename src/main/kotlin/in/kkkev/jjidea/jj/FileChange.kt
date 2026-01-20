package `in`.kkkev.jjidea.jj

/**
 * DTO representing a file change in a commit.
 * Simple data class without dependencies on IntelliJ VCS framework.
 */
data class FileChange(val filePath: String, val status: FileChangeStatus)

enum class FileChangeStatus {
    MODIFIED,
    ADDED,
    DELETED,
    UNKNOWN
}
