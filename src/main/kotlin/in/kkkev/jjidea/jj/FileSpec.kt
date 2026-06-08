package `in`.kkkev.jjidea.jj

private val RENAME_SPEC_REGEX = Regex("""([^{)]*)\{([^}]*) => ([^}]*)}(.*)""")

fun parseRenameSpec(spec: String): Pair<String, String> {
    val (prefix, before, after, suffix) = requireNotNull(
        RENAME_SPEC_REGEX.find(spec)
    ) { "Invalid rename/copy format: $spec" }.destructured
    require(before.isNotEmpty() || after.isNotEmpty()) {
        "Invalid rename/copy format (both sides cannot be empty): $spec"
    }
    return joinPathParts(prefix, before, suffix) to joinPathParts(prefix, after, suffix)
}

// When mid is empty, suffix starts with a separator that would create a double separator (e.g. "foo//bar").
// Drop the leading separator from suffix and let prefix supply it.
private fun joinPathParts(prefix: String, mid: String, suffix: String) =
    if (mid.isNotEmpty()) {
        prefix + mid + suffix
    } else {
        prefix + suffix.trimStart('/', '\\')
    }
