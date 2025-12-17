# Jujutsu VCS IntelliJ IDEA Plugin - Development Guide

This document captures all requirements, implementation decisions, and development instructions for the Jujutsu VCS plugin for IntelliJ IDEA.

## Project Overview

**Goal**: Create a native IntelliJ IDEA plugin for Jujutsu (jj) version control system that implements JJ's unique "describe-first" workflow.

**Technology Stack**:
- Language: Kotlin
- Build System: Gradle with IntelliJ Platform Gradle Plugin 2.0
- Target Platform: IntelliJ IDEA 2025.2+
- Java Version: 21
- VCS Integration: CLI-based with interface abstraction for future library support

## Requirements History

### Phase 1: MVP - Basic Read-Only Features
1. ✅ Basic VCS integration similar to Git plugin
2. ✅ View working copy status (tree view)
3. ✅ Show diffs for changed files
4. ✅ CLI-based command execution with interface abstraction
5. ✅ Change detection and file status coloring

### Phase 2: JJ-Native Workflow
6. ✅ Tool window on LEFT side (not bottom) like Git Commit view
7. ✅ Describe-first workflow support
   - Description text area
   - "Describe" button → `jj describe`
   - "New Change" button → `jj new`
8. ✅ Load current description from `jj log`
9. ✅ Continuous commit workflow (working copy is always a commit)

### Phase 3: Enhanced UI
10. ✅ Tree structure with "Changes" root node
11. ✅ Group by directory with toggle
12. ✅ File counts in grouped nodes (e.g., "src (3 files)")
13. ✅ Expand All / Collapse All buttons
14. ✅ Refresh button
15. ✅ Files colored by status (not letters):
    - Blue: Modified
    - Green: Added
    - Gray: Deleted
16. ✅ File type icons
17. ✅ Click to show diff
18. ✅ Double-click to open file
19. ✅ F4 key to open file
20. ✅ Enter key to open file
21. ✅ Right-click context menu

### Phase 4: Diff View Improvements
22. ✅ Show actual file content (before vs after, not identical)
23. ✅ Filename in diff tab title
24. ✅ Editable working copy side
25. ✅ Load content off EDT to avoid blocking

### Phase 5: File-Specific Actions (Issue #27)
26. ✅ Annotate (blame) support - Show change info per line
27. ✅ Show file history
28. ✅ Show diff with working copy
29. ✅ Compare file with bookmark/change/revision
30. ✅ Show history for selected lines

### Phase 6: Auto-Refresh
31. ✅ Real-time status updates - Automatically refresh when files change

## Architecture

### Core Components

#### VCS Integration Layer
```
JujutsuVcs (extends AbstractVcs)
├── root: VirtualFile - Auto-discovers .jj directory by searching upward
├── JujutsuContentRevision (inner class) - File content at specific revision
├── JujutsuChangeProvider - Detects file changes via `jj status`
├── JujutsuDiffProvider - Provides diff content
├── JujutsuCheckinEnvironment - Enables commit view (minimal)
├── BulkFileListener - Auto-refresh on file system changes
└── JujutsuCommandExecutor (interface)
    └── JujutsuCliExecutor(root) - CLI implementation
```

#### Command Executor Pattern
**Pattern**: `CommandExecutor` interface with CLI implementation (`CliExecutor`)

**Rationale**: Interface abstraction allows future replacement with native library if/when available.

**See**: `src/main/kotlin/in/kkkev/jjidea/jj/CommandExecutor.kt` and `jj/cli/CliExecutor.kt`

#### UI Layer
```
JujutsuToolWindowFactory
└── JujutsuToolWindowPanel (main UI)
    ├── Description area (JBTextArea)
    ├── Buttons (Describe, New Change)
    ├── Toolbar (Refresh, Expand/Collapse, Group toggle)
    └── Changes Tree
        ├── JujutsuChangesTreeModel - Builds tree with grouping
        └── JujutsuChangesTreeCellRenderer - Icons and colors
```

### Key Design Decisions

#### 1. Tree Structure
**Decision**: Use standard Swing `Tree` with custom model instead of IntelliJ's internal `ChangesTreeImpl`.

**Rationale**: `ChangesTreeImpl` and related classes are internal APIs not available in public IntelliJ SDK. Custom implementation provides:
- Full control over tree structure
- Compatibility with IntelliJ 2025.2
- Root node named "Changes" as required
- File count display in directory nodes

