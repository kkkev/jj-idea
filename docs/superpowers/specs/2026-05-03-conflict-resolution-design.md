# Conflict Resolution Design

**Date**: 2026-05-03
**Status**: Approved
**Feature**: JJ conflict detection and 3-way merge resolution

## Overview

Add conflict detection and resolution to the JJ IDEA plugin, modelled on git4idea's approach. Users see conflicted files in the Changes view (red, standard IntelliJ styling) and can open IntelliJ's built-in 3-panel merge editor to resolve them. The Conflicts tool window (showing all conflicts across the repo) is explicitly deferred to a follow-up.

## Goals

- Conflicted files appear with `FileStatus.MERGED_WITH_CONFLICTS` in the Changes view
- Right-clicking a conflicted file → "Resolve Conflicts…" opens IntelliJ's standard 3-panel merge editor
- The merge editor is populated with ours/base/theirs content parsed from JJ's conflict markers
- After the user saves the resolved file, JJ auto-detects resolution (no extra CLI call needed)
- Conflict extraction is behind a strategy interface so the implementation can be swapped later

## Out of Scope

- Conflicts tool window (auto-appearing panel listing all conflicted files with Accept Yours / Accept Theirs) — follow-up
- `MergeProvider2` (bulk Accept Yours / Accept Theirs) — TODO in code, follow-up
- Binary file conflict resolution
- N-way (more than 2 sides) conflict handling beyond what fits naturally in the 3-panel view

## Architecture

### 1. Data Model

**`JujutsuConflict`** (`jj/JujutsuConflict.kt`):
```kotlin
data class JujutsuConflict(val repo: JujutsuRepository, val filePath: FilePath)
```
Not used by the `MergeProvider` path (which operates on `VirtualFile` directly). Added now as shared infrastructure for the future Conflicts tool window. No status fields (added/deleted/modified per side) — that belongs to `MergeProvider2`.

**`FileChangeStatus.CONFLICT`** — new enum value added to the existing `FileChangeStatus` enum for domain completeness.

### 2. CLI Layer

**`CommandExecutor.resolveList(revision: Revision): CommandResult`**
- Calls `jj resolve --list -r <revision>`
- Returns raw output (one file path per line)
- Used by future Conflicts tool window; not directly needed for the `MergeProvider` path (the file content already contains markers)

**`CliExecutor`** — implements `resolveList` following the same argument-builder pattern as other commands.

### 3. Conflict Extraction (Strategy Pattern)

**`ConflictExtractor`** interface (`jj/conflict/ConflictExtractor.kt`):
```kotlin
interface ConflictExtractor {
    fun extract(fileContent: ByteArray): MergeData?
}
```
Returns `null` if the file has no recognizable conflict markers or the format is malformed.

**`JjMarkerConflictExtractor`** (`jj/conflict/JjMarkerConflictExtractor.kt`):
- Parses JJ's embedded conflict marker format:
  ```
  <<<<<<< Conflict N of N
  +++++++ Contents of side #1
  [ours content]
  ------- Base
  [base content]
  +++++++ Contents of side #2
  [theirs content]
  >>>>>>> Conflict N of N ends
  ```
