package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

interface ConflictExtractor {
    fun extract(fileContent: ByteArray): MergeData?
}