#### 2. Grouping Implementation
**Decision**: Custom tree model with `groupByDirectory` boolean parameter.

**Behavior**: When enabled, groups files by directory with counts (e.g., "src/main (5 files)"). When disabled, shows flat list.

**See**: `ui/JujutsuChangesTreeModel.kt`

#### 3. Diff Content Loading
**Decision**: Load revision content in background thread, create UI on EDT.

**Rationale**: `jj file show` is synchronous and blocks EDT. Load content in pooled thread, then create diff UI on EDT.

**See**: `ui/JujutsuToolWindowPanel.kt` - `showDiff()` method

#### 4. Working Copy as Editable Diff Side
**Decision**: Use `VirtualFile` for after-revision to enable editing working copy directly from diff view.

**See**: `ui/JujutsuToolWindowPanel.kt` - diff content creation

#### 5. Revision Identifiers
**Decision**: Use `@` for working copy commit (before revision) when showing working copy changes.

**JJ Concepts**:
- `@` = working copy commit (always exists)
- `@-` = parent of working copy
- Working copy changes (from `jj status`): `@` (before) vs working copy files (after)
- Note: `jj status` shows uncommitted changes in the working copy relative to the `@` commit

#### 6. Log Parsing with Null Byte Separator
**Decision**: Use null byte (`\0`) as field separator in `jj log` template output.

**Rationale**: Commit descriptions can contain newlines, pipes, and special characters. Null bytes ensure robust parsing without escaping.

**Note**: Use `++` concatenation (not `separate()`) since `separate()` skips empty values.

**See**: `jj/cli/CliLogService.kt` - template system and parsing

#### 7. Auto-Refresh with BulkFileListener
**Decision**: Use `BulkFileListener` with message bus to automatically refresh when files change.

**Rationale**:
- Modern IntelliJ Platform API (avoids deprecated `VirtualFileListener`)
- Processes file changes in batches for better performance
- Automatic disposal through message bus connection

## File Structure

```
src/main/kotlin/in/kkkev/jjidea/
├── jj/                                    # Core JJ domain types and interfaces
│   ├── ChangeId.kt                        # JJ change identifier with short prefix
│   ├── FileChange.kt                      # File change DTO (path + status)
│   ├── JujutsuCommandExecutor.kt          # Interface for all jj commands
│   ├── JujutsuCommitMetadata.kt           # VCS metadata wrapper
│   ├── JujutsuCommitMetadataBase.kt       # Base for metadata implementations
│   ├── JujutsuFullCommitDetails.kt        # Full commit with file changes
│   ├── LogEntry.kt                        # Parsed log entry DTO
│   ├── LogService.kt                      # Interface for log queries
│   ├── JJRef.kt                           # Bookmark and ref types
│   └── cli/                               # CLI implementation
│       ├── CliExecutor.kt                 # CLI command execution
│       ├── CliLogService.kt               # CLI-based log service with templates
│       └── AnnotationParser.kt            # Parses jj file annotate output
├── vcs/                                   # IntelliJ VCS framework integration
│   ├── JujutsuVcs.kt                      # Main VCS implementation
│   ├── JujutsuLogProvider.kt              # VCS log integration
│   ├── JujutsuLogRefManager.kt            # Ref (bookmark) manager
│   ├── JujutsuRootChecker.kt              # VCS root detection
│   ├── JujutsuTimedCommit.kt              # Timed commit for log graph
│   ├── changes/
│   │   ├── JujutsuChangeProvider.kt       # Detects file changes
│   │   └── JujutsuContentRevision.kt      # Revision number
│   ├── checkin/
│   │   └── JujutsuCheckinEnvironment.kt   # Enables commit view
│   ├── diff/
│   │   └── JujutsuDiffProvider.kt         # Provides diffs
│   └── history/
│       ├── JujutsuFileRevision.kt         # File revision
│       ├── JujutsuHistoryProvider.kt      # File history provider
│       └── JujutsuHistorySession.kt       # History session
└── ui/                                    # User interface components
    ├── ChangeListCellRenderer.kt          # (unused, can be deleted)
    ├── JujutsuChangesTreeCellRenderer.kt  # Custom tree cell renderer
    ├── JujutsuChangesTreeModel.kt         # Tree model with grouping
    ├── JujutsuCommitFormatter.kt          # Formats commit display
    ├── JujutsuToolWindowFactory.kt        # Creates tool window
    └── JujutsuToolWindowPanel.kt          # Main UI panel (Changes view)

src/test/kotlin/in/kkkev/jjidea/
├── RequirementsTest.kt                    # Documents all requirements
├── commands/
│   └── JujutsuCommandResultTest.kt        # CommandResult tests
├── jj/
│   ├── ChangeIdTest.kt                    # ChangeId tests (18 tests)
│   ├── FileChangeTest.kt                  # FileChange tests (11 tests)
│   └── cli/
│       ├── AnnotationParserTest.kt        # Annotation parser tests
│       └── LogTemplateTest.kt             # Log template integration tests (30 tests)
├── ui/
│   ├── JujutsuChangesTreeModelSimpleTest.kt # Tree model tests (4 tests)
│   ├── JujutsuCommitFormatterTest.kt      # Commit formatter tests (4 tests)
│   ├── JujutsuFileAnnotationTest.kt       # File annotation tests
│   └── JujutsuLogEntryTest.kt             # Log entry tests (~30 tests)
└── vcs/
    ├── actions/
    │   └── JujutsuCompareWithPopupTest.kt # Compare popup bookmark parsing tests (14 tests)
    └── changes/
        └── JujutsuRevisionNumberTest.kt   # Revision number tests

src/main/resources/META-INF/
├── plugin.xml                             # Main plugin configuration
└── jujutsu-vcslog.xml                     # VCS log provider registration
```

