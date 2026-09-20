# Native jj conflict resolution UX (jj-idea-n6fz)

**Status: design, pre-implementation.** This is the follow-up design work `jj-idea-9ms6`
(GitHub #63) explicitly deferred: that issue fixed a *destructive bug* in the borrowed git-shaped
merge dialog (closing without resolving silently discarded a side) without questioning whether the
dialog itself is the right shape for jj. This document makes that case, inventories what the
plugin already has to build on, and splits the remaining work into sized, independently-shippable
children under this epic.

## The model mismatch

Git's conflict model: resolving is a one-shot, index-mediated transaction. The working file is
disposable scratch — the index is authoritative, and once you `git add` the resolved file the
markers are gone for good. IntelliJ's `MergeProvider2`/`MultipleFileMergeDialog` is built entirely
around that: open a modal, pick sides or hand-edit, close it, done. There is no partial state
between "conflicted" and "resolved" that git's own tooling represents.

jj's conflict model is different in a way that matters for UI design, not just terminology:

- **A conflict is a first-class object stored in the commit**, not an index entry. It has no
  separate "resolved" flag distinct from the markers themselves.
- **The working file's markers ARE the conflict record.** They're materialized fresh from the
  commit on checkout and **re-parsed from scratch on every snapshot** — there is no other source
  of truth to fall out of sync with.
- **Resolution is naturally incremental.** Edit some markers, save, keep working on something
  else, come back later — jj simplifies the conflict on the next snapshot as markers disappear.
  Nothing requires an atomic "did you finish the whole file" transaction.
- **Conflicts exist at any revision, not just `@`.** `jj resolve -r <rev>` and `jj file show -r
  <rev>` both work on arbitrary commits. Multiple descendants can carry copies of the same
  conflict simultaneously (jj propagates conflicts downward through rebases).
- **Full undo history via evolog/op log.** A resolution is just another operation; `jj undo`
  reverses it like any other command.

A modal, one-shot, working-copy-only dialog is a reasonable UI for git's model. For jj's, it's a
borrowed shape that actively fights the tool underneath it — which is exactly why GitHub #63
happened: the platform's merge tool assumes the file it's editing is disposable, and jj's working
file is never disposable.

## What already exists (do not rebuild)

The plugin has already built substantial jj-native infrastructure around conflicts; this design
composes it rather than replacing it.

| Piece | Path | Notes |
|---|---|---|
| Marker parser, all 3 styles + GitHub #112 side reorientation | `jj/conflict/JjMarkerConflictExtractor.kt` | `extract(ByteArray): ExtractedConflict?`. Handles git/snapshot/diff marker styles and rebase-conflict "Yours" reorientation. |
| `jj resolve --list` line parser | `jj/conflict/ConflictInfo.kt` | `ConflictInfoParser.parse(stdout): Map<String, ConflictInfo>`; shape text (`"2-sided conflict including 1 deletion"`) preserved verbatim. |
| Per-path conflict shape cache | `jj/conflict/JujutsuConflictRegistry.kt` | Revision-keyed as of jj-idea-cf2c's spike (see "Gaps found" below) — no longer working-copy only. |
| #63-safe scratch-document merge tool | `vcs/merge/JujutsuConflictResolver.kt` | Kept as an accelerator (see "What stays as-is"). |
| `MergeProvider2` + bulk `:ours`/`:theirs` | `vcs/merge/JujutsuMergeProvider.kt:114` `acceptFilesRevisions` | Routes through `jj resolve --tool` so modify/delete conflicts actually delete rather than leaving an empty file. |
| Read a conflict at an arbitrary revision (read-only) | `vcs/diff/JujutsuConflictDiffRequestProvider.kt` | GitHub #119 / `jj-idea-ct7e`. The primitive S3/S4 below reuse for the write side. |
| Single funnel all four resolve gestures route through | `actions/change/workingCopyConflicts.kt:30` `resolveConflicts(project, files)` | Log menu, file action, toolbar button, tree-node link, and the editor banner all call this. |
| Editor banner | `ui/editor/JujutsuConflictEditorNotificationProvider.kt` | Today: static text + one "Resolve" link. S1 below extends this in place. |
| "Merge Conflicts" tree node + toolbar button | `ui/common/JujutsuConflictsNode.kt`, `actions/file/ResolveAllConflictsAction.kt` | Unaffected by this design. |
| Command wrappers, both already revision-parametrised | `jj/CommandExecutor.kt:368` `resolveList(revision)`, `:378` `resolve(paths, tool, revision, configArgs)`; args at `jj/cli/CliExecutor.kt:377,381` | The CLI layer already supports everything S3/S4 need; nothing new to add there. |

### Gaps found while surveying the above

- ~~**`jj/ChangeService.kt:68` `conflictedPathsFor` re-implements path extraction from `jj
  resolve --list` by hand** instead of reusing `ConflictInfoParser.parse(...).keys`, and
  discards the sides/deletions shape entirely, so `JujutsuConflictRegistry` is only ever
  populated for the working copy.~~ **Fixed since this was written** (found during the S4
  spike, jj-idea-cf2c): `ChangeService.conflictInfosFor(entry)` now calls
  `resolveList(entry.id)`, parses with `ConflictInfoParser`, and calls
  `JujutsuConflictRegistry.replace(repoDir, infos.values, entry.id)`; the registry is
  revision-keyed (`get(repoDir, file, revision)`, key `"${repoDir.path}@$revision"`). Shape
  data off-`@` is available today — S3 (`jj-idea-qmws`) is no longer blocked on this.
- **`actions/change/resolveConflictsAvailability.kt`'s `NEEDS_EDIT` state** exists purely because
  `JujutsuMergeProvider.loadConflict` is hard-coded to `WorkingCopy` (see its own doc comment,
  `JujutsuMergeProvider.kt:48-52`) — a git-shaped "you can only resolve what's checked out"
  limitation. jj itself has no such restriction. Retiring this state is the single biggest UX
  unlock in this design (browsing history and fixing a conflict on a non-`@` commit without first
  checking it out) and is scoped as its own spike (see S4) because the interactive write-back path
  has never been exercised off-`@` and needs a safety check before committing to a full feature.
- **No inlay hints, line markers, or `GutterIconRenderer`s exist anywhere in the plugin yet.** The
  only gutter-adjacent code is `diffedit/HunkArrowDiffExtension.kt` (diff-viewer arrows, gated to
  the hunk-picker session) and `vcs/diffbase/DiffbaseContentLoader.kt` (the platform's own
  line-status-tracker gutter, not custom rendering). S2 below is the first consumer of this kind of
  UI in the codebase and should expect to spend real design/prototyping time on it, not just wire
  up an existing pattern.

## What stays as-is

`JujutsuConflictResolver` and the `MergeProvider2`/`MergeSessionEx` wiring in
`JujutsuMergeProvider` are **not removed or replaced** by anything in this design. They remain the
three-way merge tool, reachable as an explicit "Open Merge Tool" action from every entry point.
Reasons to keep it:

- It is the only path that gives a full three-pane diff view for hand-editing a complex conflict —
  genuinely useful, not just a fallback.
- It integrates with the platform's own multi-file merge dialog (Commit tool window, when a user
  opts back into it) via `MergeProvider2`, which several other IntelliJ subsystems key off.
