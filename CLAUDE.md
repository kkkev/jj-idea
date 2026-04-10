# Jujutsu VCS IntelliJ IDEA Plugin - Development Guide

This document captures essential architecture, implementation decisions, and development instructions for the Jujutsu VCS plugin for IntelliJ IDEA.

## Project Overview

**Goal**: Create a native IntelliJ IDEA plugin for Jujutsu (jj) version control system that implements JJ's unique "describe-first" workflow.

**Technology Stack**:
- Language: Kotlin
- Build System: Gradle with IntelliJ Platform Gradle Plugin 2.0
- Target Platform: IntelliJ IDEA 2025.2+
- Java Version: 21
- VCS Integration: CLI-based with interface abstraction for future library support

## Product Direction & Prioritization Strategy

### Vision for 1.0 Release

**Target Audience**: JJ CLI power users
- Users already comfortable with JJ command-line interface
- Seeking IDE integration for convenience and visual feedback
- Familiar with JJ concepts and workflows

**Key Differentiator**: Best-in-class support for JJ's unique "describe-first" workflow
- Emphasize continuous commit model where working copy is always a commit
- Streamline the describe → change → new cycle
- Native IntelliJ integration that feels like a first-class VCS

**Critical Success Factors for 1.0**:
1. **Core Operations**: Essential JJ operations work flawlessly (edit, abandon, describe, new)
2. **UI Polish**: Professional, consistent UI with proper icons and theming
3. **Stability**: Comprehensive testing, graceful error handling, no crashes
4. **Discoverability**: Clear documentation, marketing materials, keyboard shortcuts

### Using the Priority System

```bash
bd list --status=open | grep '\[P0\]'  # View release blockers
bd ready                                # View ready work
bd stats                                # Project statistics
```

**Triage Guidelines**:
- **P0**: Blocks 1.0 release (crashes, data loss, missing essential features, marketplace requirements)
- **P1**: Important for quality 1.0 (performance, polish, testing, discoverability)
- **P2**: Enhances experience but not blocking (nice-to-have features, advanced UI)
- **P3**: Minor improvements (visual tweaks, edge cases)
- **P4**: Future releases (advanced operations, major new features)

## Developer Guidelines
See [here](contributing.md).

## Architecture

### Core Components

#### VCS Integration Layer
```
JujutsuVcs (extends AbstractVcs)
├── root: VirtualFile - Auto-discovers .jj directory
├── JujutsuChangeProvider - Detects file changes via `jj status`
├── JujutsuDiffProvider - Provides diff content
├── BulkFileListener - Auto-refresh on file system changes
└── JujutsuCommandExecutor (interface)
    └── JujutsuCliExecutor(root) - CLI implementation
```

#### Three-Tier VCS Access Pattern

**Critical for proper error handling**. Each method is on `Project` and `VirtualFile`:

1. **`possibleJujutsuVcs`** → `JujutsuVcs?`
   - Use in action `update()` to enable/disable actions
   - Use in helper methods that check if VCS exists

2. **`getVcsWithUserErrorHandling(actionName, logger)`** → `JujutsuVcs?`
   - Use in **user-facing actions** (context menus, toolbar)
   - Shows friendly error dialog if VCS not configured
   - Logs at INFO level (user error)
   ```kotlin
   ApplicationManager.getApplication().executeOnPooledThread {
       val vcs = JujutsuVcs.getVcsWithUserErrorHandling(project, "Action Name", log)
           ?: return@executeOnPooledThread
       // ... use vcs
   }
   ```

