# git4idea Research: File-Specific Actions

**Date**: 2025-12-09
**Purpose**: Document git4idea implementation patterns for "Compare with Branch/Revision" and "Show History for Selection" features to guide Jujutsu plugin implementation.

## Overview

This document contains research findings from the IntelliJ Community git4idea plugin to implement similar features for the Jujutsu VCS plugin. The goal is to follow IntelliJ Platform conventions and reuse standard VCS components wherever possible.

## File Paths (IntelliJ Community)

### Compare with Branch/Revision
- **Main Action**: `plugins/git4idea/src/git4idea/actions/GitCompareWithRefAction.kt`
- **Base Class**: `plugins/dvcs/src/com/intellij/dvcs/actions/DvcsCompareWithAction.kt`
- **Popup Dialog**: `plugins/dvcs/src/com/intellij/dvcs/ui/DvcsCompareWithBranchPopup.kt`
- **Branch Chooser**: `platform/dvcs-impl/src/com/intellij/dvcs/ui/BranchActionGroupPopup.kt`

### Show History for Selection
- **Main Action**: `platform/vcs-impl/src/com/intellij/openapi/vcs/actions/SelectedBlockHistoryAction.kt`
- **Dialog**: Uses standard `FileHistoryDialog` from VCS platform
- **Model**: `platform/vcs-impl/src/com/intellij/openapi/vcs/history/VcsHistorySession.kt`

### Tabbed File History
- **Main Action**: `platform/vcs-impl/src/com/intellij/openapi/vcs/actions/TabbedShowHistoryAction.kt`
- **Panel**: `platform/vcs-impl/src/com/intellij/openapi/vcs/history/impl/VcsHistoryPanel.kt`

### Action Registration
- **Git Actions Group**: `plugins/git4idea/resources/META-INF/plugin.xml`
- **Editor Actions**: `plugins/git4idea/src/git4idea/actions/GitFileActions.kt`

## Architecture Patterns

### 1. Compare with Branch/Revision

#### Base Class: `DvcsCompareWithAction`

Git extends this DVCS base class which provides:
- Standard popup UI for branch/tag selection
- Diff creation logic
- Integration with IntelliJ's diff viewer

**Key Methods to Override:**
```kotlin
abstract class DvcsCompareWithAction : DumbAwareAction() {
    // Get list of branches/refs to show in popup
    abstract fun getBranchNamesExceptCurrent(
        repository: Repository,
        root: VirtualFile
    ): Collection<String>

    // Create diff for selected ref
    abstract fun getDiffChanges(
        project: Project,
        root: VirtualFile,
        targetRef: String,
        currentRef: String
    ): Collection<Change>
}
```

**Implementation Pattern:**
1. User triggers action (right-click → Jujutsu → Compare with Branch...)
2. Action shows `DvcsCompareWithBranchPopup` with branch/bookmark list
3. User selects target ref
4. Action creates diff using VCS provider
5. Shows diff in standard IntelliJ diff viewer

**For Jujutsu:**
- Extend `DvcsCompareWithAction` (or similar base if available)
- Override `getBranchNamesExceptCurrent()` to return:
  - Bookmarks from `jj bookmark list`
  - Common revision symbols: `@`, `@-`, `@--`, etc.
  - Allow manual change ID input
- Use existing `JujutsuDiffProvider` to create diffs
- Reuse standard `DiffManager.showDiff()` for display

#### Standard Dialogs Used

**DvcsCompareWithBranchPopup:**
- Part of DVCS platform API
- Provides searchable list of refs
- Supports grouping (local/remote branches, tags)
- Free to use for any DVCS plugin

### 2. Show History for Selection

#### Base Class: `SelectedBlockHistoryAction`

Platform VCS action that:
- Captures editor selection (line range)
- Queries VCS for history of those lines
- Shows filtered history in dialog

**Key Flow:**
1. User selects lines in editor
2. Action becomes enabled (only when selection exists)
3. User triggers action
4. Action calls `VcsHistoryProvider.reportAppendableHistory()` with line range
5. Shows results in `FileHistoryDialog`

**Line Range Filtering:**
- Git uses `git log -L <start>,<end>:<file>` command
- Returns only commits that modified those specific lines
- Efficient server-side filtering

**For Jujutsu:**
- **Problem**: `jj log` does NOT support line-range filtering (as of Dec 2024)
- **Solution**: Post-filtering approach:
  1. Get full file history via existing `JujutsuHistoryProvider`
  2. For each revision, check if it modified the selected lines
  3. Use `jj file annotate` to map lines → change IDs
  4. Filter history to only include those change IDs
- **Alternative**: Request feature from jj team (future enhancement)

#### Standard Components Used

**FileHistoryDialog:**
- Standard IntelliJ Platform component
- Automatically handles:
  - History list display
  - Diff preview
  - Filtering/searching
  - Export functionality
