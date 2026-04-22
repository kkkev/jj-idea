package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vcs.vfs.AbstractVcsVirtualFile
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * A virtual file backed by a Jujutsu repository. Used in situations that need a [com.intellij.openapi.vfs.VirtualFile]
 * with repository contents, e.g. when opening an editor.
 */
class JujutsuVirtualFile(private val fileAtVersion: FileAtVersion, private val repo: JujutsuRepository) :
    AbstractVcsVirtualFile(fileAtVersion.filePath) {
    val contents by lazy { repo.createContentRevision(fileAtVersion).content?.toByteArray() ?: byteArrayOf() }

    init {
        putUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY, repo.getLogEntry(contentLocator))
    }

    override fun contentsToByteArray() = contents

    override fun isDirectory() = false
    override fun getPresentableName() = fileAtVersion.title

    val contentLocator get() = fileAtVersion.contentLocator
}
