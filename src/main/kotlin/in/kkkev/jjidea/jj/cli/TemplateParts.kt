package `in`.kkkev.jjidea.jj.cli

object TemplateParts {
    fun qualifiedChangeId(base: String? = null): String {
        fun fn(name: String) = fn(base, name)
        return "${fn("change_id")} ++ \"~\" ++ ${fn("change_id")}.shortest() ++ \"~\" ++ " +
            "if(${fn("divergent")}, ${fn("change_offset")}, \"\")"
    }

    /**
     * `change_id`, offset-qualified when divergent — matches the format of [in.kkkev.jjidea.jj.ChangeId.full]
     * (`"$fullChangeId/$offset"`), so ids emitted by this template can be compared directly against it.
     */
    fun changeIdWithOffset(base: String? = null): String {
        fun fn(name: String) = fn(base, name)
        return "${fn("change_id")} ++ if(${fn("divergent")}, \"/\" ++ ${fn("change_offset")}, \"\")"
    }

    fun commitId(base: String? = null): String {
        fun fn(name: String) = fn(base, name)
        return "${fn("commit_id")} ++ \"~\" ++ ${fn("commit_id")}.shortest()"
    }

    fun nameWithRemote(base: String? = null): String {
        fun fn(name: String) = fn(base, name)
        return "if(${fn("remote")}, ${fn("name")} ++ \"@\" ++ ${fn("remote")}, ${fn("name")})"
    }

    private fun fn(base: String?, name: String) = base?.let { "$it.$name()" } ?: name
}
