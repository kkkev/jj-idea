package `in`.kkkev.jjidea.jj

/**
 * A change identified within its repository - the plugin's repo-scoped commit identity.
 *
 * Deliberately narrower than [ChangeKey]: [ChangeKey.revision] is a loose *selection/navigation*
 * key that can also be a [WorkingCopy] or a bookmark/tag [Ref] - see [JujutsuRepository]'s
 * `invalidate(select: Revision?)` extension, which documents `invalidate(WorkingCopy)` and
 * `invalidate(bookmark)` alongside `invalidate(changeId)` - while this interface is always exactly
 * one commit. Anything that only ever needs "which change, in which repo" - a drop target's
 * identity, a graph node, a bookmark leaf - should implement this rather than reaching for a full
 * [LogEntry] or inventing its own `repo`/`id` pair.
 *
 * Named to avoid colliding with [Ref]/[RefItem], which already mean bookmark/tag references here.
 */
interface ChangeIdentity {
    val repo: JujutsuRepository
    val id: ChangeId

    /** This identity as a [ChangeKey], for the selection/navigation and lookup APIs that key on one. */
    val key: ChangeKey get() = ChangeKey(repo, id)
}
