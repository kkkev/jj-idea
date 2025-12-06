# VCS Log Customization for Jujutsu

This document describes how to customize the IntelliJ VCS log view to properly display Jujutsu-specific concepts like change IDs and bookmarks.

## Related Issues

- **#29**: Show formatted change IDs in log view details pane
- **#30**: Display bookmarks as tags in log view commit list
- **#31**: Remove or replace 'Not in any branch' message in log view details

## Architecture Overview

The VCS log view consists of three main areas:

```
┌─────────────────────────────────────────────────┬──────────────────────────────┐
│ Commit Graph & List                             │ Details Panel (right pane)   │
│                                                 │                              │
│ * qpvuntsm @ - Update plugin                    │ ┌──────────────────────────┐ │
│ * ylryv - Fix bug                               │ │ HashAndAuthorPanel       │ │
│ * vwn - Add feature                             │ │  - Hash & author         │ │
│                                                 │ └──────────────────────────┘ │
│                                                 │ ┌──────────────────────────┐ │
│                                                 │ │ CommitMessagePanel       │ │
│                                                 │ │  - Commit description    │ │
│                                                 │ └──────────────────────────┘ │
│                                                 │ ┌──────────────────────────┐ │
│                                                 │ │ ReferencesPanel          │ │
│                                                 │ │  - Bookmarks as tags     │ │
│                                                 │ └──────────────────────────┘ │
│                                                 │ ┌──────────────────────────┐ │
│                                                 │ │ ContainingBranchesPanel  │ │
│                                                 │ │  - "Not in any branch"   │ │
│                                                 │ └──────────────────────────┘ │
└─────────────────────────────────────────────────┴──────────────────────────────┘
```

## Key Classes

All classes are located in the IntelliJ Community repository under `platform/vcs-log/impl/src/com/intellij/vcs/log/ui/`:

### Main Container

**`frame/MainFrame.java`**
- Orchestrates the entire VCS log UI layout
- Creates the details panel: `new CommitDetailsListPanel(logData.getProject(), this, ...)`
- Manages the splitter between graph table and details panel

### Details Panel Components

**`details/CommitDetailsListPanel.kt`**
- Abstract container that manages commit details display
- Coordinates `ChangesBrowserWithLoadingPanel` and actual commit details
- Calls `commitDetails.setCommits(selectedCommits)` on selection changes

**`details/FullCommitDetailsListPanel.kt`**
- Concrete implementation extending `CommitDetailsListPanel`
- Manages loading of commit changes asynchronously
- Uses `OnePixelSplitter` with 0.67 ratio for layout

**`details/commit/CommitDetailsPanel.kt`** ⭐ **PRIMARY CUSTOMIZATION TARGET**
- Main component rendering commit metadata
- Uses `MigLayout` for flexible component arrangement
- Contains all the sub-panels listed below

### Sub-Components (Customization Points)

#### 1. HashAndAuthorPanel ⭐ **ISSUE #29**

**Location**: Inner class within `CommitDetailsPanel.kt`

**Current Behavior**:
- Extends `HtmlPanel` to render HTML content
- Shows "Commit: \<hash\>" with commit hash
- Shows author name and email
- Uses `ExtendableHTMLViewFactory` for styling

**Required Changes for Jujutsu**:
```kotlin
// Current (Git):
// "Commit: abc123def456..."
// "Author: John Doe <john@example.com>"

// Desired (Jujutsu):
// "Change: <b style='color:#c800c8'>qp</b><span style='color:gray'>vuntsm</span>"
// "Commit: abc123def456..." (optional, secondary)
// "Author: John Doe <john@example.com>"
```

**Implementation Strategy**:
1. Access to `VcsCommitMetadata` which should be `JujutsuCommitMetadata`
2. Extract `changeId` from metadata
3. Use `JujutsuCommitFormatter.format()` to get formatted parts
4. Use `JujutsuCommitFormatter.toHtml()` to generate HTML with colors
5. Prepend change ID line before commit hash line

**Key Method**: Likely overrides `getHtmlBody()` or similar to generate HTML content

#### 2. ReferencesPanel.java ⭐ **ISSUE #30**

**Location**: `details/commit/ReferencesPanel.java`

**Current Behavior**:
- Receives `List<VcsRef>` via `setReferences(Collection<VcsRef> refs, Collection<VcsRef> bookmarks)`
- Groups references by type
- Creates colored label icons for each ref type
- Shows refs as inline labels: `[main] [origin/main] [feature-branch]`
- Hides panel entirely when no refs exist: `setVisible(!myGroupedVisibleReferences.isEmpty())`

**Required Changes for Jujutsu**:
- Ensure `JujutsuLogProvider.readAllRefsInternal()` properly creates refs with `BOOKMARK` type
- Verify refs are passed through to `ReferencesPanel`
- Ensure `JujutsuLogRefManager.BOOKMARK` type has proper styling (icon, color)