- The GitHub #63 fix living in `JujutsuConflictResolver` (scratch-document output, never handing
  the real file's Document to the platform) is a solved, tested problem. Nothing in this design
  reopens it.

The slices below make the editor — not the modal — the *default*, discoverable path, and demote
the modal to an accelerator reachable in one click, matching how git4idea itself increasingly
treats its own merge tool relative to inline gutter conflict actions.

## The six slices

Ordered by value and dependency. Each becomes its own beads issue (see epic children).

### S1 — Editor as the primary, incremental surface

Upgrade `JujutsuConflictEditorNotificationProvider` from a single static "Resolve" link into a
banner that reflects jj's actual model:

- **Live block count**, parsed from the editor's `Document` text (not just
  `ChangeListManager`'s binary `MERGED_WITH_CONFLICTS` status), so the count decrements as the
  user hand-edits markers and the banner disappears once the last block is gone — without waiting
  for a jj snapshot.
- **Text that states the model**: something like "This file has 2 unresolved conflicts — edit the
  markers and save, or use the actions below" rather than the current generic "This file has merge
  conflicts."
- **`Accept <side-1 label>` / `Accept <side-2 label>` action links**, using
  `ExtractedConflict.currentTitle`/`lastTitle` for the label text (the same jj-native commit+role
  labels `JujutsuConflictResolver`'s pane titles already use, GitHub #112), routed through
  `CommandExecutor.resolve(paths, ":ours"/":theirs")` — reusing the exact call
  `JujutsuMergeProvider.acceptFilesRevisions` makes today, so modify/delete conflicts correctly
  delete the file rather than leaving empty content.
- **Secondary "Open Merge Tool" link** that calls the existing `resolveConflicts(project, files)`
  funnel, unchanged.

API sketch: `JjMarkerConflictExtractor` already returns block count implicitly (count of
`Segment.Conflict` entries) — expose a lightweight `blockCount(bytes): Int` alongside `extract`, or
reuse `extract(...)` output directly since the banner already has to call it for labels. No new jj
commands needed.

Risk: keeping the live count in sync with in-progress edits without re-parsing the whole document
on every keystroke — bind to `DocumentListener` with the same 300ms-class debounce pattern
`JujutsuStateModel.scheduleRepositoryRefresh()` uses, not a naive per-edit rescan.

### S2 — jj conflict markers as first-class editor regions

The natural conclusion of "the markers are the conflict record": recognize conflict marker blocks
as live editor regions (like IntelliJ's native git conflict gutter actions), not just something
scanned once for a banner.

- Incremental document scanner (rescans only the changed range after the first full pass — O(edit
  size), not O(file size), per contributing.md's performance rules) feeding `RangeHighlighter`s
  over each block.
- Per-block gutter actions: *Accept side #1 / side #2 / both / base*. Each edits the **document
  text directly** — replace the marker block with the chosen side's lines — with no jj command and
  no modal involved at all. This is the purest expression of the jj model: resolving a block is
  just an edit like any other; jj simplifies the conflict from the markers that remain on the next
  snapshot.
- All three marker styles (`JjMarkerConflictExtractor` already parses git/snapshot/diff) need
  matching editor-region recognition, including the git-style `|||||||` base section for an
  "Accept base" action where one exists.

This is a genuinely new UI surface for the codebase (no precedent — see gap above), largest scope
of the six slices, and depends on S1 only in that it shares the same document-scanning
infrastructure (worth building once, used by both).

### S3 — jj-native Conflicts view

A non-modal, multi-file conflicts list built directly on `resolveList(revision)` /
`ConflictInfoParser`, showing jj's own shape text per row (`"2-sided conflict including 1
deletion"`) rather than reconstructing that information from a platform-shaped `Change` list.

The key differentiator from the platform's `MultipleFileMergeDialog` (which this replaces as the
*bulk* affordance, alongside the existing "Merge Conflicts" tree node for the working copy): a
revision picker, so the view can show conflicts in **descendants and arbitrary other revisions**,
not only `@`. `CommandExecutor.kt:368`'s doc comment on `resolveList` — "infrastructure for the
future Conflicts tool window" — was written anticipating exactly this.

Depends on the registry gap (above) being fixed first, since a useful non-`@` conflicts view needs
`JujutsuConflictRegistry` populated for revisions other than the working copy. Also depends on the
S4 spike's outcome, since bulk actions in this view (accept side #1/#2 for every conflicted file at
a chosen revision) are exactly the write-at-arbitrary-revision operation S4 needs to validate is
safe.

Scale note for the implementing issue: listing conflicts for one revision is O(conflicts at that
revision), which `jj resolve --list -r` already bounds — no new per-file/per-commit loop on the
Kotlin side, so this needs an operation-count test only if the view adds its own scan (e.g.
resolving shape for every row eagerly) rather than lazily per visible row.

### S4 — Resolve at any revision (retire `NEEDS_EDIT`)

`jj resolve -r <rev>` and `jj file show -r <rev>` already exist as `CommandExecutor` calls and are
already exercised read-only by `JujutsuConflictDiffRequestProvider` (GitHub #119). What's missing
is the **write** side: letting the interactive per-file resolve flow
(`JujutsuConflictResolver`/the S1 banner's accept actions) target a revision other than `@` instead
of refusing via `NEEDS_EDIT`.

Filed as a **timeboxed spike**, not a feature, because the plugin has never exercised interactive
write-back off the working copy, and there are real open questions worth answering before
committing engineering time to the full feature: does resolving a conflict at a non-`@` revision
rebase every descendant that inherited the conflict (likely yes, matching `jj resolve -r`'s CLI
behavior) in a way the UI needs to communicate before the user commits to the action? Does the
existing undo/invalidate machinery (`JujutsuRepository.invalidate`) correctly refresh a log view
showing revisions far from `@`? The spike's job is to answer these with a working prototype and a
written recommendation, which S3's bulk-action scope and any follow-on "resolve this conflict from
the log, without checking it out first" feature both then build on.

### S4 spike findings (jj-idea-cf2c)

Verified against real jj 0.44 via `src/test/kotlin/in/kkkev/jjidea/contract/`
`ResolveAtRevisionContractCliTest.kt` and `ResolveWriteBackContractCliTest.kt` (both pass;
run with `./gradlew contractTest` — the `contract` tag is excluded from `check`/`test`).

**Q1: does resolving at a non-`@` revision correctly propagate to descendants, and does the
UI need to warn about it?**

Yes, and yes, with a specific shape. Built `initial → change-a/change-b` (divergent edits to
one line) → rebase `change-a` onto `change-b` (conflict) → `change-c → change-d` stacked on
the conflicted `change-a`:

- `jj resolve -r change-a --tool :ours file.txt` succeeds with `@` anywhere.
- Both descendants are rewritten and their conflict clears (`conflict` template flag
  `true → false` on `change-a`, `change-c`, and `change-d`).
- **Change ids are stable across the rebase; commit ids are not** — confirmed
  (`ResolveAtRevisionContractCliTest`'s first test asserts both). This matters for anything
  in the plugin that keys UI state (selection, expansion) by commit id rather than change id
  across a resolve.
- **The decisive case**: when `@` is a descendant of the resolved revision, `@` itself is
  silently rewritten and its on-disk working-copy file updated — `jj` prints "Working copy
  (@) now at: ..." and the file content changes with no further action
  (`ResolveAtRevisionContractCliTest`'s second test). This is `jj resolve -r`'s ordinary
  descendant-rebase behavior, identical to what already happens on `jj rebase`/`jj squash`
  today, but a resolve UI at a *browsed* revision is exactly the situation where the user's
  attention is on a commit that **isn't** `@` — they could plausibly not expect their working
  copy to move.

**Recommendation**: the UI must distinguish two cases before committing to the action, not
treat "resolve at revision X" as uniform:
- `@` is **not** a descendant of X: no special warning needed beyond the ordinary "N
  descendants will be rebased" notice already implicit in `jj resolve -r`'s own stderr.
- `@` **is** a descendant of X (including `@` itself): the confirmation must say the working
  copy will change — e.g. "This also updates your working copy" — since a browsed-revision
  resolve action is the one place in the plugin where that could surprise a user who assumes
  actions on a non-`@` row are scoped to that row. (This plugin has no jj-side way to
  distinguish "independently conflicted" descendants from "inherited-only" ones without a
  second `jj resolve --list -r` per descendant — out of scope for the warning text itself,
  which only needs "does this touch `@`", answerable with one revset check,
  e.g. `jj log -r 'X..@ & @'` / an `is_ancestor`-shaped query already available via existing
  log data.)

**Q2: does `JujutsuRepository.invalidate` correctly refresh a log view for revisions far
from `@`?**

Answered by code trace (`jj/JujutsuStateModel.kt:668` `invalidate` →
`stateModel.logRefresh.notify(Unit)` → `UnifiedJujutsuLogPanel`'s
`logRefresh.connect(this) { refresh() }` → `CommitTablePanel.refresh()` →
`UnifiedJujutsuLogDataLoader.refresh()`), not a live `runIde` observation — the trace is
unambiguous and self-documenting enough that a visual check wasn't run in this spike's
timebox; flagged below as a gap rather than asserted as verified.

**No — not for a revision on an already-loaded deep page, by design.** `refresh()` (the path
`invalidate()` triggers) is documented at `CommitTablePanel.kt:672-683` and
`UnifiedJujutsuLogDataLoader.kt:343-349` as a **deliberately cheap, page-1-only reconcile**:
it re-fetches only page 1 and splices it ahead of whatever deeper pages were already loaded,
*without* re-fetching those deeper pages. This is intentional (jj-idea-2c8k / GitHub #69):
`forceRefresh()` — full paged re-verification of every loaded page — used to run on every
`logRefresh` firing (i.e., after every write), which was too expensive; `refresh()` was
introduced specifically to make the common "write happened, reconcile the log" path cheap,
at the cost of not re-verifying rows already loaded on page 2+.

Consequence for S4: **resolving a conflict on a revision whose row is on an already-loaded
page 2+ leaves that row showing stale `hasConflict = true` state** until the user does an
explicit toolbar Refresh (which calls `forceRefresh()`, re-verifying everything currently
loaded) or scrolls past the loaded window and back. A resolve-from-the-log feature (S4/S3)
therefore cannot rely on `invalidate()`'s existing `select` parameter or `logRefresh` alone
to make its own effect visible for a revision beyond page 1 — it needs one of:
- After a non-`@` resolve, call `forceRefresh()` (not just `invalidate()`) when the resolved
  revision is known to be outside page 1 — the plugin already has this entry point
  (`DataLoader.forceRefresh`), it's just never invoked from a per-write path today by design.
- Or, narrower: patch just the resolved row's `hasConflict` in the already-loaded page's
  cached entries in place, avoiding a re-fetch — more code, but preserves the "cheap
  per-write path" property `refresh()` exists for.

Separately, `invalidateRepositoryState()` (called by `invalidate()`) invalidates
`workingCopies`, which drives `VcsDirtyScopeManager.dirDirtyRecursively()` — but the
`MERGED_WITH_CONFLICTS` statuses `workingCopyConflicts.kt` reads come from
`ChangeListManager`, which is disk/working-copy-derived. An off-`@` resolve doesn't touch
disk at all when `@` isn't a descendant, so this machinery is a no-op for it either way —
consistent with, not contradicting, the page-refresh finding above.

**No test currently pins this splice-vs-full-refresh distinction for a mutated row on an
already-loaded page** — `PagedLogWindowContractTest.kt` covers paging correctness (no
missing/duplicate commits) but not a post-write content change on a deep page. Recommended
as a required regression test for whichever issue implements the fix above.

**Write-back mechanism recommendation**

The ephemeral-merge-tool approach (`DiffEditTool.mergeToolConfigArgs` +
`diffedit/MergeApplyMain.kt`, landed by this spike, mirroring the already-shipped
`diffEditConfigArgs`/`HunkApplyMain` used by `jj split --tool`/`jj squash --interactive`) is
confirmed to work end-to-end against real jj (`ResolveWriteBackContractCliTest`): stage
resolved bytes to a temp file, register it as a one-shot `merge-tools.<name>` via `--config`,
run `jj resolve -r <rev> --tool <name>`. No alternative (a scratch `jj new`/`jj squash
--into` workspace) is needed — the merge-tool protocol already accepts an arbitrary
`-r <rev>`, so there's no "scratch workspace" step to build. This is the recommended
mechanism for the real S4 feature.

**Sizing note for the real S4 feature**: this is not "thread a revision parameter through
the existing interactive flow." Three structural obstacles, found while surveying for this
spike:
- `MergeProvider2.loadRevisions(VirtualFile)` has no revision slot and the platform
  interface can't be extended to add one — an off-`@` interactive resolve has to be a
  **second, log-shaped resolve flow** (a dialog/editor invoked from the log, not from
  `MergeProvider2`), not an extension of the existing per-file merge dialog.
- `JujutsuConflictResolver` writes resolved bytes straight to disk
  (`Files.write`/`Files.deleteIfExists`) — meaningless off-`@`. The new flow needs the
  merge-tool write-back above instead, not a parameter added to the existing writer.
- Everything above `CommandExecutor` (`workingCopyConflicts.kt`, `resolveConflicts`, both
  `NEEDS_EDIT`-gated actions) is `VirtualFile`-shaped with no revision dimension; none of it
  is directly reusable for a revision-scoped flow without a parallel code path.

**Verdict: go, with conditions.** Retiring `NEEDS_EDIT` is safe from jj's side (Q1) provided
the UI adds the descendant/`@`-touches-working-copy warning above, and is safe from the log
UI's side (Q2) only once the deep-page staleness gap is closed (or explicitly accepted with
a documented "click Refresh to see it" caveat, which is a worse UX than fixing it). Neither
blocker is large; both are now sized. Recommend filing S4 as its own beads issue (child of
`jj-idea-n6fz`) scoped to: the log-shaped resolve flow, the `forceRefresh`-or-patch-in-place
fix, and the descendant warning — with this section as its design input.

### S5 — Undo affordance

Route S1's accept actions (and, if it ships, S2's per-block accepts once they trigger a snapshot)
through `createUndoTrackedCommand` — already used elsewhere for exactly this purpose — and surface
an "Undo" action in the resulting success notification. This makes jj's own "change your mind
later, it's all in the op log" property visible in the UI at the moment a conflict is resolved,
rather than requiring the user to know to look at Operation Log / `jj undo` separately.

Depends on S1 shipping first (nothing to attach undo to otherwise).

### S6 — Demote the modal merge tool

Once S1 (and ideally S2) are live and proven, re-point the *default* action of each existing entry
point — editor banner, tree-node link, toolbar button, log context menu — at the new editor-first
flow, with "Open Merge Tool" surviving everywhere as an explicit secondary action rather than the
only option. Ships last, deliberately: it's a pure UI re-wiring with no new logic, and doing it
before S1/S2 are validated in real use would remove the fallback before the replacement has proven
itself.

## Verification for the design itself

This document's own claims are checkable without writing code:

- Every `file:line` citation above resolves against the current tree (spot-checked while writing
  this doc).
- The "gaps found" section names concrete code (`ChangeService.kt:68`,
  `resolveConflictsAvailability.kt`'s `NEEDS_EDIT`) that a reader can independently confirm rather
  than taking on faith.
- The S4 spike findings are backed by two contract tests run against real jj 0.44
  (`ResolveAtRevisionContractCliTest`, `ResolveWriteBackContractCliTest`) rather than asserted
  from memory; the Q2 finding is a code trace, explicitly flagged as not visually confirmed via
  `runIde` within the spike's timebox.
