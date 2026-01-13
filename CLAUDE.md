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

## Implementation Phases & Progress

### Phase 1: MVP - Basic Read-Only Features ✅
1. ✅ Basic VCS integration similar to Git plugin
2. ✅ View working copy status (tree view)
3. ✅ Show diffs for changed files
4. ✅ CLI-based command execution with interface abstraction
5. ✅ Change detection and file status coloring

### Phase 2: JJ-Native Workflow ✅
6. ✅ Tool window on LEFT side (not bottom) like Git Commit view
7. ✅ Describe-first workflow support (Description text area, "Describe" button → `jj describe`, "New Change" button → `jj new`)
8. ✅ Load current description from `jj log`
9. ✅ Continuous commit workflow (working copy is always a commit)

### Phase 3: Enhanced UI ✅
10. ✅ Tree structure with "Changes" root node
11. ✅ Group by directory with toggle
12. ✅ File counts in grouped nodes (e.g., "src (3 files)")
13. ✅ Expand All / Collapse All buttons
14. ✅ Refresh button
15. ✅ Files colored by status (Blue: Modified, Green: Added, Gray: Deleted)
16. ✅ File type icons
17. ✅ Click to show diff, Double-click/F4/Enter to open file
18. ✅ Right-click context menu

### Phase 4: Diff View Improvements ✅
19. ✅ Show actual file content (before vs after, not identical)
20. ✅ Filename in diff tab title
21. ✅ Editable working copy side
22. ✅ Load content off EDT to avoid blocking

### Phase 5: File-Specific Actions ✅
23. ✅ Annotate (blame) support - Show change info per line
24. ✅ Show file history
25. ✅ Show diff with working copy
26. ✅ Compare file with bookmark/change/revision
27. ✅ Show history for selected lines

### Phase 6: Auto-Refresh ✅
28. ✅ Real-time status updates - Automatically refresh when files change

### Phase 7: Settings/Configuration UI ✅
29. ✅ Settings panel under Settings → Version Control → Jujutsu
30. ✅ JJ executable path configuration with file picker
31. ✅ Auto-refresh toggle
32. ✅ Change ID format preference (short/long)
33. ✅ Log change limit configuration
34. ✅ Persistent settings across IDE restarts

### Phase 8: Custom Log Auto-Open ✅
35. ✅ Startup activity to automatically open custom log tab
36. ✅ Setting to enable/disable auto-open behavior
37. ✅ Replace standard VCS log with custom implementation on startup

### Phase 9: Log Context Menu and Auto-Refresh ✅
38. ✅ Right-click context menu for log table entries
39. ✅ Copy Change ID action
40. ✅ Copy Description action
41. ✅ New Change From This action (create new working copy from selected commit)
42. ✅ Describe Working Copy action (for @ entries only)
43. ✅ Show Changes action (placeholder for future implementation)
44. ✅ Auto-refresh log and working copy after VCS operations
45. ✅ Smart selection: select working copy (@) after "new change" operations

### Phase 10: Core VCS Operations (1.0 Blockers) - IN PROGRESS
**P0 - Release Blockers (6 issues)**
- **jj-idea-43o**: Add edit function - Essential operation to move working copy to selected change
- **jj-idea-p1q**: Add abandon function - Core workflow operation to remove changes
- **jj-idea-0ex**: Add icons for all custom actions - UI consistency and professionalism
- **jj-idea-4eq**: Handle non-JJ repositories gracefully - Critical stability issue
- **jj-idea-omi**: Create marketing materials - Required for JetBrains Marketplace listing
- **jj-idea-qvf**: Comprehensive testing - Stability and quality assurance

**Prioritization Rationale**: These operations complete the essential JJ workflow. Power users need edit (navigate history) and abandon (clean up unwanted changes) as core operations. Without these, users must switch to CLI for basic tasks, defeating the purpose of IDE integration.

### Phase 11: Quality & Polish (1.0 High Priority)
**P1 - High Priority (9 issues)**
- Performance fixes (slow annotations - **jj-idea-xpe**, annotation display bugs - **jj-idea-4ly**)
- Discoverability (VCS menu visibility - **jj-idea-c9c**, keyboard shortcuts - **jj-idea-78i**)
- Testing infrastructure (feature parity tests - **jj-idea-kem**, fuller test suite - **jj-idea-nk3**)
- Visual polish (themes, scaling, tooltips - **jj-idea-ckl**)
- Implementation completion (Show Changes action - **jj-idea-6b4**)
- Documentation (CLAUDE.md architecture - **jj-idea-035**)

**Prioritization Rationale**: These improve quality and user experience but aren't blocking. Performance issues affect usability but have workarounds. Documentation and testing ensure maintainability.