**Key Methods**:
- `setReferences(Collection<VcsRef> refs, Collection<VcsRef> bookmarks)` - Entry point
- `update()` - Renders the labels
- `createIcon(VcsRef ref)` - Creates colored label icon
- `createLabel(VcsRef ref)` - Creates text label

**Data Flow**:
```
JujutsuLogProvider.readAllRefsInternal()
  → Returns Set<VcsRef> with bookmarks
    → VcsRefImpl(changeId.hash, name, BOOKMARK, root)
      → CommitDetailsPanel.setRefs()
        → ReferencesPanel.setReferences()
          → Filters refs by isBranch() / isTag()
            → update() renders labels
```

**Current Issue**: Bookmarks may not be showing because:
1. `JujutsuLogRefManager.BOOKMARK` type not properly configured
2. Refs not being returned from `readAllRefsInternal()`
3. Filtering logic in `CommitDetailsPanel.setRefs()` excludes them

#### 3. ContainingBranchesPanel ⭐ **ISSUE #31**

**Location**: Referenced in `CommitDetailsPanel.kt` as `myContainingBranchesPanel`

**Current Behavior**:
- Shows "Branches: main, develop" when commit is in branches
- Shows "Not in any branch" when no branches contain the commit
- Uses `VcsLogProvider.getContainingBranches(root, hash)` to get branch list

**Required Changes for Jujutsu**:
```kotlin
// Option 1 (Simple): Hide the panel entirely
// Option 2 (Better): Show bookmark info
//   "Bookmarks: main, feature-x" (if has bookmarks)
//   Hide panel if no bookmarks
// Option 3 (Best): Show working copy indicator
//   "@ Working Copy" (if isWorkingCopy)
//   "Bookmarks: main, feature-x" (if has bookmarks)
//   Hide panel otherwise
```

**Current Implementation in JujutsuLogProvider**:
```kotlin
override fun getContainingBranches(root: VirtualFile, hash: Hash): Collection<String?> {
    // Return empty for now - this would require checking which bookmarks contain this commit
    return emptyList()
}
```

**Implementation Strategy**:
1. Keep returning `emptyList()` to hide "Not in any branch" message
2. Alternative: Return bookmark names if we want them shown in this panel
3. May need to customize the panel label text from "Branches:" to "Bookmarks:"

#### 4. CommitMessagePanel

**Location**: Inner class within `CommitDetailsPanel.kt`

**Current Behavior**:
- Renders commit message as HTML
- Handles hyperlinks to navigate to other commits
- Uses `parseTargetCommit()` for commit hash detection

**Required Changes**: None needed - works fine with Jujutsu descriptions

## Implementation Phases

### Phase 1: Ensure Bookmarks Are Visible (Issue #30)

**Goal**: Make bookmarks appear as colored tags in the log view

**Steps**:
1. Verify `JujutsuLogProvider.readAllRefsInternal()` is called and returns bookmarks
2. Check that refs have proper type: `JujutsuLogRefManager.BOOKMARK`
3. Ensure `BOOKMARK` type has proper styling in `JujutsuLogRefManager`:
   ```kotlin
   val BOOKMARK = SimpleVcsRefType(true, VcsLogStandardColors.Refs.BRANCH)
   // OR
   val BOOKMARK = SimpleVcsRefType(true, JBColor(Color.ORANGE, Color.ORANGE))
   ```
4. Add logging to verify refs are created:
   ```kotlin
   log.info("Created bookmark ref: ${jjRef.name} for change ${jjRef.changeId}")
   ```

**Testing**: Open log view, verify bookmarks appear as colored labels next to commits

### Phase 2: Add Change ID to Details Panel (Issue #29)

**Goal**: Show formatted change ID prominently in the details pane

**Challenge**: `HashAndAuthorPanel` is an internal IntelliJ class we cannot directly modify

**Approach 1: Custom CommitPresentation** (Recommended)
- Customize how `JujutsuCommitMetadata` is converted to `CommitPresentation`
- May need to look for extension points or override points in the rendering pipeline

**Approach 2: Custom Details Panel Factory** (If available)
- Look for extension points to provide custom details panel
- Might be in `VcsLogUiFactory` or similar

**Approach 3: Modify Commit Message** (Workaround)
- Prepend change ID to commit message in `CommitPresentation`
- Less clean but might work if no extension points exist

**Investigation Needed**:
1. How is `CommitPresentation` created from `VcsCommitMetadata`?
2. Are there extension points for customizing details panel?
3. Can we provide a custom `CommitDetailsPanel` implementation?

### Phase 3: Fix "Not in any branch" Message (Issue #31)

**Goal**: Remove or replace the misleading message

**Simple Solution**:
- Keep `getContainingBranches()` returning `emptyList()`
- This hides the entire `ContainingBranchesPanel`
- No misleading message shown

**Better Solution** (if customization is possible):
- Show working copy indicator: "@ Working Copy"
- Show bookmarks containing this commit
- Requires accessing `JujutsuLogEntry.isWorkingCopy` in details panel

## Data Flow Analysis

### How Commit Details Are Loaded