## JJ Commands Used

| Command | Purpose | Implementation |
|---------|---------|----------------|
| `jj status` | List working copy changes | `JujutsuChangeProvider.parseStatus()` |
| `jj describe -r @ -m "msg"` | Set working copy description | "Describe" button |
| `jj new` | Create new change on top | "New Change" button |
| `jj log -r @ --no-graph -T template` | Get log entries with metadata | `CliLogService`, history provider |
| `jj file show -r @ path` | Get file content at revision | `JujutsuContentRevision.getContent()`, compare actions |
| `jj file annotate -r @ -T template path` | Get line-by-line change attribution | `JujutsuAnnotationProvider` |
| `jj bookmark list` | List all bookmarks | `JujutsuCompareWithBranchAction` |
| `jj diff path` | Show diff for file | `JujutsuDiffProvider` |
| `jj --version` | Check availability | `isAvailable()`, `version()` |

## Testing Strategy

**Framework**: JUnit 5 + Kotest assertions + MockK mocks

**Test Files**:
- `RequirementsTest.kt` - Documents all 25+ requirements as integration test placeholders
- `JujutsuCommandResultTest.kt` - ✅ Tests for CommandResult (3 tests)
- `ChangeIdTest.kt` - ✅ Tests for ChangeId (18 tests - NEW)
- `FileChangeTest.kt` - ✅ Tests for FileChange (11 tests - NEW)
- `JujutsuLogServiceTest.kt` - ✅ Tests for JujutsuLogService enums (3 tests - NEW)
- `JujutsuLogEntryTest.kt` - ✅ Tests for JujutsuLogEntry (~30 tests, 18+ added)
- `JujutsuLogParserTest.kt` - ✅ Comprehensive tests for log parsing (15+ tests)
- `JujutsuCommitFormatterTest.kt` - ✅ Tests for commit ID formatting (4 tests)
- `JujutsuChangesTreeModelSimpleTest.kt` - ✅ Tests for tree node display logic (4 tests)
- `JujutsuRevisionNumberTest.kt` - Tests for revision numbers (needs IntelliJ Platform)

**To Run Tests**:
```bash
# Simple unit tests without IntelliJ instrumentation (25 tests)
./gradlew simpleTest

# Full integration tests (requires proper IntelliJ test setup)
./gradlew test  # Currently disabled due to instrumentation issues
```

**Test Results** (as of 2025-12-10):
- 58 tests total
- 34 simple unit tests passing
- 24 tests require IntelliJ Platform test fixtures (ChangeId, JujutsuLogEntry, etc. use Hash type)
- Test coverage includes comprehensive template-based log parsing tests

**Writing New Tests**:
Use JUnit tests with Kotest assertions:

```kotlin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ExampleTest {
    @Test
    fun `descriptive test name using backticks`() {
        val result = someFunction()
        result shouldBe expected
        result.toString() shouldContain "substring"
    }
}
```

