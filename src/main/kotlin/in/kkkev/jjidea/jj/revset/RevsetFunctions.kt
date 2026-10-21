package `in`.kkkev.jjidea.jj.revset

/**
 * A builtin revset function offered by completion. [signature] is shown as tail text, [doc] is a
 * one-liner, and [takesArgs] decides whether the insert handler leaves the caret inside the parens.
 * [sample] is a valid call used by the contract test to check the name against the real jj.
 */
data class RevsetFunction(
    val name: String,
    val signature: String,
    val doc: String,
    val takesArgs: Boolean,
    val sample: String
)

private fun fn(name: String, args: String, doc: String, sample: String = "$name(${if (args.isEmpty()) "" else "@"})") =
    RevsetFunction(name, "($args)", doc, args.isNotEmpty(), sample)

private fun pat(name: String, doc: String, sample: String = "\"a\"") = fn(name, "pattern", doc, "$name($sample)")

/** Builtins of jj 0.44 (`jj help -k revsets`). jj is the validator; this list only drives completion. */
val REVSET_FUNCTIONS = listOf(
    fn("all", "", "All visible commits and ancestors of commits explicitly mentioned"),
    fn("none", "", "No commits"),
    fn("root", "", "The virtual root commit"),
    fn("visible_heads", "", "All visible heads"),
    fn("working_copies", "", "The working-copy commits of all workspaces"),
    fn("trunk", "", "The head of the default bookmark of the default remote"),
    fn("mutable", "", "Commits jj treats as mutable"),
    fn("immutable", "", "Commits jj treats as immutable"),
    fn("immutable_heads", "", "The heads of the immutable set"),
    fn("visible", "", "The visible commits"),
    fn("hidden", "", "The hidden commits"),
    fn("merges", "", "Merge commits"),
    fn("forks", "", "Commits with more than one child"),
    fn("mine", "", "Commits authored by the current user"),
    fn("empty", "", "Commits modifying no files"),
    fn("signed", "", "Cryptographically signed commits"),
    fn("conflicts", "", "Commits with conflicted files"),
    fn("divergent", "", "Divergent commits"),
    fn("parents", "x, [depth]", "Parents of x (same as x-)", "parents(@)"),
    fn("children", "x, [depth]", "Children of x (same as x+)", "children(@)"),
    fn("ancestors", "x, [depth]", "Ancestors of x (same as ::x)", "ancestors(@, 2)"),
    fn("descendants", "x, [depth]", "Descendants of x (same as x::)", "descendants(@)"),
    fn("first_parent", "x, [depth]", "The first parent of x", "first_parent(@)"),
    fn("first_ancestors", "x, [depth]", "Ancestors of x following only first parents", "first_ancestors(@)"),
    fn("reachable", "srcs, domain", "Commits reachable from srcs within domain", "reachable(@, all())"),
    fn("connected", "x", "Same as x::x", "connected(@)"),
    fn("heads", "x", "Commits in x that are not ancestors of others in x", "heads(all())"),
    fn("roots", "x", "Commits in x that are not descendants of others in x", "roots(all())"),
    fn("latest", "x, [count]", "The latest commits in x", "latest(all(), 2)"),
    fn("fork_point", "x", "The fork point of all commits in x", "fork_point(@)"),
    fn("merge_point", "x", "The merge point of all commits in x", "merge_point(@)"),
    fn("bisect", "x", "About half of x, for bisecting", "bisect(all())"),
    fn("exactly", "x, count", "x, erroring unless it has exactly count commits", "exactly(@, 1)"),
    fn("change_id", "prefix", "Commits with the given change id prefix", "change_id(z)"),
    fn("commit_id", "prefix", "Commits with the given commit id prefix", "commit_id(0)"),
    fn("bookmarks", "[pattern]", "Local bookmark targets", "bookmarks()"),
    fn("remote_bookmarks", "[name], [remote]", "Remote bookmark targets", "remote_bookmarks()"),
    fn("tracked_remote_bookmarks", "[name], [remote]", "Tracked remote bookmark targets", "tracked_remote_bookmarks()"),
    fn(
        "untracked_remote_bookmarks",
        "[name], [remote]",
        "Untracked remote bookmark targets",
        "untracked_remote_bookmarks()"
    ),
    fn("tags", "[pattern]", "Tag targets", "tags()"),
    fn("remote_tags", "[name], [remote]", "Remote tag targets", "remote_tags()"),
    pat("description", "Commits whose description matches"),
    pat("subject", "Commits whose subject matches"),
    pat("author", "Commits whose author name or email matches"),
    pat("author_name", "Commits whose author name matches"),
    pat("author_email", "Commits whose author email matches"),
    pat("author_date", "Commits whose author date matches", "after:\"2020-01-01\""),
    pat("committer", "Commits whose committer name or email matches"),
    pat("committer_name", "Commits whose committer name matches"),
    pat("committer_email", "Commits whose committer email matches"),
    pat("committer_date", "Commits whose committer date matches", "after:\"2020-01-01\""),
    fn("files", "fileset", "Commits modifying paths matching the fileset", "files(\"a\")"),
    fn("diff_lines", "text, [files]", "Commits whose diff contains matching lines", "diff_lines(\"a\")"),
    fn("diff_lines_added", "text, [files]", "Commits whose diff adds matching lines", "diff_lines_added(\"a\")"),
    fn("diff_lines_removed", "text, [files]", "Commits whose diff removes matching lines", "diff_lines_removed(\"a\")"),
    fn("present", "x", "x, or none() if it names a missing commit", "present(@)"),
    fn("coalesce", "revsets...", "The first non-empty revset", "coalesce(@, all())"),
    fn("at_operation", "op, x", "x evaluated at an operation", "at_operation(@, @)")
)
