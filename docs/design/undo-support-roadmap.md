# Undo Support Roadmap

## Context

There is no `jj undo`, no `jj op log`, and no `undo` method on `CommandExecutor` anywhere
in the plugin (`jj/CommandExecutor.kt`). It appears only as an aspiration in
`ROADMAP.md:10` ("Operation Log & Undo — Browse `jj op log`, undo/redo with toolbar
buttons"). Every mutating action's failure path is `tellUser(...)`; there is no rollback.

This became a hard dependency of [jj-idea-6oeg](jj-idea-6oeg-drag-and-drop-graph-ops.md):
applying a history rewrite on mouse-release, with no in-IDE undo, is a materially
different risk profile from a dialog with a Cancel button. Rather than adding interim
confirmation dialogs to every drag gesture (which would then have to be removed once undo
ships — churning the UX twice), this roadmap makes Stage 1 below a **blocking
prerequisite** for immediate-apply drag-and-drop.

This document also exists independently of the DnD spike — it is the `ROADMAP.md:10` item
proper, and the design decisions here (especially the concurrency-safety analysis and the
Cmd+Z recommendation) apply regardless of what triggers a future undo request.

## The core problem: identifying "our" operation safely

Every jj command appends one entry to the repository's **operation log** — a DAG of
operations, each with parent links, inspectable via `jj op log`. Three primitives exist for
moving backward through it, and only one is safe to expose per-action without a browsing UI:

### Rejected: `jj undo`

*"Undo the last operation. If used repeatedly, it will restore increasingly older
operations."* This reverts **the repo's last operation**, which may not be ours — a
background snapshot, another workspace, or a concurrent process (including another AI
agent operating on the same repo) can interleave between our command and the undo click.
A user who clicks "Undo" on their rebase could silently revert someone else's work instead.

### Rejected: `jj op restore <id>`

The naive fix — capture the head operation id *before* our command runs, and restore to it
on undo — is not safe either, and is worse than it looks. `jj op restore` is *reset*, not
*revert*: *"restores the repo to the state at the specified operation, effectively undoing
all later operations."* Any operation that landed after the captured point is discarded,
**whether it happened before or after the operation we actually meant to undo**. A
concurrent agent's commit made between our capture and our undo click would be silently
erased along with our own change. This looked like the obvious fix and is the one the
initial draft of this document proposed; it does not survive scrutiny under concurrency.

### Correct: `jj op revert <id>`

*"Create a new operation that reverts an earlier operation... by applying the inverse of
the operation."* This targets **one specific operation by id** and leaves every other
operation — including concurrent ones — intact. It is the `git revert` of the operation
log, not the `git reset`.

This still leaves one question: how do we identify *our* operation's id, given that reading
"the current head" after our command runs is exactly the same race that made `op restore`
unsafe? The op log is a DAG with parent links, and those are available via template
(`self.parents()`, verified against jj 0.44), so identity comes from **linkage**, not
recency:

1. **Before** the command: capture `head = jj op log -n 1 --no-graph -T 'id'`.
2. Run the command.
3. **After**: read the most recent few operations with `id` and
   `self.parents().map(|p| p.id())`, and take the one whose parent set contains `head`.
   That is our operation, regardless of what else landed in the meantime — a concurrent
   operation from the same starting point produces a *sibling*, not our match.
4. Undo = `jj op revert <that id>`.

Two caveats to design in from the start:

- **If the parent-match is not unique, withhold undo.** A concurrent process operating from
  the same `head` produces a divergent op log that jj later merges; in that window, two
  operations can share our captured `head` as a parent. Guessing which one is ours is worse
  than offering nothing — expire the undo balloon instead.
- **Pass `--what repo`.** The default scope for `op revert` is `repo remote-tracking`, and
  jj's own help warns: *"remote-tracking bookmarks — do not restore these if you'd like to
  push after the undo."* Reverting remote-tracking state as a side effect of undoing a local
  rewrite would desync the push story. Only `repo` should be touched.

One consequence worth stating plainly: `op revert` is *safe* but not always *clean* —
inverting an operation that a later operation built on can materialise conflicts, exactly
as `git revert` can conflict. That is the correct trade for an undo affordance: it never
silently discards anyone's work, and jj surfaces the resulting conflict as ordinary,
first-class repository state rather than failing the undo outright.

## Stages

### Stage 1 — Undo exactly the operation we performed (blocks DnD)

Scope:
- `CommandExecutor.opLog(limit: Int, template: String? = null): CommandResult` and
  `CommandExecutor.opRevert(id: OperationId, what: Set<OpRevertScope> = setOf(REPO)):
  CommandResult`, plus their pure arg builders in `CliExecutor.kt`, following the existing
  builder/executor split used throughout that file.
- A small helper — capture-before / match-parent-after, per the algorithm above — used by
  every mutating command that wants an undo affordance.
- Wire the result into a balloon via the existing
  `Notification.addExpiringAction(messageKey) { ... }` helper
  (`ui/services/JujutsuNotifications.kt:27`) on the existing `Jujutsu` BALLOON group
  (`plugin.xml:70`) — both already exist and need no new infrastructure.

This is deliberately small and self-contained: no operation-log browser, no new dialog, no
new notification group. It is independently useful (any mutating action can opt in to an
undo balloon) and is what unblocks drag-and-drop's immediate-apply operations.

### Stage 2 — First-class Undo/Redo actions

Registered `Jujutsu.Undo` / `Jujutsu.Redo` actions (`jj undo` / `jj redo` — both real
top-level jj commands, distinct from the `op revert`-based per-action undo above) in the
VCS menu and the log toolbar. Two requirements:
- The presentation must name *what* will be undone — read the head operation's description
  via `jj op log` and put it in the action text. A blind "Undo" button acting on a whole
  repository is unnerving without knowing what it targets.
- The action must be disabled with a reason when the head operation is not undoable (e.g.
  already at the root of the op log).

This is a coarser, always-available complement to Stage 1's precise per-drop undo — useful
for undoing something that did *not* originate from an in-IDE gesture (e.g. a CLI command
run in a terminal alongside the IDE).

### Stage 3 — Operation log UI (the `ROADMAP.md:10` item proper)

Parse `jj op log` into an `OperationEntry` (id, description, timestamp, user, tags,
parents), and present it as a browsable list with a per-entry action.

**Default per-entry action: "Revert this operation"** (`jj op revert <id>`), not "Restore
to here" (`jj op restore <id>`) — for the identical concurrency reason established above.
Restore is a reset to a point in time and can discard anything that landed afterward;
revert undoes exactly one entry and composes safely with everything else in the log,
including entries added by other processes after the one being undone. Offer restore only
as an explicit, clearly-labelled secondary action for the deliberate "I want to time-travel
to exactly this state" case — never as the default click target, since a user reaching for
"undo my last few changes" via restore could unknowingly discard other legitimate work.

The right model to copy for the overall UI is **IntelliJ's own Local History** — a
system-level, time-ordered list of states with per-entry revert and its own dedicated UI,
deliberately *not* wired into the editor's Cmd+Z stack. This also matches
contributing.md's UX-priority tie-breaker (prefer an IntelliJ-native pattern over a bespoke
one).