- Just provide `VcsHistorySession` with filtered revisions

### 3. Action Registration Pattern

#### Git's Approach in plugin.xml

```xml
<actions>
    <group id="Git.FileActions">
        <action id="Git.CompareWithBranch"
                class="git4idea.actions.GitCompareWithRefAction"/>
        <action id="Git.ShowSelectionHistory"
                class="com.intellij.openapi.vcs.actions.SelectedBlockHistoryAction"/>
    </group>

    <group id="Git.ContextMenu">
        <reference ref="Git.FileActions"/>
        <add-to-group group-id="VcsGroup" anchor="last"/>
        <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    </group>
</actions>
```

#### Current Jujutsu Registration

From `plugin.xml`:
```xml
<actions>
    <group id="JujutsuEditorActionGroup"
           class="in.kkkev.jjidea.vcs.actions.JujutsuEditorActionGroup"
           popup="true">
        <add-to-group group-id="VcsGroup" anchor="last"/>
    </group>
</actions>
```

**Pattern:**
- `JujutsuEditorActionGroup` extends `StandardVcsGroup`
- Automatically includes standard VCS actions (Show Diff, Show History, etc.)
- Add custom actions by registering them in the group

### 4. Testing Strategies

#### Git's Test Approach

**Unit Tests:**
- Test ref parsing and validation
- Test diff creation logic
- Mock VCS executor for command results
- Located in: `plugins/git4idea/tests/`

**Integration Tests:**
- Test full action flow with test repository
- Use `GitTestCase` base class
- Require IntelliJ test framework setup
- Slower but comprehensive

**For Jujutsu:**
- **Unit Tests** (can run in CI):
  - Test bookmark list parsing: `jj bookmark list` output → List<String>
  - Test change ID validation (format, length)
  - Test line range extraction from editor selection
  - Test filtering logic (which revisions match line range)
  - Use MockK for `JujutsuCommandExecutor`

- **Integration Tests** (manual or CI with test repo):
  - Full action flow with real jj repository
  - Test UI interactions
  - Verify diff display correctness

- **Simple Test Suite**:
  - Add to `simpleTest` source set for fast CI validation
  - Focus on business logic without IntelliJ Platform dependencies

## JJ Command Capabilities

### Available Commands

| Command | Purpose | Output Format |
|---------|---------|---------------|
| `jj bookmark list` | List all bookmarks | One per line: `bookmark-name: change-id` |
| `jj log -r <rev>` | Show commit history | Templated output (already used) |
| `jj file annotate <file>` | Line-by-line change attribution | `change-id: line-content` |
| `jj file show -r <rev> <file>` | File content at revision | Raw file content (already used) |
| `jj diff -r <rev>` | Diff against revision | Standard diff format |

### Missing Capabilities

| Feature | Git Command | JJ Status |
|---------|-------------|-----------|
| Line-range history | `git log -L <start>,<end>:<file>` | ❌ Not available |
| Blame with line range | `git blame -L <start>,<end> <file>` | ❌ Not available |

**Workaround for Line-Range History:**
1. Use `jj file annotate` to get change IDs for all lines
2. Extract change IDs for selected line range
3. Filter full file history to only those change IDs
4. Less efficient than server-side filtering, but functional

## Implementation Recommendations

### For "Compare with Branch/Revision"

**1. Add Bookmark List Command**

Update `JujutsuCommandExecutor.kt`:
```kotlin
interface JujutsuCommandExecutor {
    // ... existing methods ...

    fun bookmarkList(root: VirtualFile): CommandResult
}
```

Implement in `JujutsuCliExecutor.kt`:
```kotlin
override fun bookmarkList(root: VirtualFile): CommandResult =
    execute(root, listOf("bookmark", "list"))
```

**2. Create Action**

New file: `JujutsuCompareWithBranchAction.kt`
```kotlin
class JujutsuCompareWithBranchAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // Get bookmarks and change IDs
        val refs = getAvailableRefs(project, file)

        // Show selection popup
        val chosen = showRefChooserPopup(refs, project)

        // Create and show diff
        showDiffWithRef(project, file, chosen)
    }

    private fun getAvailableRefs(project: Project, file: VirtualFile): List<String> {
        val vcs = JujutsuVcs.find(project) ?: return emptyList()
        val result = vcs.commandExecutor.bookmarkList(vcs.root)

        return if (result.isSuccess) {
            parseBookmarks(result.output) + listOf("@", "@-", "@--")
        } else {
            emptyList()
        }
    }
}
```

**3. Register Action**

In `JujutsuEditorActionGroup.kt`, add action to group.

In `plugin.xml`:
```xml
<action id="Jujutsu.CompareWithBranch"
        class="in.kkkev.jjidea.vcs.actions.JujutsuCompareWithBranchAction"
        text="Compare with Branch or Change..."
        description="Compare current file with a bookmark or change">
    <add-to-group group-id="JujutsuEditorActionGroup" anchor="last"/>
</action>
```

