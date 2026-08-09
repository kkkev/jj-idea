package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.FilePathIconProvider
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Icon provider to use coloured repository icons to display folders in change trees that represent Jujutsu repository
 * roots.
 */
class JujutsuFilePathIconProvider : FilePathIconProvider {
    // On the 2025.1 floor this 2-arg overload is the interface's only (abstract) method; the 3-arg
    // getIcon(FilePath, Boolean, Project?) doesn't exist until 2025.2, where it is a default that
    // delegates here. Overriding only this one therefore compiles and behaves correctly across the
    // whole supported range. The platform marks it @Deprecated(forRemoval = true) in favour of the
    // 3-arg form; switch over once sinceBuild rises past 251. The isDirectory argument is
    // irrelevant here — this provider only matches a repository root by exact FilePath equality.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getIcon(filePath: FilePath, project: Project?) = project
        ?.possibleJujutsuRepositoryFor(filePath)
        ?.takeIf { it.directory.filePath == filePath }
        ?.let { repo -> RepositoryIcons[repo] }
}
