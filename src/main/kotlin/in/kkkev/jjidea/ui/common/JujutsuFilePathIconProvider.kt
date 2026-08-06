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
    // The platform's getIcon(FilePath, Project) overload was abstract before 2025.2 and only
    // became a default method (calling into the 3-arg overload) afterward — on the 2025.1 floor,
    // overriding only the 3-arg method leaves this one unimplemented, causing AbstractMethodError
    // when the platform calls it. Override both.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getIcon(filePath: FilePath, project: Project?) = getIcon(filePath, filePath.isDirectory, project)

    override fun getIcon(filePath: FilePath, isDirectory: Boolean, project: Project?) = project
        ?.possibleJujutsuRepositoryFor(filePath)
        ?.takeIf { it.directory.filePath == filePath }
        ?.let { repo -> RepositoryIcons[repo] }
}
