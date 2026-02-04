package `in`.kkkev.jjidea.jj

/**
 * Identifies a specific change in a repository.
 *
 * Used to communicate between actions and UI components about which change to select,
 * display, or operate on. Supports multiple revision types:
 * - [ChangeId] or [CommitId] for specific commits
 * - [WorkingCopy] for the current working copy (`@`)
 * - [Bookmark] or [Tag] for named references
 *
 * @param repo The repository containing the change
 * @param revision The revision to identify, or null for no specific selection
 */
data class ChangeKey(val repo: JujutsuRepository, val revision: Revision)