```
User clicks commit in log table
  ↓
MainFrame.VcsLogCommitSelectionListenerForDetails.onSelection()
  ↓
CommitDetailsListPanel.setCommits(selectedCommits)
  ↓
CommitDetailsLoader.loadCommitData() [async]
  ↓
VcsLogProvider.readFullDetails(root, hashes, consumer)
  ↓
JujutsuLogProvider.readFullDetails()
  ↓
Creates JujutsuFullCommitDetails from LogEntry
  ↓
Consumer receives VcsFullCommitDetails
  ↓
CommitDetailsPanel.setCommit(CommitPresentation)
  ↓
HashAndAuthorPanel.setHtml() - renders hash & author
CommitMessagePanel.setMessage() - renders description
ReferencesPanel.setReferences() - renders bookmarks/tags
ContainingBranchesPanel.setBranches() - renders "containing branches"
```

### How References Are Loaded

```
VcsLogProvider.readFirstBlock() or readAllHashes()
  ↓
Returns VcsLogProvider.LogData with refs
  ↓
JujutsuLogProvider.readAllRefsInternal()
  ↓
logService.getRefs()
  ↓
Returns List<JJRef> with type WORKING_COPY or BOOKMARK
  ↓
Maps to VcsRefImpl(changeId.hash, name, type, root)
  ↓
CommitDetailsPanel.setRefs(refs)
  ↓
ReferencesPanel.setReferences(refs, bookmarks)
  ↓
ReferencesPanel.update() renders colored labels
```

## Current JJ Implementation Status

### ✅ Working

- `JujutsuLogProvider.readAllRefsInternal()` creates bookmark refs
- `JujutsuLogRefManager` defines `BOOKMARK` and `WORKING_COPY` types
- Refs are returned with proper `VcsRefImpl` structure

### ❓ Unknown / To Investigate

- Are bookmark refs actually visible in the log view?
- Does `ReferencesPanel` receive our bookmark refs?
- Is `BOOKMARK` type properly styled?
- Can we customize `HashAndAuthorPanel` rendering?

### ❌ Not Working

- Change ID not shown in details panel (Issue #29)
- "Not in any branch" always shown (Issue #31)
- Possibly: Bookmarks not visible as tags (Issue #30)

## Extension Points to Investigate

Look for these extension point IDs in `plugin.xml`:

```xml
<!-- Possible customization points -->
<extensionPoint name="vcsLogCustomization" .../>
<extensionPoint name="vcsLogDetailsPanel" .../>
<extensionPoint name="vcsCommitPresentation" .../>
```

## Testing Strategy

### Manual Testing

1. **Bookmark Visibility** (Issue #30):
   - Create bookmarks: `jj bookmark create main`
   - Open log view
   - Verify bookmarks appear as colored tags
   - Check color and icon match expectations

2. **Change ID Display** (Issue #29):
   - Select a commit in log view
   - Check details pane on right
   - Verify change ID is shown with formatting
   - Verify short prefix is bold and colored

3. **Branch Message** (Issue #31):
   - Select various commits
   - Verify "Not in any branch" doesn't appear
   - Optionally: Verify working copy indicator shows

### Automated Testing

Would require integration tests with IntelliJ test framework:
- Mock `VcsLogUi` and selection changes
- Verify refs are created with correct types
- Test details panel rendering (difficult)

## References

### IntelliJ Community Source Code

- [MainFrame.java](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-log/impl/src/com/intellij/vcs/log/ui/frame/MainFrame.java) - Main UI orchestrator
- [CommitDetailsPanel.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-log/impl/src/com/intellij/vcs/log/ui/details/commit/CommitDetailsPanel.kt) - Details panel rendering
- [ReferencesPanel.java](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-log/impl/src/com/intellij/vcs/log/ui/details/commit/ReferencesPanel.java) - Branch/tag display
- [VcsLogData.java](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-log/impl/src/com/intellij/vcs/log/data/VcsLogData.java) - Data management

### IntelliJ Documentation

- [Log tab - IntelliJ IDEA Documentation](https://www.jetbrains.com/help/idea/log-tab.html) - User-facing documentation
- [Version Control Systems - Plugin SDK](https://plugins.jetbrains.com/docs/intellij/vcs-integration-for-plugins.html) - Plugin development guide

### Project Files

- `src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuLogProvider.kt` - Our log provider implementation
- `src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuLogRefManager.kt` - Our ref type definitions
- `src/main/kotlin/in/kkkev/jjidea/jj/JujutsuCommitMetadata.kt` - Commit metadata wrapper
- `src/main/kotlin/in/kkkev/jjidea/ui/JujutsuCommitFormatter.kt` - Change ID formatting logic

## Next Steps

1. **Immediate**: Add debug logging to verify refs are being created and returned
2. **Short-term**: Investigate bookmark visibility (Issue #30)
3. **Medium-term**: Research extension points for custom details panel (Issue #29)
4. **Long-term**: Implement full customization of details panel

---

**Last Updated**: 2025-12-06
**Author**: Claude Code analysis
**Status**: Design phase - implementation pending
