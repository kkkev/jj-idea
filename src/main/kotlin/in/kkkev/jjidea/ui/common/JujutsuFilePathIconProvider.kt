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
    override fun getIcon(filePath: FilePath, isDirectory: Boolean, project: Project?) = project
        ?.possibleJujutsuRepositoryFor(filePath)
        ?.takeIf { it.directory.filePath == filePath }
        ?.let { repo -> RepositoryIcons[repo] }
}