**4. Add Tests**

New file: `JujutsuCompareWithBranchActionTest.kt`
```kotlin
class JujutsuBookmarkParserTest {
    @Test
    fun `parse bookmark list output`() {
        val output = """
            main: abc123
            feature: def456
        """.trimIndent()

        val bookmarks = parseBookmarks(output)

        bookmarks shouldContain "main"
        bookmarks shouldContain "feature"
    }
}
```

### For "Show History for Selection"

**1. Extend History Provider**

Modify `JujutsuHistoryProvider.kt` to support line filtering:
```kotlin
class JujutsuHistoryProvider : VcsHistoryProvider {
    // ... existing methods ...

    fun getHistoryForLines(
        filePath: FilePath,
        startLine: Int,
        endLine: Int,
        revision: VcsRevisionNumber?
    ): VcsHistorySession? {
        // Get full history
        val fullHistory = createSessionFor(filePath)

        // Get line annotations to find relevant changes
        val affectedChanges = getChangesAffectingLines(
            filePath,
            startLine,
            endLine
        )

        // Filter history
        val filtered = fullHistory.revisionList.filter { rev ->
            rev.changeId in affectedChanges
        }

        return JujutsuHistorySession(filtered)
    }

    private fun getChangesAffectingLines(
        filePath: FilePath,
        startLine: Int,
        endLine: Int
    ): Set<String> {
        // Use jj file annotate to map lines → change IDs
        val vcs = JujutsuVcs.find(project) ?: return emptySet()
        val result = vcs.commandExecutor.annotate(
            vcs.root,
            filePath.path
        )

        return parseAnnotationForLineRange(result.output, startLine, endLine)
    }
}
```

**2. Create Action**

New file: `JujutsuShowHistoryForSelectionAction.kt`
```kotlin
class JujutsuShowHistoryForSelectionAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selection = editor.selectionModel
        val startLine = selection.selectionStartPosition?.line ?: return
        val endLine = selection.selectionEndPosition?.line ?: return

        // Get filtered history
        val provider = JujutsuHistoryProvider()
        val session = provider.getHistoryForLines(
            VcsUtil.getFilePath(file),
            startLine,
            endLine,
            null
        )

        // Show in standard dialog
        showHistoryDialog(project, file, session)
    }
}
```

**3. Register Action**

In `plugin.xml`:
```xml
<action id="Jujutsu.ShowSelectionHistory"
        class="in.kkkev.jjidea.vcs.actions.JujutsuShowHistoryForSelectionAction"
        text="Show History for Selection"
        description="Show history of selected lines">
    <add-to-group group-id="JujutsuEditorActionGroup" anchor="last"/>
</action>
```

**4. Add Tests**

New file: `JujutsuHistoryFilteringTest.kt`
```kotlin
class JujutsuHistoryFilteringTest {
    @Test
    fun `extract change IDs from annotation for line range`() {
        val annotation = """
            abc123: line 1
            abc123: line 2
            def456: line 3
            def456: line 4
        """.trimIndent()

        val changes = parseAnnotationForLineRange(annotation, 2, 3)

        changes shouldContain "abc123"
        changes shouldContain "def456"
    }
}
```

## Key Takeaways

1. **Reuse IntelliJ Platform Components**: Don't create custom dialogs when standard VCS dialogs exist
2. **Follow DVCS Patterns**: Extend base classes like `DvcsCompareWithAction` when available
3. **Action Registration**: Register in existing action group, not as separate top-level actions
4. **Testing**: Separate unit tests (fast, CI-friendly) from integration tests (requires full platform)
5. **JJ Limitations**: Work around missing line-range filtering with post-filtering approach
6. **Future Enhancement**: Request line-range filtering feature from jj team for better performance

## References

- IntelliJ Platform VCS Integration: https://plugins.jetbrains.com/docs/intellij/vcs-integration.html
- Git4idea Source: `../intellij-community/plugins/git4idea/`
- DVCS Base Classes: `../intellij-community/plugins/dvcs/`
- VCS Platform API: `../intellij-community/platform/vcs-api/`
- VCS Implementation: `../intellij-community/platform/vcs-impl/`

## Future Research Needed

- [ ] Check if `DvcsCompareWithAction` is available in IntelliJ 2025.2 public API
- [ ] Investigate `FileHistoryDialog` vs `VcsHistoryDialog` for selection history
- [ ] Research bookmark-specific UI components (if any)
- [ ] Test performance of post-filtering approach with large files
- [ ] Consider caching annotation results for better performance

---

**Last Updated**: 2025-12-09
**IntelliJ Version**: 2025.2
**JJ Version**: 0.23.0+
