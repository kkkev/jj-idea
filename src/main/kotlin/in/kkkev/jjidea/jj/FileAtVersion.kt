package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.vcs.locator

/**
 * Object representing a file at a particular version.
 */
data class FileAtVersion(val filePath: FilePath, val contentLocator: ContentLocator) {
    val name get() = filePath.name
    val isWorkingCopy get() = contentLocator is WorkingCopy
    val title get() = "$name (${contentLocator.title})"
}

val FilePath.fileAtWorkingCopy get() = FileAtVersion(this, WorkingCopy)
fun FilePath.fileAt(contentLocator: ContentLocator) = FileAtVersion(this, contentLocator)
val ContentRevision.fileAtVersion get() = FileAtVersion(file, locator)
