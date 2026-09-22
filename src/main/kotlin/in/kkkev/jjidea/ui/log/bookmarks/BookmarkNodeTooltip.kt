package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.icons.AllIcons
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.TextCanvas
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendBookmarkChip
import `in`.kkkev.jjidea.ui.components.appendBracket
import `in`.kkkev.jjidea.ui.components.appendChangeTooltip
import `in`.kkkev.jjidea.ui.components.appendClosestBookmarkStatus
import `in`.kkkev.jjidea.ui.components.appendTagChip
import `in`.kkkev.jjidea.ui.components.htmlString
import `in`.kkkev.jjidea.ui.components.icon

/**
 * Hover-tooltip HTML for a bookmarks-panel row (jj-idea-uyu9, GitHub #110) — v2, rewritten after
 * user feedback on the first cut: that version restated what the row's own icon/arrows already
 * show, in beginner/tutorial prose ("Everything inside is tracked and in sync"), which a seasoned
 * user finds irritating on every hover. This version shows only what the row *doesn't* already:
 * the bookmark's full `/`-qualified path, which repo/group it belongs to, and (DRY with the log's
 * own row tooltip, via [in.kkkev.jjidea.ui.components.appendChangeTooltip]) the commit it points
 * to - change id, commit id, author, date, description. State the row's icon/arrows already
 * convey (tracked/conflict/deleted/ahead-behind) is not repeated as text.
 *
 * Every tooltip has the same shape: an optional repo line (multi-repo projects only), then a
 * `[GroupLabel, ...]`-bracketed identity line with an icon (mirrors
 * [in.kkkev.jjidea.ui.components.appendSummaryAndStatuses]'s `[Conflict, Immutable, ...]`
 * commit-status convention, extracted into the shared [in.kkkev.jjidea.ui.components.appendBracket]),
 * then node-specific content. Wired up in [JujutsuBookmarksPanel] via
 * [in.kkkev.jjidea.ui.components.installIconAwareTooltip] rather than a plain Swing tooltip - the
 * chips this leads with are `icon:`/`unbreakable:` `<img>` markup that only
 * [in.kkkev.jjidea.ui.components.IconAwareHtmlPane] resolves instead of painting as a broken
 * image (jj-idea-fmrj, jj-idea-2md7).
 *
 * [entryLookup] resolves a node's target change to a real [LogEntry] for the commit-info block -
 * the same resolver [JujutsuBookmarksPanel] already threads through for change actions. A miss
 * (the change is outside the currently loaded log window - a normal, expected outcome, not an
 * error) just omits that block rather than showing a placeholder. [isMultiRepo] gates the leading
 * repo line.
 */
internal fun bookmarkNodeTooltip(
    node: BookmarkNode,
    entryLookup: (ChangeKey) -> LogEntry?,
    isMultiRepo: Boolean
): String? = when (node) {
    is BookmarkNode.Local -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        appendGroupBracket(JujutsuBundle.message("bookmarks.panel.local"))
        append("\n")
        appendBookmarkChip(node.item.bookmark, node.item.bookmark.localName)
        appendRemoteBreakdown(node.remotes)
        appendCommitInfo(node.repo, node.item.id, entryLookup)
    }

    is BookmarkNode.Remote -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        appendGroupBracket(node.item.bookmark.remote)
        append("\n")
        appendBookmarkChip(node.item.bookmark, node.item.bookmark.localName)
        if (node.item.bookmark.behindCount > 0) {
            append("\n")
            appendBracket(listOf { append(message("bookmarks.panel.tooltip.remote.forcepush")) })
        }
        appendCommitInfo(node.repo, node.item.id, entryLookup)
    }

    is BookmarkNode.Tag -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        appendGroupBracket(JujutsuBundle.message("bookmarks.panel.tags"))
        append("\n")
        appendTagChip(node.item.tag, node.item.tag.name)
        appendCommitInfo(node.repo, node.item.id, entryLookup)
    }

    is BookmarkNode.Category -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        if (node.isDanglingHeadsGroup) {
            append(unbookmarkedHeadsCountText(node.children.size))
        } else {
            appendGroupBracket(node.displayName, node.rollup)
            appendLeafCount(node.refKind, node.rollup.leafCount)
        }
    }

    is BookmarkNode.Prefix -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        appendGroupBracket(node.groupLabel, node.rollup)
        append("\n")
        append(node.fullPath)
        appendLeafCount(node.refKind, node.rollup.leafCount)
    }

    is BookmarkNode.WorkingCopy -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        if (node.onWorkingCopyNames.isNotEmpty()) {
            appendBookmarkNameList(node.onWorkingCopyNames)
        } else {
            appendClosestBracket(node.closest, isDanglingHead = false)
        }
        appendCommitInfo(node.repo, node.id, entryLookup)
    }

    is BookmarkNode.DanglingHead -> htmlString {
        appendRepoLine(node.repo, isMultiRepo)
        appendGroupBracket(JujutsuBundle.message("bookmarks.panel.unbookmarked"))
        append("\n")
        appendClosestBracket(node.closest, isDanglingHead = true)
        appendCommitInfo(node.repo, node.id, entryLookup)
    }

    // Only shown at all in a multi-repo project (buildBookmarkTree's only caller of RepoGroup),
    // so unlike appendRepoLine's other call sites this one isn't conditional on isMultiRepo.
    is BookmarkNode.RepoGroup -> htmlString { append(node.repo) }
}

