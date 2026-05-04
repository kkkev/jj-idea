package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath

data class JujutsuConflict(val repo: JujutsuRepository, val filePath: FilePath)
