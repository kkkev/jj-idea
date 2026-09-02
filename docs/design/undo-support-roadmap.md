# Undo Support Roadmap

**Status: Stage 1 shipped (jj-idea-v9zp).** The rest of this document is preserved as
written before implementation for the concurrency-safety analysis and the Cmd+Z
recommendation, both unchanged by what shipped; sections describing what actually landed
are marked "shipped" inline, with corrections to the original algorithm called out
explicitly rather than silently edited away — the corrections themselves are as much the
record of this design as the original proposal.

## Context

Originally: there was no `jj undo`, no `jj op log`, and no `undo` method on
`CommandExecutor` anywhere in the plugin (`jj/CommandExecutor.kt`) — an aspiration only in
`ROADMAP.md:10` ("Operation Log & Undo — Browse `jj op log`, undo/redo with toolbar
buttons"). Every mutating action's failure path was `tellUser(...)`; there was no rollback.

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
unsafe?

**Rejected: parent-linkage.** The op log is a DAG with parent links (`self.parents()`,
verified against jj 0.44), so an early draft of this document proposed identity via
**linkage, not recency**: capture `head = jj op log -n 1 --no-graph -T 'id'` before the
command, then after it, take the child whose parent set contains `head`. This does not
survive scrutiny, for two independent reasons found during implementation:

- **Snapshot ops.** jj snapshots a dirty working copy as its *own* operation before running
  the real command, so the log becomes `head → "snapshot working copy" → <our op>`. Taking
  the direct child reverts the snapshot, not the command. (With a clean working copy there
  is no snapshot op, so the gap is 0 or 1 — it varies, which is itself a sign the approach
  is fragile.)
- **Interleaving (decisive).** If a concurrent process — another IDE window, an agent, a
  terminal `jj` command — lands an operation between our capture and our command, the direct
  child of `head` is uniquely *theirs*. We would offer "Undo abandon" wired to their
  operation. No refinement of parent-linkage fixes this: linkage can only express "ran after
  my capture," never "is mine."

**What ships instead: a per-invocation token.** Every jj command accepts (and silently
ignores) unknown `--config` keys — verified on 0.37 and 0.44 — and every operation records
its own argv, readable via the `tags` template keyword (`self.attributes()` on 0.44+, but
that method doesn't exist on 0.39, so `tags` is what's actually used; both read the same
data). So:

1. Generate a fresh UUID per invocation and run the command as
   `jj --config jj-idea.undo-token=<uuid> <command...>`.
2. On success, read back a window of recent operations
   (`jj op log -n 20 --no-graph --ignore-working-copy -T <template>`) and find the one whose
   recorded argv contains `jj-idea.undo-token=<uuid>` **and** is not a snapshot operation
   (jj's paired snapshot, when one exists, carries the identical argv — same invocation — so
   the snapshot exclusion is still required, just no longer load-bearing for identity).
3. Undo = `jj op revert <that id> --what repo`.

This is immune to interleaving by construction: a concurrent operation can never carry our
token, regardless of when it lands relative to our command or our op-log read — there is no
race window to reason about, unlike the "before" capture the linkage approach needed.
Implementation: `jj/cli/OpLogParser.kt` (`findTaggedOperation`), wired into
`CliExecutor`'s private `execute` (`jj/cli/CliExecutor.kt`).

Two caveats carried over, still correct:

- **If the match is not unique, withhold undo.** More than one non-snapshot operation
  carrying the token should never happen with a fresh UUID, but a corrupted or replayed op
  log is not impossible — `findTaggedOperation` returns `Ambiguous` rather than guessing.
- **Pass `--what repo`.** The default scope for `op revert` is `repo remote-tracking`, and
  jj's own help warns: *"remote-tracking bookmarks — do not restore these if you'd like to
  push after the undo."* Reverting remote-tracking state as a side effect of undoing a local
  rewrite would desync the push story. Only `repo` is touched — `CommandExecutor.opRevert`
  doesn't even accept `REMOTE_TRACKING` as a default.

One consequence worth stating plainly: `op revert` is *safe* but not always *clean* —
inverting an operation that a later operation built on can materialise conflicts, exactly
as `git revert` can conflict. That is the correct trade for an undo affordance: it never
silently discards anyone's work, and jj surfaces the resulting conflict as ordinary,
first-class repository state rather than failing the undo outright.

## Not every operation is undoable, and jj will not tell you

`op revert --what repo` only inverts **repo-scope** state. Reverting an operation whose
effect lives elsewhere reports success while doing nothing — or worse. Verified against real
jj 0.37/0.39/0.44:

| Command | `op revert … --what repo` result |
|---|---|
| `git push` | `Reverted operation: …` / `Nothing changed.` The remote still has the commit. |
| `git fetch` | Local bookmark moves back, but the remote-tracking bookmark still points at the fetched (now hidden) commit — a visibly broken repo. |
| `bookmark untrack` | `Nothing changed.` — remote-tracking scope only. |
| `bookmark track`, `config set`/`unset` | Same category; `config set`/`unset` write **no operation at all** (verified: the op-log head is unchanged after `jj config set --repo ...`). |
| `bookmark forget`, `bookmark delete`, `git init` | **Correctly reversible** — despite superficially resembling the row above, these are pure repo-scope mutations. Not every same-family command shares a classification; each was verified individually. |

Because jj gives no revertibility signal (`jj op diff` distinguishes "Changed remote
bookmarks" from "Changed commits" in its output, but has no `-T` option — using it would
mean parsing English section headers, plus an extra invocation, for information the plugin
already has at the point it builds the command), revertibility is a **declared, per-command
property**, not something inferred from a result. `jj/cli/JjInvocation.kt`'s `Reversibility`
enum (`REVERSIBLE` / `IRREVERSIBLE` / `READ_ONLY`) is attached to every command's argument
builder — an **allowlist**, so a newly-added command defaults to nothing being claimed about
it rather than silently becoming undoable. `CommandResult.Success.Irreversible.Reason`
records *why* a successful command still offers no undo (`NOT_REVERSIBLE_COMMAND` /
`READ_ONLY` / `NO_OPERATION` / `NOT_IDENTIFIED` / `NOT_TRACKED`).

## Stages

### Stage 1 — Undo exactly the operation we performed (blocks DnD) — shipped (jj-idea-v9zp)

Shipped:
- `CommandResult` became a sealed hierarchy (`Success.Reversible` / `Success.Irreversible`,
  `Failure.Exited` / `Failure.TimedOut` / `Failure.NotLaunched`) so a result can carry undo
  information, and every failure kind is now honestly distinguishable — a timed-out process
  has real partial stdout but no real stderr; a not-launched one has no streams at all.
- `JjInvocation`/`Reversibility` (`jj/cli/JjInvocation.kt`): every one of the 42 jj commands
  the plugin issues now declares its reversibility next to the code that builds it, per the
  verified table above.
- `CommandExecutor.opLog(limit, template)` and `opRevert(id, what = setOf(REPO))`, plus
  `withUndoTracking(): CommandExecutor` — an opt-in decorator (costs one extra `jj op log`
  call per `REVERSIBLE` command) rather than a flag threaded through every method, so
  untracked call sites pay nothing and cannot accidentally claim undo support.
- Token injection and identification live in `CliExecutor`'s private `execute` (see "What
  ships instead" above) — no separate capture-before call, since the token replaces it.
- Wired into a balloon via `CommandExecutor.Command.withUndoBalloon(...)`
  (`ui/services/UndoBalloon.kt`), using the existing
  `Notification.addExpiringAction(messageKey) { ... }` helper
  (`ui/services/JujutsuNotifications.kt:27`) on the existing `Jujutsu` BALLOON group — both
  already existed and needed no new infrastructure.
- **Beyond the original Stage 1 scope**: `JujutsuUndoService` (`ui/services/JujutsuUndoService.kt`)
  and `Jujutsu.UndoLastOperation` (`actions/undo/UndoLastOperationAction.kt`) — a persistent,
  named "Undo <last action>" entry in `Vcs.MainMenu` that survives a dismissed balloon.
  Pulled forward from Stage 2 below (see that section).
- Wired into one action: `actions/change/abandonChangeAction.kt`. Stage 4 extends this to
  the rest.

This remains self-contained: no operation-log browser, no new notification group. It is
independently useful (any mutating action can opt in via `.withUndoTracking()` +
`.withUndoBalloon(...)`) and is what unblocks drag-and-drop's immediate-apply operations.

### Stage 2 — First-class Undo/Redo actions

`Jujutsu.UndoLastOperation`'s naming requirement — the presentation must say *what* will be
undone, never act as a blind "Undo" button — already shipped in Stage 1 above, scoped to
operations *this plugin issued*. What remains here is genuinely additional:

- `Jujutsu.Redo` (`jj redo` — confirmed to exist as a real top-level jj command, the
  natural counterpart of `jj undo`).
- A `jj undo`-backed action for undoing something that did *not* originate from an in-IDE
  gesture (e.g. a CLI command run in a terminal alongside the IDE) — `Jujutsu.UndoLastOperation`
  only ever reverts operations this plugin issued and identified by token, by design (see
  "What ships instead" above), so it structurally cannot reach a bare terminal `jj` command;
  a `jj undo`-backed action is the deliberate, separate affordance for that case, and must
  read the head operation's description via `jj op log` for its own presentation text,
  independent of `JujutsuUndoService`.
- Repeated undo/redo (`jj undo`/`jj redo` chain naturally; `Jujutsu.UndoLastOperation` does
  not, since it only ever knows about one recorded operation per repo).

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

### Stage 4 — Polish (jj-idea-t0iy)

Extend `.withUndoTracking()` + `.withUndoBalloon(...)` to every mutating jj action across
the plugin, not just Abandon — description edits, squash, split, bookmark actions, etc. By
this point the underlying mechanism (token/identify/revert/balloon) already exists; this
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

- `jj op log`, `jj op revert --help`, `jj op restore --help`, `jj undo --help`, `jj redo
  --help` — verified against `jj 0.44.0`, `0.39.0`, and `0.37.0` (`JjVersion.MINIMUM`) via
  `scripts/jj-install-version.sh`.
- Snapshot ops, `--what repo` scope, and the irreversible-command table above — verified
  empirically in scratch repos: dirtying the working copy before a command to force a
  snapshot op; pushing/fetching/tracking against a real bare-repo remote and reverting the
  resulting operation to observe `Nothing changed.` / desync; `jj config set --repo` leaving
  the op-log head unchanged.
- The undo-token mechanism — verified: `jj --config jj-idea.undo-token=<uuid> abandon -r @`
  followed by `jj op log -T tags` shows `args: jj --config 'jj-idea.undo-token=<uuid>'
  abandon -r @` on both 0.37 and 0.44 (0.44 emits a `tags()` deprecation hint to *stderr*
  only, so it doesn't affect parsing stdout).
- `self.parents()`, `self.attributes()` (0.44 only — absent on 0.39, hence `tags` in
  `OP_LOG_TEMPLATE`), `time`, `user`, `root`, `snapshot` on an operation-log template — all
  verified working, e.g. `jj op log -n 3 --no-graph -T 'id.short() ++ " | parents: " ++
  self.parents().map(|p| p.id().short()).join(",") ++ " | " ++ self.description()'`.
- `PartialLocalLineStatusTracker` — `platform/vcs-impl/src/com/intellij/openapi/vcs/ex/`
  in intellij-community, the sole platform-wide `UndoableAction` consumer in a VCS context.
- `git4idea` source tree — no `UndoManager` reference found by full-tree grep.
- `Vcs.MainMenu` (`VcsMainMenuActionGroup`, `platform/vcs-impl/.../VcsMainMenuActionGroup.kt`
  in intellij-community) — visible whenever the project has a single active VCS without a
  custom menu, regardless of which VCS. `Jujutsu.UndoLastOperation` doesn't add its own
  Jujutsu-specific gate on top: `JujutsuUndoService` can only ever hold a record after a
  Jujutsu action has actually succeeded, so on a non-Jujutsu (or jj-idle) project the action
  is simply always disabled with the generic "nothing to undo" text — visible but inert,
  same outcome as an explicit check would give, with less code.