### Stage 4 — Polish

Extend the Stage 1 undo balloon to every mutating jj action across the plugin, not just
drag-and-drop drops — description edits, abandon, squash, split, bookmark actions, etc. By
this point the underlying mechanism (capture/match/revert/balloon) already exists; this
stage is purely about wiring it up everywhere it's useful.

## On Cmd+Z / Shift+Cmd+Z integration

**Recommendation: do not bind jj undo/redo to the editor's Cmd+Z / Shift+Cmd+Z.** Four
reasons, the third being decisive:

1. **No precedent.** A search across all of `platform/` and `plugins/` in
   intellij-community found zero references to `UndoManager` in `git4idea`. The only VCS
   use of `UndoableAction` on the whole platform is `PartialLocalLineStatusTracker`, and
   that is genuinely document-scoped — line-level staging inside one open file — not
   repository history. No IDE-integrated VCS treats repository operations as editor-undo
   entries.
2. **Scope mismatch.** `UndoManager` is editor/document-scoped; even "global" undo still
   resolves against the focused editor's document affinity. A rebase has no document scope
   — it can touch every file on disk, including files with no editor currently open, so
   there is no sensible document to attach the undo entry to.
3. **Safety inversion.** Cmd+Z in an editor is reached for reflexively to undo typing. If a
   repository rewrite shared that same stack, a user who just rebased and then started
   typing would press Cmd+Z expecting to delete a character — and instead unwind a history
   rewrite. That makes the single cheapest, most automatic gesture in the entire IDE also
   the most destructive one available in the plugin. That is backwards, and especially
   risky while the plugin's undo story is new and untested by users.
4. **Model mismatch.** jj's undo is repository-global and strictly sequential (repeated
   `jj undo` walks further back through one shared op log); IntelliJ's editor undo is
   per-document and can branch. Two editors focused on two different files would imply two
   different meanings for "undo" against the same repository state — there is no coherent
   way to unify the two models.

Instead: register `Jujutsu.Undo` / `Jujutsu.Redo` as ordinary, discoverable actions in the
menu, and leave them **rebindable by the user via Keymap settings** rather than bound by
default — consistent with the project's stated UX tie-breaker of preferring to rebind
existing platform mechanisms over inventing new settings. If a default binding is wanted at
all, use a chord clearly distinct from the editor's undo, such as Cmd+Alt+Z. In practice,
the primary undo affordance for a drag-and-drop drop is the Stage 1 balloon's inline Undo
link, appearing right where and when the action happened — which is exactly why Stage 1
alone is sufficient to unblock drag-and-drop, without needing Stages 2 or 3 first.

## Verified references

- `jj op log`, `jj op revert --help`, `jj op restore --help`, `jj undo --help` — all
  verified against the locally installed `jj 0.44.0`.
- `self.parents()` on an operation-log template — verified working:
  `jj op log -n 3 --no-graph -T 'id.short() ++ " | parents: " ++
  self.parents().map(|p| p.id().short()).join(",") ++ " | " ++ self.description()'`.
- `PartialLocalLineStatusTracker` — `platform/vcs-impl/src/com/intellij/openapi/vcs/ex/`
  in intellij-community, the sole platform-wide `UndoableAction` consumer in a VCS context.
- `git4idea` source tree — no `UndoManager` reference found by full-tree grep.
