package `in`.kkkev.jjidea.jj.cli

/**
 * How a jj command relates to `jj op revert --what repo`. Stated at construction so a new
 * command cannot be added without deciding - [CliExecutor]'s private `execute` takes only a
 * [JjInvocation], so there is no way to run a command without one.
 *
 * Verified empirically against jj 0.37/0.39/0.44 (see docs/design/undo-support-roadmap.md):
 * `git push`/`fetch`/`clone` and `bookmark track`/`untrack` all either report
 * "Nothing changed." or desync local/remote-tracking state when reverted with `--what repo` -
 * despite `bookmark forget` and `bookmark delete` (which also touch bookmark state) reverting
 * correctly, so this is a per-command classification, not a per-subcommand-family guess.
 */
enum class Reversibility {
    /** Whole effect lives in `repo` scope; `op revert --what repo` fully inverts it. */
    REVERSIBLE,

    /**
     * Effect reaches beyond the `repo` scope that `op revert --what repo` inverts - a git-remote
     * operation, or a command that only touches remote-tracking bookmark state.
     */
    IRREVERSIBLE,

    /** Writes no operation of its own (it may still trigger a working-copy snapshot). */
    READ_ONLY
}

/** One jj CLI invocation: the argv, plus whether it can be undone via `jj op revert`. */
data class JjInvocation(val reversibility: Reversibility, val args: List<String>) {
    constructor(reversibility: Reversibility, vararg args: Any) : this(reversibility, args.map(Any::toString))
}