- Populates `MergeData.CURRENT` (side #1 / ours), `MergeData.ORIGINAL` (base), `MergeData.LAST` (side #2 / theirs)
- Handles multiple conflict blocks in a single file: for each panel, the full file is reconstructed with every conflict region replaced by the appropriate side (CURRENT = all regions use side #1, ORIGINAL = all regions use base, LAST = all regions use side #2)
- Returns `null` on parse failure rather than throwing

### 4. MergeProvider

**`JujutsuMergeProvider`** (`vcs/merge/JujutsuMergeProvider.kt`) implements `MergeProvider`:

```kotlin
// TODO: Upgrade to MergeProvider2 to support bulk Accept Yours / Accept Theirs
class JujutsuMergeProvider(private val project: Project) : MergeProvider {
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor()

    override fun loadRevisions(file: VirtualFile): MergeData {
        val bytes = file.contentsToByteArray()
        return extractor.extract(bytes)
            ?: throw VcsException("Could not extract conflict data from ${file.name}")
    }

    override fun conflictResolvedForFile(file: VirtualFile) {
        // JJ auto-detects resolution when conflict markers are gone — no CLI call needed
    }

    override fun isBinary(file: VirtualFile) = file.fileType.isBinary
}
```

**`JujutsuVcs`** — override `getMergeProvider()` to return a lazily-initialized `JujutsuMergeProvider`. This single hook causes IntelliJ to automatically expose "Resolve Conflicts…" in the Changes view toolbar and context menu for files with `FileStatus.MERGED_WITH_CONFLICTS`.

### 5. Changes View Integration

**`JujutsuChangeProvider.parseStatusLine()`** — add `'C'` case:
```kotlin
'C' -> addConflictedChange(path, repo, builder)
```

**`addConflictedChange()`**:
```kotlin
private fun addConflictedChange(path: FilePath, repo: JujutsuRepository, builder: ChangelistBuilder) {
    val beforeRevision = repo.createRevision(path, repo.workingCopyParent())
    val afterRevision = CurrentContentRevision(path)
    builder.processChange(
        Change(beforeRevision, afterRevision, FileStatus.MERGED_WITH_CONFLICTS),
        vcs.keyInstanceMethod
    )
}
```

No changes needed to the status header detection loop — `C` lines appear in the working copy section alongside `M`/`A`/`D`.

## Data Flow

```
jj status → JujutsuChangeProvider → FileStatus.MERGED_WITH_CONFLICTS
                                           ↓
                              (file appears red in Changes view)
                                           ↓
                         user right-clicks → "Resolve Conflicts…"
                                           ↓
                    JujutsuMergeProvider.loadRevisions(file)
                                           ↓
                    JjMarkerConflictExtractor.extract(bytes)
                                           ↓
                    MergeData { CURRENT, ORIGINAL, LAST }
                                           ↓
                    IntelliJ 3-panel merge editor (DiffManagerEx)
                                           ↓
                    user edits and saves → conflict markers gone
                                           ↓
                    JJ auto-detects resolution on next refresh
```

## File Layout

```
src/main/kotlin/in/kkkev/jjidea/
├── jj/
│   ├── JujutsuConflict.kt           (new)
│   ├── FileChange.kt                (add CONFLICT to FileChangeStatus)
│   └── conflict/
│       ├── ConflictExtractor.kt     (new — interface)
│       └── JjMarkerConflictExtractor.kt  (new — implementation)
├── vcs/
│   ├── JujutsuVcs.kt               (add getMergeProvider())
│   ├── changes/
│   │   └── JujutsuChangeProvider.kt (add 'C' case)
│   └── merge/
│       └── JujutsuMergeProvider.kt  (new)
```

## Testing

### `JjMarkerConflictExtractorTest`
- Single conflict block → correct CURRENT/ORIGINAL/LAST bytes
- Multiple conflict blocks in one file → all regions handled
- File with no conflict markers → returns `null`
- Malformed / incomplete markers → returns `null`

### `JujutsuChangeProviderTest` (extend existing)
- `C path/to/file.txt` line → `Change` with `FileStatus.MERGED_WITH_CONFLICTS`
- Conflicted file path parsed correctly alongside other statuses

### `JujutsuMergeProviderTest`
- `loadRevisions` with known conflict content → correct `MergeData` bytes
- `loadRevisions` with non-conflicted content → throws `VcsException`
- `isBinary` delegates to `file.fileType.isBinary`

## Future Work

- **Conflicts tool window**: Auto-appearing panel (like git4idea's) listing all conflicted files across all repos, with a conflict count badge. Requires `JujutsuConflictsView`, `JujutsuConflictsToolWindowManager`, and reactive state from `JujutsuStateModel`.
- **`MergeProvider2`**: Upgrade `JujutsuMergeProvider` to `MergeProvider2` to support "Accept Yours" / "Accept Theirs" bulk resolution with status columns in the merge dialog list.
- **N-way conflicts**: JJ supports conflicts with more than 2 sides. The current design handles the common 2-side case; n-way conflicts need a custom UI or a reduction strategy.
- **`resolveList` usage**: The `CommandExecutor.resolveList()` method added in this feature is wired up in the Conflicts tool window follow-up.
