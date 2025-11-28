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

## Architecture

### Core Components

#### VCS Integration Layer
```
JujutsuVcs (extends AbstractVcs)
├── JujutsuChangeProvider - Detects file changes via `jj status`
├── JujutsuDiffProvider - Provides diff content
├── JujutsuCheckinEnvironment - Enables commit view (minimal)
└── JujutsuCommandExecutor (interface)
    └── JujutsuCliExecutor - CLI implementation
```

#### Command Executor Pattern
```kotlin
interface JujutsuCommandExecutor {
    fun status(root: VirtualFile): CommandResult
    fun diff(root: VirtualFile, filePath: String): CommandResult
    fun show(root: VirtualFile, filePath: String, revision: String): CommandResult
    fun describe(root: VirtualFile, message: String, revision: String = "@"): CommandResult
    fun new(root: VirtualFile, message: String? = null): CommandResult
    fun log(root: VirtualFile, revisions: String = "@", template: String? = null): CommandResult
    fun isAvailable(): Boolean
    fun version(): String?
}
```

**Rationale**: Interface abstraction allows future replacement with native library if/when available.

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

**Implementation**:
```kotlin
class JujutsuChangesTreeModel(
    private val project: Project,
    private val groupByDirectory: Boolean
) {
    fun buildModel(changes: List<Change>): DefaultTreeModel
}
```

When `groupByDirectory = true`:
- Creates `DirectoryNode` for each unique parent directory
- Shows file count: "src/main (5 files)"
- Sorts directories and files alphabetically

When `groupByDirectory = false`:
- All files as direct children of root
- Flat structure

#### 3. Diff Content Loading
**Decision**: Load revision content in background thread, create UI on EDT.

**Rationale**: Calling `change.beforeRevision?.content` executes synchronous `jj file show` command. This blocks EDT and causes errors:
```
Synchronous execution on EDT: jj file show -r @ .idea/vcs.xml
```

**Solution**:
```kotlin
private fun showDiff(change: Change) {
    ApplicationManager.getApplication().executeOnPooledThread {
        // Load content in background (jj file show)
        val beforeContent = change.beforeRevision?.content ?: ""
        val afterVirtualFile = LocalFileSystem.getInstance().findFileByPath(afterPath.path)

        ApplicationManager.getApplication().invokeLater {
            // Create and show diff on EDT
            val diffRequest = SimpleDiffRequest(fileName, content1, content2, ...)
            diffManager.showDiff(project, diffRequest)
        }
    }
}
```

#### 4. Working Copy as Editable Diff Side
**Decision**: Use `VirtualFile` for after-revision instead of string content.

**Implementation**:
```kotlin
val content2 = if (afterVirtualFile != null && afterVirtualFile.exists()) {
    contentFactory.create(project, virtualFile)  // Editable
} else {
    contentFactory.create(project, afterContent, filePath)  // Read-only
}
```

This enables editing the working copy directly from the diff view.

#### 5. Revision Identifiers
**Decision**: Use `@-` for parent of working copy (before revision).

**JJ Concepts**:
- `@` = working copy commit (always exists)
- `@-` = parent of working copy
- Modified file diff: `@-` (before) vs `@` (after/working copy)

## File Structure

```
src/main/kotlin/in/kkkev/jjidea/
├── JujutsuVcs.kt                          # Main VCS implementation
├── commands/
│   ├── JujutsuCommandExecutor.kt          # Interface for all jj commands
│   └── JujutsuCliExecutor.kt              # CLI implementation
├── changes/
│   ├── JujutsuChangeProvider.kt           # Detects file changes
│   ├── JujutsuContentRevision.kt          # File content at revision
│   └── CurrentContentRevision.kt          # Working copy content
├── diff/
│   └── JujutsuDiffProvider.kt             # Provides diffs
├── checkin/
│   └── JujutsuCheckinEnvironment.kt       # Enables commit view
└── ui/
    ├── JujutsuToolWindowFactory.kt        # Creates tool window
    ├── JujutsuToolWindowPanel.kt          # Main UI panel
    ├── JujutsuChangesTreeModel.kt         # Tree model with grouping
    ├── JujutsuChangesTreeCellRenderer.kt  # Custom renderer
    └── ChangeListCellRenderer.kt          # (Legacy, unused)

src/test/kotlin/in/kkkev/jjidea/
└── RequirementsTest.kt                    # Documents all requirements

src/main/resources/META-INF/
└── plugin.xml                             # Plugin configuration
```

