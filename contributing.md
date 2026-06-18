# Contributing

## Project Overview & Tech Stack

A native IntelliJ IDEA plugin for [Jujutsu](https://jj-vcs.github.io/jj/) (jj) version
control, built around JJ's "describe-first" workflow: the working copy is always a
commit, and the core loop is describe → change → new.

- Language: Kotlin
- Build: Gradle + IntelliJ Platform Gradle Plugin 2.0
- Target Platform: IntelliJ IDEA 2025.2+, Java 21
- VCS integration is CLI-based behind an interface (`JujutsuCommandExecutor`), leaving
  room for a future native-library implementation

See [ROADMAP.md](ROADMAP.md) for the prioritized feature list.

## Vision for 1.0

**Audience**: JJ CLI power users — already comfortable with the jj command line, seeking
IDE convenience and visual feedback without losing JJ's workflow.

**Key differentiator**: best-in-class support for JJ's "describe-first" workflow — the
working copy is always a commit, and the core loop is describe → change → new. The
plugin should feel like a first-class native IntelliJ VCS, not a CLI wrapper.

**Critical success factors**:
1. **Core operations** work flawlessly (edit, abandon, describe, new)
2. **UI polish** — professional, consistent, proper icons and theming
3. **Stability** — comprehensive testing, graceful error handling, no crashes
4. **Discoverability** — clear documentation, marketing materials, keyboard shortcuts

## Architecture

### Core Components

```
JujutsuVcs (extends AbstractVcs)
├── root: VirtualFile - Auto-discovers .jj directory
├── JujutsuChangeProvider - Detects file changes via `jj status`
├── JujutsuDiffProvider - Provides diff content
├── BulkFileListener - Auto-refresh on file system changes
└── JujutsuCommandExecutor (interface)
    └── JujutsuCliExecutor(root) - CLI implementation
```

### Three-Tier VCS Access Pattern

Critical for proper error handling. Each method is on `Project` and `VirtualFile`:

1. **`possibleJujutsuVcs`** → `JujutsuVcs?` — use in action `update()` and helpers that
   merely check whether VCS exists.
2. **`getVcsWithUserErrorHandling(actionName, logger)`** → `JujutsuVcs?` — use in
   user-facing actions (context menus, toolbar). Shows a friendly error dialog if VCS
   isn't configured; logs at INFO (user error), e.g.:
   ```kotlin
   ApplicationManager.getApplication().executeOnPooledThread {
       val vcs = JujutsuVcs.getVcsWithUserErrorHandling(project, "Action Name", log)
           ?: return@executeOnPooledThread
       // ... use vcs
   }
   ```
3. **`jujutsuVcs`** → `JujutsuVcs` (throws `VcsException`) — use when VCS *must* exist
   (tool windows, VCS providers, log panels); a thrown exception here is a plugin bug.
   Logs at ERROR.

**Logging**: INFO = expected user error (VCS not configured, action used outside a
Jujutsu project). ERROR = plugin bug (VCS should exist but doesn't).

**Threading**: always call VCS lookup methods on a background thread
(`ApplicationManager.getApplication().executeOnPooledThread { }`) — lookup does
filesystem checks and can block.

**See**: `vcs/JujutsuVcs.kt` companion object

### Command Executor Pattern

`JujutsuCommandExecutor` is the abstraction for all jj commands; `JujutsuCliExecutor` is
the CLI-based implementation. This indirection is what allows a future native-library
swap. **See**: `jj/CommandExecutor.kt`, `jj/cli/CliExecutor.kt`

### Key Design Decisions

**1. Log parsing with null-byte separator** — `jj log` templates use `\0` as the field
separator (descriptions can contain newlines/special chars) and `++` concatenation, not
`separate()` (which skips empty values and would misalign fields). **See**:
`jj/cli/CliLogService.kt`

**2. TextCanvas pattern for rendering** — a `TextCanvas` interface
(`fun append(text: String, style: SimpleTextAttributes)`) plus typed extension functions
(`append(changeId)`, `append(description)`, `append(bookmarks)`, etc.) is the single
source of truth for formatting, used consistently across renderers. **See**:
`ui/TextCanvas.kt`, `ui/DateTimeFormatter.kt`, `ui/Formatters.kt`, `ui/JujutsuColors.kt`

**3. Settings — three-tier model** — fallback resolution: repo override → project
default → global default.

| Tier | Service | Storage | Settings |
|------|---------|---------|----------|
| Global (app) | `JujutsuApplicationSettings` | `~/Library/.../jujutsu.xml`, `RoamingType.DISABLED` | `jjExecutablePath` |
| Project | `JujutsuSettings` | `.idea/jujutsu.xml` | UI prefs, log defaults |
| Repository | `RepositoryConfig` | `.idea/jujutsu.xml`, map keyed by repo path | `logChangeLimit` overrides (nullable = "use project default") |

Adding a setting: global → field on `JujutsuApplicationSettingsState`; project → field on
`JujutsuSettingsState`; per-repo → nullable field on `RepositoryConfig` + accessor on
`JujutsuSettings`. Version migrations live in `loadState()` (v2 moved `jjExecutablePath`
project→global). **See**: `settings/` package

**4. Auto-refresh via `BulkFileListener`** — batched file-change detection, disposed
through the message bus. **See**: `JujutsuVcs.kt`

**5. Auto-open custom log on startup** — `PostStartupActivity` opens the custom log tab
and suppresses IntelliJ's default VCS log tabs via `ContentManagerListener`. **See**:
`JujutsuStartupActivity.kt`

**6. Reusing actions in custom context menus via `uiDataSnapshot`** — when a custom tree
(e.g. `JujutsuChangesTree`) needs a context menu of existing actions (restore, compare,
etc.), don't duplicate their logic in static helpers or local `DumbAwareAction` wrappers.
Instead: override `uiDataSnapshot` on the tree to populate standard data keys
(`VcsDataKeys.CHANGES`, `CommonDataKeys.VIRTUAL_FILE_ARRAY`), look up the registered
action via `ActionManager`, and set the tree as the popup's target component so the
action's `AnActionEvent` extension properties (in `ActionEventExtensions.kt`) resolve
correctly. This makes any file/change action work unmodified in any tree that supplies
the right data context. **See**: `ui/JujutsuChangesTree.kt`,
`vcs/actions/ActionEventExtensions.kt`, `ui/workingcopy/UnifiedWorkingCopyPanel.kt`

**7. Action factory pattern** — VCS actions are built with factory functions, not
classes:
```kotlin
fun editChangeAction(project: Project, changeId: ChangeId?) =
    nullAndDumbAwareAction(changeId, "log.action.edit", AllIcons.Actions.Edit) {
        project.jujutsuVcs.commandExecutor
            .createCommand { edit(target) }
            .onSuccess { project.refreshAfterVcsOperation(selectWorkingCopy = true) }
            .onFailureTellUser("log.action.edit.error", project, log)
            .executeAsync()
    }
```
- `nullAndDumbAwareAction`/`emptyAndDumbAwareAction` (`NullAndDumbAwareAction.kt`)
  auto-disable on null/empty target and supply an `ActionContext<T>`.
- The command builder (`CommandExecutor.kt`) — `createCommand { }`, `.onSuccess { }`
  (EDT), `.onFailureTellUser(prefix, project, log)`, `.executeAsync()` — handles all
  thread switching; never call `executeOnPooledThread` manually from an action.
- Message keys follow `log.action.<name>[.tooltip]`,
  `log.action.<name>.error.title/.message`, `dialog.<name>.input.message/.title`
  (`JujutsuBundle.properties`).
- `project.requestDescription(prefix, initial)` (`Inputs.kt`) is the multiline input
  dialog helper, returning `Description?`.
- `project.refreshAfterVcsOperation(selectWorkingCopy = true|false)` (`ChangeActions.kt`)
  invalidates state and marks VCS dirty after an operation.
- One file per action in `vcs/actions/`, lowercase filename, `Action` suffix
  (`editChangeAction.kt`).

**8. Custom log architecture** — the custom log fully replaces IntelliJ's built-in VCS
log, built from `JBTable` + `AbstractTableModel` + custom `TableCellRenderer`s.

```
JujutsuCustomLogTabManager (@Service — tab lifecycle, singleton Content)
└── UnifiedJujutsuLogPanel (multi-root) or JujutsuLogPanel (single-root)
    ├── Toolbar (search, root, reference, author, date filters; column menu)
    ├── OnePixelSplitter
    │   ├── JujutsuLogTable (JBTable + JujutsuLogTableModel)
    │   └── JujutsuCommitDetailsPanel
    └── JujutsuColumnManager (visibility + inlining state)
```
The two panels are parallel implementations (no shared inheritance); `JujutsuLogPanel`
adds a Paths filter, `UnifiedJujutsuLogPanel` adds a root filter and root gutter column,
which both auto-hide for single-root projects.

Data flow: `jj log` (CLI) → `CliLogService` (template + null-byte parse) → `List<LogEntry>`
→ `DataLoader` (background) → `CommitGraphBuilder` → `LayoutCalculatorImpl` → EDT:
`tableModel.setEntries()` + `table.updateGraph(graphNodes)`. Graph layout is computed
alongside data load, not on demand — `LayoutCalculatorImpl` does a two-pass
`foldIndexed` (pass 1: lane + parent-child assignment; pass 2: build `RowLayout`s), and
lane assignment prefers staying in a child's lane for continuity.

Template parsing (`CliLogService.kt`) is a composable parser algebra: `LogSpec<T>` →
`SingleField<T>` (one null-byte field) or `MultipleFields<T>`/`LogTemplate<T>` (nested
specs). Templates compose by `take()`-ing a sub-template then extending it (e.g.
`fullLogTemplate` wraps `basicLogTemplate`). `LogFields` provides typed constructors
(`stringField`, `booleanField`, `timestampField`, `SignatureFields`).

Multi-repo: `UnifiedJujutsuLogDataLoader` loads all repos in parallel
(`CountDownLatch` + `ConcurrentHashMap`), merging by
`flatten().sortedByDescending { authorTimestamp ?: committerTimestamp }`. Selection
matching requires both `repo` and `revision` to avoid cross-repo collisions.

State/refresh (`JujutsuStateModel.kt`, `Messaging.kt`): `JujutsuStateModel` is the
project-level event hub for all VFS/VCS subscriptions, exposing
`initializedRoots`/`repositoryStates` (`NotifiableState<T>`, wraps `MessageBus`,
`invalidate()` runs on a pooled thread and publishes on EDT only if changed — never
auto-invalidates on construction), `logRefresh` (`Notifier<Unit>`, fed by exactly 3
non-overlapping sources), and `changeSelection` (fire-and-forget). File changes debounce
through a 300ms `MutableSharedFlow`. Bootstrap: `JujutsuStartupActivity` →
`ToolWindowEnabler` → `JujutsuStateModel` subscribes and fires initial
`initializedRoots.invalidate()`. `suppressRefresh()`/`resumeRefresh()` bracket batch
operations to avoid redundant refreshes. Both data loaders use a pending-selection
pattern (volatile `pendingSelection`/`hasPendingSelection`) to resolve the race between
VCS-operation completion and refresh data arrival.

Column inlining (`JujutsuColumnManager.kt`): hiding a column (Change ID, Description,
Decorations) moves its content into the graph column via computed flags like
`val showChangeId: Boolean get() = !showChangeIdColumn && showChangeIdInGraph`, read by
`JujutsuGraphAndDescriptionRenderer.paintComponent()`.

**See**: `ui/log/` package, `jj/JujutsuStateModel.kt`, `jj/util/Messaging.kt`,
`jj/cli/CliLogService.kt`

### File Structure

```
src/main/kotlin/in/kkkev/jjidea/
├── jj/                          # Core JJ domain types
│   ├── ChangeId.kt              # JJ change identifier
│   ├── FileChange.kt            # File change DTO
│   ├── LogEntry.kt              # Parsed log entry
│   ├── JujutsuCommandExecutor.kt
│   ├── LogService.kt
│   └── cli/
│       ├── CliExecutor.kt
│       ├── CliLogService.kt
│       └── AnnotationParser.kt
├── vcs/                         # IntelliJ VCS integration
│   ├── JujutsuVcs.kt
│   ├── actions/                 # VCS operation actions (see Key Design Decisions §6, §7)
│   ├── changes/
│   ├── diff/
│   └── history/
├── settings/                    # Plugin settings
│   ├── JujutsuSettings.kt
│   ├── JujutsuSettingsState.kt
│   └── JujutsuConfigurable.kt
└── ui/                          # User interface
    ├── JujutsuToolWindowPanel.kt
    ├── TextCanvas.kt
    ├── DateTimeFormatter.kt
    ├── Formatters.kt
    └── log/                     # Custom VCS log
        ├── JujutsuLogPanel.kt
        ├── JujutsuLogTable.kt
        ├── JujutsuCommitGraph.kt
        └── JujutsuLogContextMenuActions.kt
```

## JJ Commands Used

| Command | Purpose | Implementation |
|---------|---------|----------------|
| `jj status` | List working copy changes | `JujutsuChangeProvider` |
| `jj describe -r @ -m "msg"` | Set working copy description | "Describe" button |
| `jj new [revision]` | Create new change | "New Change" button, context menu |
| `jj log -r @ --no-graph -T template` | Get log entries | `CliLogService` |
| `jj file show -r @ path` | Get file content at revision | `JujutsuContentRevision` |
| `jj file annotate -r @ -T template path` | Line-by-line attribution | `JujutsuAnnotationProvider` |
| `jj bookmark list` | List all bookmarks | Compare actions |
| `jj diff path` | Show diff for file | `JujutsuDiffProvider` |
| `jj --version` | Check availability | `isAvailable()` |

## Coding Standards

### Kotlin Style

- Respect `.editorconfig`: prefer code on one line where it fits.
- Use expression bodies where possible (`fun foo() = bar`, not `fun foo() { return bar }`).
- Omit the return type when it's an unambiguous single-line expression
  (`fun foo() = bar`, not `fun foo(): String = bar`) — except when the expression is a
  platform call with ambiguous nullity, where the type should be explicit.
- Avoid multiple return points: prefer chaining with `?.let` over early returns
  (`vcs?.let { executeOperation(it) }` instead of `if (vcs == null) return; executeOperation(vcs)`).
- `equals` overrides use `when` expression style:
  ```kotlin
  override fun equals(other: Any?) = when {
      this === other -> true
      other !is MyClass -> false
      else -> field == other.field
  }
  ```
- Always import symbols rather than fully-qualifying them, and keep imports optimized.

### Error Handling Philosophy

Never fail silently — silent failures are hard to debug. Distinguish:

1. **System errors** (bugs, invariant violations) — throw immediately, let them bubble
   up. Use `!!` when something must exist (registered action, required service), and
   `check()`/`require()` for invariants. E.g.
   `ActionManager.getInstance().getAction("Jujutsu.Init")!!` — null here is a bug.
2. **User errors** (misconfiguration, invalid input, missing prerequisites) — show a
   helpful message and, ideally, a direct fix UX (e.g. VCS root configured but `.jj`
   missing → notification with "Initialize"/"Configure VCS" buttons via
   `JujutsuNotifications`).

Anti-patterns: `if (x != null) { ... }` with no else when null means a bug; `?.let { }`
that silently no-ops on an unexpected null; empty catch blocks; logging an error and
continuing as if nothing happened.

## Testing Strategy

**Prefer automated tests.** The manual checklist (`docs/manual-tests.md`) is a fallback
for surfaces automation can't reach — not a substitute for tests that can be written.

**Framework**: JUnit 5 + Kotest assertions + MockK.
```bash
./gradlew test  # Unit tests with IntelliJ Platform classes on classpath
```
```kotlin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExampleTest {
    @Test
    fun `descriptive test name`() {
        someFunction() shouldBe expected
    }
}
```
Covered today: core domain types, log parsing, formatters, tree models, annotation
parsing.

### Automate vs. manual

- **Automate**: parser round-trips, data model invariants, template field changes,
  argument-building functions — no UI dependency, cheap regression coverage. See
  `LogTemplateTest.kt`, `CliExecutorBookmarkTest.kt`, `JujutsuCompareWithPopupTest.kt`,
  `LogServiceIntegrationTest.kt` for patterns.
- **Manual** (via `./gradlew runIde`): anything affecting visible rendering — cell
  renderers, TextCanvas output, icon placement, tooltip text, context menus, dialog
  content, keyboard shortcuts. Automated tests can't catch these.
- After every implementation, state the exact manual smoke steps for that specific
  change so it can be verified without guessing — and **update
  [`docs/manual-tests.md`](docs/manual-tests.md)** if it adds or changes a manual-testable
  surface. That checklist is the canonical pre-release regression list; it only stays
  useful if every PR that touches a manual surface updates it.

### Manual test patterns

- **Rendering surfaces**: bookmark/status decorations appear in three places —
  the Decorations column (`SeparateDecorationsCellRenderer`), the inlined graph column
  (`JujutsuGraphAndDescriptionRenderer`), and the commit details panel (`HtmlTextCanvas`
  via `appendSummaryAndStatuses`). Check all three when changing bookmark rendering.
- **Log state changes**: use `jj bookmark create/delete/set/track` in the sandbox repo,
  trigger a refresh (file save or toolbar button), verify the log updates (~300ms
  auto-refresh debounce).
- **Popup filter correctness**: `RevisionSelectorPopup` ("Compare with Another Commit…")
  must only show bookmarks that are valid revision targets — verify unusual states
  (deleted, conflict) are filtered or displayed correctly.
- **Tooltip content**: hover icons/decorated names, confirm text matches
  `JujutsuBundle.properties` keys. `HtmlTextCanvas` tooltips render Swing HTML — check
  for tag mismatches when editing it.

## Building & Running

```bash
./gradlew build          # Build plugin
./gradlew runIde         # Run in IDE for testing
./gradlew buildPlugin    # Package plugin (output: build/distributions/)
./gradlew check          # Tests + linting
```

**Adding a new JJ command**: add a method to the `CommandExecutor` interface, implement
in `CliExecutor`, use from UI/VCS providers.

**Adding UI features**: update the relevant panel/component, add toolbar actions, wire
event handlers, verify with `./gradlew runIde`.

**Debugging**: `Logger.getInstance(MyClass::class.java)` for debug logging,
`./gradlew runIde --debug-jvm` to attach a debugger. All jj invocations are logged by
`CliExecutor`.

### Configuration

`plugin.xml`:
```xml
<extensions defaultExtensionNs="com.intellij">
    <vcs name="Jujutsu" vcsClass="in.kkkev.jjidea.JujutsuVcs" />
    <toolWindow id="Jujutsu" anchor="left" ... />
</extensions>
```

Settings live under **Version Control → Jujutsu**: JJ executable path, auto-refresh,
change ID format (short/long), log change limit. See Settings — three-tier model above
for how these are stored and overridden.

### Performance Logging

Hot paths emit structured perf lines to `idea.log` via `util/Perf.kt::measurePerf`:
```
perf: ignore-scan took 41000ms [visited=1,843,201, ignored=12] (myrepo)
perf: log-load took 320ms [entries=5000] (myrepo:∞)
perf: graph-layout took 85ms [rows=5000]
```
INFO for normal operation; WARN when duration > 500ms or any count > 50,000
(`PERF_DURATION_WARN_MS`/`PERF_COUNT_WARN` in `util/Perf.kt`; runtime overrides planned
under jj-idea-edjs.7). A WARN line in a user-submitted log — especially a large `visited`
count — is a strong scale-regression signal (GitHub #35 hit 1.8M entries). Grep for
`perf:` in `idea.log` to find all instrumented operations.

## Performance & Scale

### Scale envelope

The plugin must stay responsive at: ~1M working-tree files, ~500k ignored files
(node_modules/build-output style trees), ~100k commits in the log, ~1k-change working
sets, and multi-root projects (work multiplies per root).

### Refresh-path rules

1. **Nothing O(repo-size) on the refresh path — only O(working-set).** Ignored-file
   computation is decoupled from `JujutsuChangeProvider.getChanges` into the async
   `VcsManagedFilesHolder` (see `JujutsuIgnoredFilesService`); refresh latency must stay
   independent of ignored-tree size.
2. **Prune ignored directories before descending.** At each `onEnter`, report an ignored
   directory as a single entry and don't recurse — a child can't be re-included under an
   excluded parent (git semantics), so pruning eliminates O(ignored-tree) visits entirely.
3. **Every file/commit loop must be cancellable.** Call `progress.checkCanceled()` per
   directory or batch — the missing check in GitHub #35 caused a 10s shutdown hang.
4. **Never assume a colocated git repo.** Most large repos aren't colocated, especially
   in jj workspaces. Pruned + cancellable JVM tree traversal (as in
   `GitignoreCache.collectIgnored`) is the standard path, not a fallback — don't write a
   git-delegating fast path with a broken slow path for the non-colocated case.
5. **Listener callbacks must be O(1)/debounced.** `BulkFileListener`/`ChangeListListener`
   callbacks must do constant work and post to a debounced background task — never fan
   out inline per event (`repositoryStates.invalidate()` uses a 300ms
   `MutableSharedFlow` debounce).

### PR requirement — scale analysis as a deliverable

Any change adding a filesystem traversal or a per-file/per-commit loop must:

**(a)** state its complexity against the scale envelope above in the PR/implementation
summary — e.g. "visits O(non-ignored entries), O(1) per VFS event."

**(b)** ship an operation-count test that injects a counting collaborator and asserts on
work performed, not wall-clock time (CI-flaky). See "Writing a scale test" below for the
pattern and exemplars.

Ask this question on any review touching file traversal, log parsing, change-provider
logic, or VFS listener fan-out:

> Does this change do work that grows with any scale dimension — total files in the
> working copy, ignored files, commits in the log, changes in the working set, or number
> of roots? If yes, what bounds it, and where is the scale test?

A `perf:` WARN line in a user-submitted `idea.log` (see Performance Logging above) is the
runtime signal that this has regressed — the `visited` count is the key indicator.

### Writing a scale test

Operation-count tests are deterministic and PR-blocking (unlike wall-clock timing, which
is CI-flaky and only ever advisory). The recipe, generalised from the ignore-scan fix
(GitHub #35, jj-idea-2570.1):

1. **Inject a counting collaborator, or return a work-count value.** Either give the hot
   method a narrow interface seam for its expensive collaborator (e.g. a filesystem
   walker) and feed it an in-memory fake that increments a counter per call, or — when
   the algorithm is pure in-memory already — have it return/expose a `Long` work count
   (e.g. an `operationCount` field) alongside its normal result.
2. **Assert on the count, never on elapsed time.** A correct-but-quadratic regression
   often produces *identical output* — only a work-count assertion catches it. Pick a
   bound that a quadratic regression would clearly blow through at the chosen N (e.g.
   `< 5*N` when N²/2 would be orders of magnitude larger).
3. **Use synthetic in-memory structures, no real repos**, sized so the test runs in
   <1s and lives in the default `test` task (not `platformTest`/`contractTest`).
4. **Add output-equivalence and cancellation tests where applicable** — pin the fast
   path's output against a brute-force baseline, and confirm a `checkCanceled` callback
   aborts promptly rather than exhausting the input.

Exemplars:
- `vcs/ignore/GitignoreScanTest.kt` — injected fake (`TrackingNode`) + work-count return
  (`ScanStats`) + output-equivalence + cancellation; the canonical example.
- `jj/RepoLogCacheScaleTest.kt` — injected counting collaborator (a mockk `LogService`);
  asserts a bulk load issues one log call (not one per entry) and that incremental
  `store()` stays correctly ordered/deduplicated at scale.
- `ui/log/graph/GraphLayoutScaleTest.kt` — work-count return (`LayoutCalculatorImpl
  .operationCount`); asserts graph layout stays linear (not quadratic) for both a linear
  chain and a wide DAG with bounded passthrough width.

## Git Workflow

Two remotes: `origin` (primary, GitLab) and `github` (public). GitHub Actions
automatically creates tags and advances `master` on `github` in the background, so
always fetch from it before pushing.

Standard push sequence:
1. `bd export -o .beads/issues.jsonl` — jj bypasses git hooks, so beads must be exported
   manually before pushing.
2. `jj git push --remote origin`
3. `jj git fetch --remote github` — pick up automated updates.
4. If diverged: `jj new master@github master@origin -m "Merge github and origin master branches"`
5. `jj bookmark set master`
6. `jj git push --remote origin && jj git push --remote github`

## Release Process

Releases are cut via the GitHub Actions **"Build and Release"** workflow
(`.github/workflows/build.yml`), triggered manually:

```bash
gh workflow run "Build and Release" --repo kkkev/jj-idea -f bump=patch
```

`bump` is `patch`/`minor`/`major` (empty = snapshot build, the default on every push).
The workflow bumps the version, moves `[Unreleased]` changelog content to the new
version section, creates the GitHub release, and publishes to the JetBrains Marketplace.

## Contributing Changes

When making changes that affect users (features, fixes, behavior changes):

1. Add an entry to the `[Unreleased]` section of `CHANGELOG.md`, under **Added**,
   **Fixed**, **Changed**, or **Removed**. Keep it user-facing — no implementation detail.
   Never refer to beads; refer to Github issues if necessary.
2. CI fails if source code changes without a changelog update. For internal-only changes
   (refactoring, tests, docs), add `[skip changelog]` to the commit message.

## End of Task Checklist

1. **Verify quality**: `./gradlew check`. Fix failures before proceeding.
2. **Update beads**: close completed issues (`bd close <id1> <id2> ...`, with a comment
   if non-trivial); file new issues (`bug`/`task`/`feature`) for anything left unresolved.
3. **Manual verification**: state the exact `./gradlew runIde` smoke steps for any
   UI-affecting change, and update `docs/manual-tests.md` if it adds/changes a
   manual-testable surface. For parser/model-only changes, confirm automated tests cover
   it and say so explicitly.
4. **Update `CHANGELOG.md`** per Contributing Changes above.
5. **Sync remotes and push** per Git Workflow above.
6. **Release decision**: ask whether to release. If yes, run the GitHub Actions
   "Build and Release" workflow per Release Process above; if no, the code stays on
   `origin` only.

## Task Tracking

Tasks (features, bugs, etc.) are managed in [Beads](https://github.com/steveyegge/beads).

```bash
bd list --status=open    # All open issues
bd ready                 # Issues ready to work on
bd show <issue-id>       # Detailed issue view
bd create                # New issue
bd update <id> -p P0     # Update priority
bd close <id>            # Complete an issue
```

**Priority levels** (see [ROADMAP.md](ROADMAP.md) for the 1.0 vision these support):

- **P0**: blocks 1.0 release (crashes, data loss, missing essential features, marketplace
  requirements)
- **P1**: important for 1.0 quality (performance, polish, testing, discoverability)
- **P2**: enhances experience, not blocking (nice-to-have, advanced UI)
- **P3**: minor (visual tweaks, edge cases)
- **P4**: future releases (advanced operations, major new features)

## Terminology

- A **root** is an IDEA VCS root — a folder in the project with its own VCS
  configuration (IDEA's VCS/Directory Mappings). A Jujutsu root points at the same
  directory as a Jujutsu repository.
- A **repository** is a Jujutsu repository — the top-level folder of a
  Jujutsu-controlled directory tree.
- A repository is **initialised** if it contains a `.jj` directory.

## References

**Jujutsu**: [Official Tutorial](https://jj-vcs.github.io/jj/latest/tutorial/) ·
[JJ Best Practices](https://zerowidth.com/2025/jj-tips-and-tricks/) ·
[Squash Workflow](https://steveklabnik.github.io/jujutsu-tutorial/real-world-workflows/the-squash-workflow.html) ·
[Chris Krycho's JJ Init](https://v5.chriskrycho.com/essays/jj-init)

**IntelliJ Platform**: [Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) ·
[VCS Integration](https://plugins.jetbrains.com/docs/intellij/vcs-integration.html) ·
[IntelliJ Platform Gradle Plugin 2.0](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)

**Other JJ IDE plugins**: [Selvejj](https://plugins.jetbrains.com/plugin/28081-selvejj)
(IntelliJ) · [VisualJJ](https://www.visualjj.com/) (VSCode) ·
[Jujutsu Kaizen (jjk)](https://marketplace.visualstudio.com/items?itemName=jjk.jjk) (VSCode)