3. **`jujutsuVcs`** → `JujutsuVcs` (throws `VcsException`)
   - Use when VCS **MUST** exist (plugin error if doesn't)
   - Use in: tool windows, VCS providers, log panels
   - Logs at ERROR level (programming error)

**Logging Strategy**:
- **INFO level**: User errors (VCS not configured, user tried Jujutsu action outside Jujutsu project)
  - Expected conditions that don't indicate plugin bugs
  - User needs to take action (configure VCS, open correct project)
  - Used by `getVcsWithUserErrorHandling()`
- **ERROR level**: Plugin errors (VCS should exist but doesn't, programming errors, unexpected state)
  - Indicates bugs or unexpected internal state
  - Should not happen during normal operation
  - Used when catching `VcsException` from `jujutsuVcs`

**Threading**:
- Always call VCS lookup methods in **background threads** to avoid EDT slow operations
- VCS lookup performs file system checks and can block
- Use `ApplicationManager.getApplication().executeOnPooledThread { }`

**See**: `vcs/JujutsuVcs.kt` companion object

#### Command Executor Pattern
- **Interface**: `JujutsuCommandExecutor` - Abstraction for all jj commands
- **Implementation**: `JujutsuCliExecutor` - CLI-based execution
- **Rationale**: Allows future replacement with native library

**See**: `jj/CommandExecutor.kt`, `jj/cli/CliExecutor.kt`

### Key Design Decisions

#### 1. Log Parsing with Null Byte Separator
Use null byte (`\0`) as field separator in `jj log` template output. Commit descriptions can contain newlines and special characters. Use `++` concatenation (not `separate()` which skips empty values).

**See**: `jj/cli/CliLogService.kt`

#### 2. TextCanvas Pattern for Consistent Rendering
Use `TextCanvas` interface with extension functions for consistent formatting:
```kotlin
interface TextCanvas {
    fun append(text: String, style: SimpleTextAttributes)
}
```

Extension functions: `append(changeId)`, `append(description)`, `append(instant)`, `append(bookmarks)`, etc.

**Rationale**: Single source of truth for formatting, type-safe, consistent across plugin

**See**: `ui/TextCanvas.kt`, `ui/DateTimeFormatter.kt`, `ui/Formatters.kt`, `ui/JujutsuColors.kt`

#### 3. Settings Architecture — Three-Tier Model

Settings use three tiers with fallback resolution: **repo override → project default → global default**.

| Tier | Service | Storage | Settings |
|------|---------|---------|----------|
| **Global** (app-level) | `JujutsuApplicationSettings` | `~/Library/.../jujutsu.xml` | `jjExecutablePath` |
| **Project** | `JujutsuSettings` | `.idea/jujutsu.xml` | UI prefs, log defaults |
| **Repository** | `RepositoryConfig` in project state | `.idea/jujutsu.xml` (map keyed by repo path) | `logChangeLimit` overrides |

**Global tier** (`JujutsuApplicationSettings`): `@Service(Service.Level.APP)` with `RoamingType.DISABLED`. Machine-specific settings shared across all projects. Currently only `jjExecutablePath`.

**Project tier** (`JujutsuSettings`): `@Service(Service.Level.PROJECT)`. UI preferences and defaults for repo-level settings.

**Repository tier** (`RepositoryConfig`): Stored in `JujutsuSettingsState.repositoryOverrides` map, keyed by `repo.directory.path`. Nullable fields — `null` means "use project default". Access via `settings.logChangeLimit(repo)`.

**Adding new settings**:
- Global (machine-specific): Add field to `JujutsuApplicationSettingsState`
- Project (UI pref): Add field to `JujutsuSettingsState`
- Per-repo (overridable): Add nullable field to `RepositoryConfig`, add accessor to `JujutsuSettings`

**Migration**: `loadState()` handles version upgrades. v2 migrated `jjExecutablePath` from project to global (first project with custom path wins).

**See**: `settings/` package

#### 4. Auto-Refresh with BulkFileListener
Modern IntelliJ Platform API for file change detection. Processes changes in batches, automatic disposal through message bus.

**See**: `JujutsuVcs.kt`

#### 5. Auto-Open Custom Log on Startup
`PostStartupActivity` automatically opens custom log tab and suppresses default VCS log tabs via `ContentManagerListener`.

**See**: `JujutsuStartupActivity.kt`

#### 6. Reusing Actions in Custom Context Menus via uiDataSnapshot
When adding context menus to custom tree components (like `JujutsuChangesTree`), **do not** duplicate action logic or create static helpers. Instead:

1. **Override `uiDataSnapshot`** on the tree component to populate the data context with standard keys:
   ```kotlin
   override fun uiDataSnapshot(sink: DataSink) {
       super.uiDataSnapshot(sink)
       sink[VcsDataKeys.CHANGES] = selectedChanges.toTypedArray()
       sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = selectedChanges.mapNotNull { it.virtualFile }.toTypedArray()
   }
   ```

2. **Look up registered actions** via `ActionManager` and add them to the popup group:
   ```kotlin
   actionManager.getAction("Jujutsu.RestoreFile")?.let { group.add(it) }
   ```

3. **Set the tree as the target component** so the action's data context flows through `uiDataSnapshot`:
   ```kotlin
   val popupMenu = actionManager.createActionPopupMenu(ActionPlaces.CHANGES_VIEW_POPUP, group)
   popupMenu.setTargetComponent(changesTree)
   ```

4. **Actions use `AnActionEvent` extension properties** (in `ActionEventExtensions.kt`) that read from these standard keys:
   ```kotlin
   val AnActionEvent.files: List<VirtualFile>
       get() = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
           ?: changes.map { it.afterRevision?.file ?: it.beforeRevision?.file }
               .mapNotNull { it?.virtualFile }
   ```

**Rationale**: Any action that works on files/changes (restore, compare, etc.) automatically works in any context where the tree provides data — no duplication, no companion object helpers, no local action wrappers.

**Anti-pattern**: Don't create companion objects with static helpers like `restoreToParent()` or wrap registered actions in local `DumbAwareAction` subclasses that capture state. This duplicates logic and bypasses the platform's data context mechanism.

**See**: `ui/JujutsuChangesTree.kt`, `vcs/actions/ActionEventExtensions.kt`, `ui/workingcopy/UnifiedWorkingCopyPanel.kt`

#### 7. Action Factory Pattern for VCS Operations
VCS actions (edit, abandon, describe, etc.) use factory functions that return `AnAction` instances:

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

**Key Components**:

1. **Helper Functions** (`NullAndDumbAwareAction.kt`):
   - `nullAndDumbAwareAction(target, messageKey, icon) { }` - For single nullable target
   - `emptyAndDumbAwareAction(targets, messageKey, icon) { }` - For list of targets
   - Auto-disables action when target is null/empty
   - Provides `ActionContext<T>` with `target`, `event`, and `log`

2. **Command Builder** (`CommandExecutor.kt`) - **Always use this from actions**:
   - `commandExecutor.createCommand { edit(target) }` - Creates command
   - `.onSuccess { stdout -> }` - Handle success (called on EDT)
   - `.onFailureTellUser(resourceKeyPrefix, project, log)` - Show error dialog
   - `.executeAsync()` - Execute command on background thread, callbacks on EDT
   - Handles all thread switching automatically - no manual `executeOnPooledThread` needed

3. **Message Key Conventions** (`JujutsuBundle.properties`):
   - Action: `log.action.<name>` (display text), `log.action.<name>.tooltip`
   - Errors: `log.action.<name>.error.title`, `log.action.<name>.error.message`
   - Dialogs: `dialog.<name>.input.message`, `dialog.<name>.input.title`

4. **Input Dialogs** (`Inputs.kt`):
   - `project.requestDescription(resourceKeyPrefix, initial)` - Multiline input dialog
   - Returns `Description?` (null if cancelled)

5. **Refresh After Operations** (`ChangeActions.kt`):
   - `project.refreshAfterVcsOperation(selectWorkingCopy = true|false)`
   - Invalidates state model, marks VCS dirty

**File Organization**: One file per action in `vcs/actions/` with lowercase filename and `Action` suffix (e.g., `editChangeAction.kt`).

**See**: `vcs/actions/` package

#### 8. Custom Log Architecture
The custom log replaces IntelliJ's built-in VCS log entirely and is built from `JBTable`, `AbstractTableModel`, and custom `TableCellRenderer` implementations.

**Component Hierarchy**:
```
JujutsuCustomLogTabManager (@Service — tab lifecycle, singleton Content in ChangesViewContentManager)
└── UnifiedJujutsuLogPanel (multi-root) or JujutsuLogPanel (single-root)
    ├── Toolbar (filters: search, root, reference, author, date; column menu)
    ├── OnePixelSplitter
    │   ├── JujutsuLogTable (JBTable + JujutsuLogTableModel)
    │   └── JujutsuCommitDetailsPanel
    └── JujutsuColumnManager (visibility + inlining state)
```
The two panel classes are parallel implementations (not related by inheritance) sharing sub-components. `JujutsuLogPanel` adds a Paths filter; `UnifiedJujutsuLogPanel` adds a root filter and root gutter column.

**Data Flow**:
```
jj log (CLI) → CliLogService (template + null-byte parse) → List<LogEntry>
  → DataLoader (background thread) → CommitGraphBuilder → LayoutCalculatorImpl
  → EDT: tableModel.setEntries() + table.updateGraph(graphNodes)
```
Graph layout is computed alongside data load (not on demand). `LayoutCalculatorImpl` uses a two-pass `foldIndexed` over entries: pass 1 assigns lanes and tracks parent-child relationships; pass 2 builds `RowLayout` objects. Lane assignment prefers staying in a child's lane for continuity.

**Template-Based Log Parsing** (`CliLogService.kt`):
The `LogSpec<T>` interface forms a composable parser algebra:
- `SingleField<T>` — one null-byte-terminated field with a typed parser
- `MultipleFields<T>` / `LogTemplate<T>` — composite specs that nest other specs

Templates compose: `fullLogTemplate` contains `basicLogTemplate` as a sub-field, calling `basicLogTemplate.take(it)` first then extending with `copy(author=..., committer=...)`. The `LogFields` factory provides typed constructors (`stringField`, `booleanField`, `timestampField`, `SignatureFields`).

Uses `++` concatenation (not `separate()`) in JJ templates because `separate()` skips empty values, which would misalign field positions. Null byte separator handles descriptions with newlines/special characters.

**Multi-Repository Support**:
`UnifiedJujutsuLogDataLoader` loads all repos in parallel via `CountDownLatch(repos.size)` + `ConcurrentHashMap`. Results merge by timestamp: `flatten().sortedByDescending { authorTimestamp ?: committerTimestamp }`. Every `LogEntry` carries its `JujutsuRepository` reference; selection matching in multi-root mode requires both `repo` and `revision` to prevent cross-repo mismatches. The root gutter column and root filter auto-hide for single-root projects.

**State Management & Reactive Refresh** (`JujutsuStateModel.kt`, `Messaging.kt`):
`JujutsuStateModel` (project-level `@Service`) is the central event hub. All IDE event subscriptions
(VFS changes, VCS configuration, VCS activation) live here. It provides:
1. `initializedRoots: NotifiableState<Set<JujutsuRepository>>` — cached roots with `.jj` dirs
2. `repositoryStates: NotifiableState<Set<LogEntry>>` — working copy entries per root, cascades from `initializedRoots`
3. `logRefresh: Notifier<Unit>` — fires when log should reload (from exactly 3 non-overlapping sources: `repo.invalidate()`, debounce collector, `initializedRoots` handler)
4. `changeSelection: Notifier<ChangeKey>` — fire-and-forget selection requests

`NotifiableState<T>` wraps `MessageBus` topics: `invalidate()` runs a loader on a pooled thread; if the value changes, publishes on EDT. Does NOT auto-invalidate on construction — callers must call `invalidate()` explicitly. File changes trigger `repositoryStates.invalidate()` with a 300ms debounce via `MutableSharedFlow`. Cascading: `initializedRoots` → `repositoryStates` + `logRefresh` → `VcsDirtyScopeManager.dirDirtyRecursively()`.

**Bootstrap chain**: `JujutsuStartupActivity` → `ToolWindowEnabler.getInstance()` → `JujutsuStateModel` (via `stateModel`) → subscribes to VFS/VCS events, fires initial `initializedRoots.invalidate()` → `ToolWindowEnabler` connects to root state for tool window visibility and log tab suppression.

**Refresh suppression**: `suppressRefresh()`/`resumeRefresh()` bracket batch operations to avoid redundant file-change refreshes. `resumeRefresh()` triggers a refresh when the counter reaches zero.

Both data loaders use a **pending selection** pattern (volatile `pendingSelection` + `hasPendingSelection` flags) to defer selection requests that arrive during background loading, resolving the race between VCS operation completion and refresh data arrival.

**Column Inlining into Graph** (`JujutsuColumnManager.kt`):
When a column (Change ID, Description, Decorations) is hidden as a separate table column, its content is automatically rendered inside the graph column via computed properties:
```kotlin
val showChangeId: Boolean get() = !showChangeIdColumn && showChangeIdInGraph
```
Toggling a separate column ON removes that element from the graph column; toggling it OFF adds it back. `JujutsuGraphAndDescriptionRenderer` reads these flags in `paintComponent()` to conditionally draw change ID, description, and decoration elements after the graph lines.

**See**: `ui/log/` package, `jj/JujutsuStateModel.kt`, `jj/util/Messaging.kt`, `jj/cli/CliLogService.kt`

## File Structure

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
│   ├── actions/                 # VCS operation actions (see patterns §6, §7)
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

## Testing Strategy

**Framework**: JUnit 5 + Kotest assertions + MockK mocks

**Run Tests**:
```bash
./gradlew test  # Unit tests with IntelliJ Platform classes on classpath
```

**Test Coverage**: Core domain types, log parsing, formatters, tree models, annotation parsing

**Writing Tests**:
```kotlin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExampleTest {
    @Test
    fun `descriptive test name`() {
        val result = someFunction()
        result shouldBe expected
    }
}
```

## Building & Running

```bash
./gradlew build          # Build plugin
./gradlew runIde         # Run in IDE for testing
./gradlew buildPlugin    # Package plugin (output: build/distributions/)
```

## Configuration

**plugin.xml**:
```xml
<extensions defaultExtensionNs="com.intellij">
    <vcs name="Jujutsu" vcsClass="in.kkkev.jjidea.JujutsuVcs" />
    <toolWindow id="Jujutsu" anchor="left" ... />
</extensions>
```

**Settings**: Version Control → Jujutsu
- JJ executable path
- Auto-refresh enabled
- Change ID format (short/long)
- Log change limit

## Development Workflow

### Adding a New JJ Command
1. Add method to `CommandExecutor` interface
2. Implement in `CliExecutor`
3. Use from UI or VCS providers

### Adding UI Features
1. Update relevant panel/component
2. Add toolbar actions if needed
3. Wire event handlers
4. Test with `./gradlew runIde`

### Debugging
- Enable debug logging: `Logger.getInstance(MyClass::class.java)`
- Run with: `./gradlew runIde --debug-jvm`
- All jj commands are logged by `CliExecutor`

## Issue Tracking

Use beads for all task management:

```bash
bd list --status=open    # View all open issues
bd ready                 # View issues ready to work on
bd show <issue-id>       # View detailed issue information
bd create                # Create new issue
bd update <id> -p P0     # Update priority
bd close <id>            # Close completed issue
```

## Coding Standards

### Kotlin Style
- **Respect .editorconfig**: Prefer code on a single line where it fits within the width
- **Expression body**: Use expression body for functions where possible (`fun foo() = bar` not `fun foo() { return bar }`)
- **Type inference**: Where function returns unambiguous type and is declared as single-line expression, omit the type
  - Example: `fun foo() = bar` (good) vs `fun foo(): String = bar` (unnecessary)
  - Exception: If expression is result of platform call where nullity is ambiguous, declare type explicitly
- **Single return point**: Avoid multiple return points where possible
  - Instead of early returns, chain calls with `?.let`
  - Example: `vcs?.let { executeOperation(it) }` instead of `if (vcs == null) return; executeOperation(vcs)`
- **`equals` with `when` expression**: Use `when` expression style for `equals` overrides:
  ```kotlin
  override fun equals(other: Any?) = when {
      this === other -> true
      other !is MyClass -> false
      else -> field == other.field
  }
  ```
- **Imports**: Always use imports over fully-qualified symbols
- **Optimize imports**: Always optimize imports (remove unused, organize)

### Error Handling Philosophy
**Never fail silently** - silent failures lead to unexpected outcomes that are difficult to debug. Fail fast and make errors obvious.

Distinguish between **user errors** and **system errors**:

1. **System errors** (coding bugs, internal inconsistencies, invariant violations):
   - Throw exceptions immediately - let them bubble up
   - Use `!!` when something absolutely must exist (e.g., registered action, required service)
   - Use `check()` / `require()` for invariants
   - Example: `ActionManager.getInstance().getAction("Jujutsu.Init")!!` - if this is null, it's a bug

2. **User errors** (misconfiguration, invalid input, missing prerequisites):
   - Show a helpful message explaining what happened
   - Tell the user how to fix it, or better, provide a direct UX to fix it
   - Example: VCS root configured but .jj missing → show notification with "Initialize" and "Configure VCS" buttons
   - Use `JujutsuNotifications` for balloon notifications with action buttons

**Anti-patterns to avoid**:
- `if (x != null) { ... }` with no else clause when null indicates a bug
- `?.let { }` that silently does nothing on null when null is unexpected
- Empty catch blocks
- Logging errors but continuing as if nothing happened

### Git Workflow
- **Push workflow**: This project has two remotes (origin and github) with automated workflows
  - `origin`: Primary GitLab remote at home.marigoldfeathers.com
  - `github`: Public GitHub remote
  - GitHub Actions automatically creates tags and advances master in background

- **Standard push sequence**:
  1. Export beads to JSONL: `bd export -o .beads/issues.jsonl` (jj doesn't trigger git hooks, so beads must be exported manually before pushing)
  2. Push to origin: `jj git push --remote origin`
  3. Fetch from github: `jj git fetch --remote github` (get automated updates)
  4. Merge if needed: `jj new master@github master@origin -m "Merge github and origin master branches"`
  5. Update master bookmark: `jj bookmark set master`
  6. Push to both remotes: `jj git push --remote origin && jj git push --remote github`

- **Why this order**: Beads uses Dolt internally but persists to `issues.jsonl` for version control — since jj bypasses git hooks, always export before pushing. GitHub Actions may have updated master@github with new tags since last push, so always fetch from github before pushing to avoid conflicts

### Task Management
- **Use beads**: Store all tasks in beads issue tracker
  - When referring to "issues" or "tasks", look in beads
  - Use `bd create`, `bd update`, `bd show`, `bd close` commands
  - Never use TodoWrite tool for this project

## Contributing

When adding features or fixing bugs:

1. Track work in beads issue tracker (`bd create`, `bd update`, etc.)
2. Update CLAUDE.md with new architectural decisions
3. Add tests (use Kotest assertions, MockK for mocking)
4. Follow threading rules: I/O on background threads, UI on EDT
5. Follow coding standards above
6. Build and test before committing: `./gradlew build test`

## End of Task Checklist

Before completing a task, run through this checklist:

### 1. Verify Quality
```bash
./gradlew check   # Run tests and linting
```
Fix any failures before proceeding.

### 2. Update Beads
- **Close completed issues**: `bd close <id1> <id2> ...` with comments if non-trivial
- **Create issues for snags**: If something couldn't be fixed in this session, create a bead:
  - `bug` for bugs discovered
  - `task` for refactoring or cleanup needed
  - `feature` for missed functionality

### 3. Update Changelog
Add entries to the `[Unreleased]` section of `CHANGELOG.md`:
- `### Added` - New features
- `### Fixed` - Bug fixes
- `### Changed` - Changes to existing functionality
- `### Removed` - Removed features

### 4. Sync Remotes
```bash
jj git fetch --remote github                    # Get any automated updates
jj new master@github master@origin -m "Merge github and origin master branches"  # If diverged
jj bookmark set master
```

### 5. Export Beads and Push
```bash
bd export -o .beads/issues.jsonl   # Sync beads to version control (jj bypasses git hooks)
jj git push --remote origin
```

### 6. Release Decision
Ask if user wants to release:
- **No release**: Done (code stays on origin only)
- **Release**:
  1. Push to github: `jj git push --remote github`
  2. Go to [GitHub Actions](https://github.com/kkkev/jj-idea/actions) → "Build and Release" → "Run workflow"
  3. Select bump type (major/minor/patch)
  4. Workflow handles: version bump, release creation, changelog update, marketplace publish

## References

### Jujutsu Documentation
- [Official Tutorial](https://jj-vcs.github.io/jj/latest/tutorial/)
- [JJ Best Practices](https://zerowidth.com/2025/jj-tips-and-tricks/)
- [Squash Workflow](https://steveklabnik.github.io/jujutsu-tutorial/real-world-workflows/the-squash-workflow.html)
- [Chris Krycho's JJ Init](https://v5.chriskrycho.com/essays/jj-init)

### IntelliJ Platform
- [Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [VCS Integration](https://plugins.jetbrains.com/docs/intellij/vcs-integration.html)
- [IntelliJ Platform Gradle Plugin 2.0](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)

### Other JJ IDE Plugins
- [Selvejj](https://plugins.jetbrains.com/plugin/28081-selvejj) - IntelliJ
- [VisualJJ](https://www.visualjj.com/) - VSCode
- [Jujutsu Kaizen (jjk)](https://marketplace.visualstudio.com/items?itemName=jjk.jjk) - VSCode

---

**Last Updated**: 2026-01-13
**Plugin Version**: 0.1.0-SNAPSHOT
**IntelliJ Version**: 2025.2
