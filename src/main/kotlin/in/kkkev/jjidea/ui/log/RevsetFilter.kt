package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset

/**
 * The custom revset log filter (jj-idea-vqpn, GitHub #116): a per-tab revset chip that narrows
 * the log to commits matching a user-typed revset, ANDed with author/date/reference/root/search
 * exactly like the other filter chips (see [JujutsuLogTableModel.setRevsetFilter]).
 *
 * Deliberately client-side rather than a change to what's loaded: the repo-level revset from
 * [in.kkkev.jjidea.settings.JujutsuSettings.resolvedLogRevset] keeps governing what
 * [in.kkkev.jjidea.jj.LogCache]/[UnifiedJujutsuLogDataLoader] load for every tab and every
 * dialog/picker that reads [in.kkkev.jjidea.jj.JujutsuRepository.logCache] - this filter only
 * resolves [filter] to a [ChangeKey] set (one ids-only `jj log` call per repo via
 * [in.kkkev.jjidea.jj.LogService.getChangeIds]) and the table model filters the already-loaded
 * entries against it.
 */

/**
 * The revset to resolve against jj: [filter] ANDed with the repo-level revset [base] so the
 * filter can never surface a commit [base] itself excludes.
 *
 * [base] blank (jj's [Revset.Default] — "omit -r, let jj's own `revsets.log` config decide") has
 * no expression to AND with, so the query is just [filter] alone; the client-side intersection
 * with the already-loaded entries (themselves bounded by jj's own default) still limits what's
 * shown to what jj's default loaded.
 */
internal fun revsetFilterQuery(base: Revset, filter: String): Revset =
    if (base == Revset.Default) Expression("($filter)") else Expression("($base) & ($filter)")

/**
 * The outcome of resolving a revset filter across every repo in a log tab: the matched
 * [ChangeKey]s (repo-scoped, so the same change id in two repos doesn't cross over — mirrors
 * [JujutsuLogTableModel]'s existing `bookmarkFilter`), plus any per-repo failure message (jj's
 * own error text, e.g. an unparseable revset) keyed by the repo it came from.
 */
internal data class RevsetFilterResult(
    val keys: Set<ChangeKey>,
    val errors: Map<JujutsuRepository, String>
)

/**
 * Resolves [filter] against every repo in [repos]: one ids-only `jj log` call per repo
 * ([in.kkkev.jjidea.jj.LogService.getChangeIds] via [revsetFilterQuery]), O(#repos) processes -
 * see the class doc above. A failing repo is reported in [RevsetFilterResult.errors] and does not
 * block the others from resolving.
 *
 * @param baseFor the repo-level revset each repo's query is ANDed with — normally
 *   [in.kkkev.jjidea.settings.JujutsuSettings.resolvedLogRevset]; a parameter (rather than reading
 *   settings directly) so this stays pure and unit-testable without the platform.
 */
internal fun resolveRevsetFilter(
    repos: Collection<JujutsuRepository>,
    filter: String,
    baseFor: (JujutsuRepository) -> Revset
): RevsetFilterResult {
    val keys = mutableSetOf<ChangeKey>()
    val errors = mutableMapOf<JujutsuRepository, String>()
    for (repo in repos) {
        val revset = revsetFilterQuery(baseFor(repo), filter)
        repo.logService.getChangeIds(revset).fold(
            onSuccess = { ids -> ids.forEach { keys.add(ChangeKey(repo, it)) } },
            onFailure = { errors[repo] = it.message ?: it.toString() }
        )
    }
    return RevsetFilterResult(keys, errors)
}