### Phase 12: Enhanced Custom Log (Post-1.0)
**P2 - Nice to Have (selected issues)**
- Custom log improvements (multi-select - **jj-idea-fpc**, sorting - **jj-idea-d6g**, filtering - **jj-idea-ncg**, graph enhancements)
- File history enhancements (details pane, mini diff viewer, remote links)
- UI refinements (empty change indicators - **jj-idea-am3**, visual polish)

**Prioritization Rationale**: These enhance the experience but power users can navigate effectively with current log functionality. Better to ensure core operations work flawlessly first.

### Phase 13+: Advanced Operations (Post-1.0)
**P3-P4 - Future Releases**
- **jj-idea-r8h**, **jj-idea-un5**: Enhanced describe/new commands with validation (P4)
- **jj-idea-qgz**, **jj-idea-ww5**: Bookmark management, file ignoring (P4)
- **jj-idea-9q7**, **jj-idea-noc**: Git remote operations and management (P4)
- **jj-idea-ah6**, **jj-idea-cgx**: Rebase/squash operations UI, conflict resolution (P4)

**Prioritization Rationale**: Power users perform these operations via CLI comfortably. Adding UI for these requires careful design and testing. Better to nail the describe-first workflow first, gather user feedback, then expand to advanced operations.

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

## Architecture

### Core Components

#### VCS Integration Layer
```
JujutsuVcs (extends AbstractVcs)
├── root: VirtualFile - Auto-discovers .jj directory
├── JujutsuChangeProvider - Detects file changes via `jj status`
├── JujutsuDiffProvider - Provides diff content
├── JujutsuCheckinEnvironment - Enables commit view
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

#### 2. HashImpl Requirement
Must use `HashImpl.build(hexString)` for all commit hashes. **Cannot** display JJ change IDs directly in standard VCS log due to platform constraints:
- `VcsLogStorageImpl` performs hardcoded cast to `HashImpl`
- `HashImpl.build()` only accepts hex characters (0-9a-f), not JJ format (z-k)
- Details pane shows hex format, custom columns can show JJ format

**See**: `jj/ChangeId.kt`

#### 3. TextCanvas Pattern for Consistent Rendering
Use `TextCanvas` interface with extension functions for consistent formatting:
```kotlin
interface TextCanvas {
    fun append(text: String, style: SimpleTextAttributes)
}
```

Extension functions: `append(changeId)`, `append(description)`, `append(instant)`, `append(bookmarks)`, etc.

**Rationale**: Single source of truth for formatting, type-safe, consistent across plugin

**See**: `ui/TextCanvas.kt`, `ui/DateTimeFormatter.kt`, `ui/Formatters.kt`, `ui/JujutsuColors.kt`

#### 4. Settings Architecture
Project-level `PersistentStateComponent` with `BoundConfigurable` UI:
- `JujutsuSettingsState` - Data class
- `JujutsuSettings` - Service managing state
- `JujutsuConfigurable` - UI panel using Kotlin UI DSL

Settings stored in `.idea/jujutsu.xml`

**See**: `settings/` package

#### 5. Auto-Refresh with BulkFileListener
Modern IntelliJ Platform API for file change detection. Processes changes in batches, automatic disposal through message bus.

**See**: `JujutsuVcs.kt`

#### 6. Auto-Open Custom Log on Startup
`PostStartupActivity` automatically opens custom log tab and suppresses default VCS log tabs via `ContentManagerListener`.

**See**: `JujutsuStartupActivity.kt`

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
│   ├── JujutsuLogProvider.kt
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
./gradlew simpleTest  # 85+ unit tests (no IntelliJ instrumentation)
./gradlew test        # Full integration tests (disabled, needs IntelliJ test setup)
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
- **Imports**: Always use imports over fully-qualified symbols
- **Optimize imports**: Always optimize imports (remove unused, organize)

### VCS Integration Constraints
- **HashImpl requirement**: You **cannot** use custom implementation of `com.intellij.vcs.log.Hash`
  - IntelliJ's VCS log cache serialization (`VcsLogStorageImpl`) is hard-coded to use `HashImpl`
  - Must use `HashImpl.build(hexString)` for all commit hashes
  - See `jj/ChangeId.kt` for hex conversion implementation

### Git Workflow
- **Push workflow**: This project has two remotes (origin and github) with automated workflows
  - `origin`: Primary GitLab remote at home.marigoldfeathers.com
  - `github`: Public GitHub remote
  - GitHub Actions automatically creates tags and advances master in background

- **Standard push sequence**:
  1. Push to origin: `jj git push --remote origin`
  2. Fetch from github: `jj git fetch --remote github` (get automated updates)
  3. Merge if needed: `jj new master@github master@origin -m "Merge github and origin master branches"`
  4. Update master bookmark: `jj bookmark set master`
  5. Push to both remotes: `jj git push --remote origin && jj git push --remote github`

- **Why this order**: GitHub Actions may have updated master@github with new tags since last push, so always fetch from github before pushing to avoid conflicts

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
6. Build and test before committing: `./gradlew build simpleTest`

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
