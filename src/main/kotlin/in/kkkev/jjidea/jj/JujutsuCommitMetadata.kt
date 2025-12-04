package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vfs.VirtualFile

/**
 * Metadata for a Jujutsu commit.
 * All methods are inherited from JujutsuCommitMetadataBase.
 */
class JujutsuCommitMetadata(
    entry: JujutsuLogEntry,
    root: VirtualFile
) : JujutsuCommitMetadataBase(entry, root)