private fun TextCanvas.appendRepoLine(repo: JujutsuRepository, isMultiRepo: Boolean) {
    if (isMultiRepo) {
        append(repo)
        append("\n")
    }
}

/** `[icon] [GroupLabel]`, optionally with rollup tags appended into the same bracket - see
 * [BookmarkRollup.divergenceText]/[BookmarkRollup.unsyncedCount]. */
private fun TextCanvas.appendGroupBracket(groupLabel: String, rollup: BookmarkRollup = BookmarkRollup.EMPTY) {
    append(icon(AllIcons.Nodes::Folder))
    space()
    val parts = buildList<TextCanvas.() -> Unit> {
        add { append(groupLabel) }
        val divergence = rollup.divergenceText()
        if (divergence.isNotEmpty()) add { append(divergence) }
        if (rollup.unsyncedCount > 0) {
            add { append(JujutsuBundle.message("bookmarks.panel.tooltip.folder.unsynced", rollup.unsyncedCount)) }
        }
    }
    appendBracket(parts)
}

/** "N bookmarks"/"N tags" (transitive leaf count) on its own line - omitted for an empty folder,
 * which the group bracket alone already conveys nothing is inside. */
private fun TextCanvas.appendLeafCount(refKind: RefKind, leafCount: Int) {
    if (leafCount == 0) return
    val plural = leafCount != 1
    val noun = when (refKind) {
        RefKind.BOOKMARK -> "bookmarks"
        RefKind.TAG -> "tags"
    }
    val key = "bookmarks.panel.tooltip.folder.count.$noun.${if (plural) "plural" else "one"}"
    append("\n")
    append(JujutsuBundle.message(key, leafCount))
}

/** `[bookmark icon] name(, [bookmark icon] name...)` - bookmark(s) sitting directly on `@`. */
private fun TextCanvas.appendBookmarkNameList(names: List<String>) {
    names.forEachIndexed { i, name ->
        if (i > 0) append(", ")
        append(icon(JujutsuIcons::Bookmark))
        append(name)
    }
    append("\n")
}

/**
 * `[N commits ahead of [bookmark icon] name, ...]` or `[no ancestor bookmark]` - the compact form
 * for [BookmarkNode.WorkingCopy] (no bookmark directly on `@`) and [BookmarkNode.DanglingHead].
 * [isDanglingHead] prefixes the slashed-bookmark glyph (matching the row's own icon and the log
 * table's own status tag for the same case, jj-idea-uyu9 follow-up) - `false` for
 * [BookmarkNode.WorkingCopy], which isn't a dangling head (excluded from that concept by
 * definition; see [in.kkkev.jjidea.jj.danglingHeads]).
 */
private fun TextCanvas.appendClosestBracket(closest: ClosestBookmarks?, isDanglingHead: Boolean) {
    appendBracket(
        listOf {
            if (isDanglingHead) {
                append(icon(JujutsuIcons::BookmarkNone))
                space()
            }
            if (closest == null) {
                append(JujutsuBundle.message("bookmarks.panel.tooltip.closest.none"))
            } else {
                appendClosestBookmarkStatus(closest)
            }
        }
    )
    append("\n")
}

/**
 * One `[<remote>, ...]` line per tracked remote sharing this local bookmark's name (jj-idea-uyu9
 * follow-up: "ahead/behind status for each remote, with an explicit status for when a force-push
 * would be required"). A remote strictly behind (whether or not also ahead) can't be
 * fast-forwarded, so push there would need `--force` (or equivalent) - flagged explicitly rather
 * than left for the user to infer from the arrows.
 */
private fun TextCanvas.appendRemoteBreakdown(remotes: List<Bookmark>) {
    for (remote in remotes.sortedBy { it.remote }) {
        append("\n")
        val parts = buildList<TextCanvas.() -> Unit> {
            add { append(remote.remote) }
            val divergence = divergenceText(remote.aheadCount, remote.behindCount)
            add {
                if (divergence.isEmpty()) {
                    append(message("bookmarks.panel.tooltip.remote.insync"))
                } else {
                    append(divergence)
                }
            }
            if (remote.behindCount > 0) {
                add { append(message("bookmarks.panel.tooltip.remote.forcepush")) }
            }
        }
        appendBracket(parts)
    }
}

/**
 * The commit-info block, DRY with the log's own row tooltip via [appendChangeTooltip]. Also
 * carries the same `@ Working Copy` status tag [in.kkkev.jjidea.ui.components.appendSummaryAndStatuses]
 * shows on a commit-row tooltip (jj-idea-uyu9 follow-up) - the "@" row's own commit-info block
 * was missing it before, unlike every other place this codebase surfaces working-copy status.
 */
private fun TextCanvas.appendCommitInfo(repo: JujutsuRepository, id: ChangeId?, entryLookup: (ChangeKey) -> LogEntry?) {
    val entry = id?.let { entryLookup(ChangeKey(repo, it)) } ?: return
    append("\n")
    appendChangeTooltip(entry)
    if (entry.isWorkingCopy) {
        append("\n")
        appendBracket(
            listOf {
                colored(JujutsuColors.WORKING_COPY) {
                    append("@ ")
                    append(message("status.workingcopy"))
                }
            }
        )
    }
}

private fun unbookmarkedHeadsCountText(count: Int) =
    if (count == 1) {
        JujutsuBundle.message("bookmarks.panel.tooltip.unbookmarked.one")
    } else {
        JujutsuBundle.message("bookmarks.panel.tooltip.unbookmarked.plural", count)
    }
