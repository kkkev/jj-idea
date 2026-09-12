package `in`.kkkev.jjidea.jj.conflict

interface ConflictExtractor {
    fun extract(fileContent: ByteArray): ExtractedConflict?
}