**Note on IntelliJ Platform Tests**:
Tests that use IntelliJ Platform classes (VirtualFile, Project, etc.) require special test fixtures provided by IntelliJ Platform Gradle Plugin. For now, these are documented in `RequirementsTest.kt` as `@Disabled` integration tests. To enable them, configure proper IntelliJ test environment with `testFramework(TestFrameworkType.Platform)`.

## Building & Running

### Build
```bash
./gradlew build
```

### Run in IDE (for testing)
```bash
./gradlew runIde
```

### Package Plugin
```bash
./gradlew buildPlugin
```

Output: `build/distributions/jj-idea-<version>.zip`

## Configuration

**plugin.xml**:
```xml
<extensions defaultExtensionNs="com.intellij">
    <vcs name="Jujutsu" vcsClass="in.kkkev.jjidea.JujutsuVcs" />

    <toolWindow
        id="Jujutsu"
        anchor="left"
        icon="AllIcons.Vcs.Branch"
        factoryClass="in.kkkev.jjidea.ui.JujutsuToolWindowFactory"
    />
</extensions>
```

**build.gradle.kts**:
- Platform: IntelliJ IDEA Community 2025.2
- Kotlin: 2.1.0
- Gradle: 8.13
- Java: 21

## Known Issues & Limitations

### Current Limitations
1. **VcsRootChecker disabled**: API changed in IntelliJ 2025.2, needs investigation
2. **No commit history view**: Only working copy view implemented
3. **Module grouping not implemented**: Only directory grouping available
4. **Basic context menu**: Only "Show Diff" and "Open File" actions

### Resolved Issues
✅ EDT blocking when loading diffs (fixed: load in background)
✅ Diff showing identical content (fixed: proper revision loading)
✅ Tool window at bottom instead of left (fixed: `anchor="left"`)
✅ Files showing M/A letters instead of colors (fixed: custom renderer)

## Future Work

For planned features and enhancements, see the issue tracker managed with beads:

```bash
bd list --status=open    # View all open issues
bd ready                 # View issues ready to work on
bd show <issue-id>       # View detailed issue information
```

Visit the beads repository for project planning and tracking.

## Development Workflow

### Adding a New JJ Command

1. Add method to `CommandExecutor` interface
2. Implement in `CliExecutor`
3. Use from UI or VCS providers

### Adding UI Features

1. Update `JujutsuToolWindowPanel.kt`
2. Add toolbar actions if needed
3. Wire event handlers
4. Test with `./gradlew runIde`

### Debugging

- Enable debug logging with `Logger.getInstance(MyClass::class.java)`
- Run with `./gradlew runIde --debug-jvm`
- All jj commands are logged by `CliExecutor`

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

## Contributing

When adding features or fixing bugs:

1. Track work in beads issue tracker (`bd create`, `bd update`, etc.)
2. Update CLAUDE.md with new architectural decisions
3. Add tests (use Kotest assertions, MockK for mocking)
4. Follow threading rules: I/O on background threads, UI on EDT
5. Build and test before committing: `./gradlew build simpleTest`

## Recent Changes

For detailed change history, see git log. Major architectural changes documented here:

- **2025-12-11**: Auto-refresh with BulkFileListener (jj-idea-7gw)
- **2025-12-10**: Template-based log parsing system with type-safe composable templates
- **2025-12-02**: Architecture refactoring - VCS root discovery and provider cleanup

## License

[Your license here]

---

**Last Updated**: 2025-12-11
**Plugin Version**: 0.1.0-SNAPSHOT
**IntelliJ Version**: 2025.2
- 
- Respect .editorconfig, prefer code on a single line where it fits into the width
- Use expression body for functions where possible
- Use a single return point in functions where possible
- Keep documentation in the docs folder
- Store tasks in glab. When I refer to issues or tasks, look in glab.
- In functions, avoid multiple return points where possible. For example, rather than shortcut by returning when a required variable is null, chain calls with ?.let.
- Prefer single-line expressions for functions. For example, if a function just returns a simple expression, rather than use `fun foo() { return bar }`, use `fun foo() = bar`
- Where a function returns an unambiguous type and is declared as a single-line expression, omit the type. For example, rather than `fun foo(): String = bar`, use `fun foo() = bar`. However, if the expression is the result of a platform call where nullity is ambiguous, declare the type explicitly.
- Always use imports over fully-qualified symbols
- Always optimise imports
- When pushing, just push to origin by default. I will push to github manually.