## JJ Commands Used

| Command | Purpose | Implementation |
|---------|---------|----------------|
| `jj status` | List working copy changes | `JujutsuChangeProvider.parseStatus()` |
| `jj describe -r @ -m "msg"` | Set working copy description | "Describe" button |
| `jj new` | Create new change on top | "New Change" button |
| `jj log -r @ --no-graph -T description` | Get current description | `loadCurrentDescription()` |
| `jj file show -r @ path` | Get file content at revision | `JujutsuContentRevision.getContent()` |
| `jj diff path` | Show diff for file | `JujutsuDiffProvider` (future) |
| `jj --version` | Check availability | `isAvailable()`, `version()` |

## Testing Strategy

**Framework**: JUnit 5 + Kotest assertions + MockK mocks

**Test Files**:
- `RequirementsTest.kt` - Documents all 25+ requirements as integration test placeholders
- `JujutsuCommandResultTest.kt` - ✅ Tests for CommandResult (passing)
- `JujutsuRevisionNumberTest.kt` - Tests for revision numbers (need IntelliJ classpath)
- `JujutsuChangesTreeModelSimpleTest.kt` - ✅ Tests for tree node display logic (passing)

**To Run Tests**:
```bash
# Simple unit tests without IntelliJ instrumentation
./gradlew simpleTest

# Full integration tests (requires proper IntelliJ test setup)
./gradlew test  # Currently disabled due to instrumentation issues
```

**Test Results** (as of 2025-11-29):
- 33 tests discovered
- 27 tests passing (command results, tree model nodes)
- 6 tests require IntelliJ Platform test fixtures (revision numbers, content revisions)

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

## Future Enhancements

### High Priority
1. **Commit History View**: Show `jj log` graphically with revision graph
2. **Bookmark Management**: Create/manage bookmarks (JJ's version of branches)
3. **Conflict Resolution**: UI for handling merge conflicts
4. **Rebase/Squash Operations**: Support common jj operations

### Medium Priority
5. **Auto-refresh**: Update when files change on disk
6. **Module Grouping**: Group by IntelliJ module in addition to directory
7. **Enhanced Context Menu**: More VCS operations (revert, abandon, etc.)
8. **Status Bar**: Show current change description

### Low Priority
9. **Settings Panel**: Configure jj executable path, default flags
10. **Annotations**: Show change information in editor gutter
11. **Diff Syntax Highlighting**: Improve diff view appearance

## Development Workflow

### Adding a New JJ Command

1. **Update interface** (`JujutsuCommandExecutor.kt`):
```kotlin
fun newCommand(root: VirtualFile, param: String): CommandResult
```

2. **Implement in CLI executor** (`JujutsuCliExecutor.kt`):
```kotlin
override fun newCommand(root: VirtualFile, param: String): CommandResult {
    return execute(root, listOf("command", param))
}
```

3. **Use in UI or provider**:
```kotlin
val result = vcs.commandExecutor.newCommand(root, "value")
if (result.isSuccess) {
    // Handle success
}
```

### Adding UI Features

1. Update `JujutsuToolWindowPanel.kt`
2. Add action to toolbar if needed (`createChangesToolbar()`)
3. Wire up event handlers
4. Test in `runIde`

### Debugging Tips

1. **Enable debug logging**:
```kotlin
private val log = Logger.getInstance(MyClass::class.java)
log.debug("Message: $value")
```

2. **Run with debug**:
```bash
./gradlew runIde --debug-jvm
```

3. **Check command output**:
All `jj` commands log via `JujutsuCliExecutor`:
```kotlin
log.debug("Executing: ${commandLine.commandLineString}")
```

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

1. **Update this document** with new requirements/decisions
2. **Add tests** documenting the requirement in `RequirementsTest.kt`
3. **Follow threading rules**:
   - File I/O and `jj` commands: background thread (`executeOnPooledThread`)
   - UI updates: EDT (`invokeLater`)
4. **Use kotest assertions** in tests: `result shouldBe expected`
5. **Mock dependencies** with MockK when testing
6. **Build and test** before committing:
   ```bash
   ./gradlew build test
   ```

## License

[Your license here]

---

**Last Updated**: 2025-11-29
**Plugin Version**: 0.1.0-SNAPSHOT
**IntelliJ Version**: 2025.2
- 
- Respect .editorconfig, prefer code on a single line where it fits into the width
- Use expression body for functions where possible
- Use a single return point in functions where possible