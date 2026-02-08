# Manual Test Checklist

This document provides a comprehensive manual test checklist for the Jujutsu IDE plugin. These tests require GUI interaction and should be verified manually using `./gradlew runIde`.

Use this checklist:
- Before releases as a regression checklist
- When verifying feature parity with standard VCS logs
- When onboarding new contributors to understand expected behavior

## How to Run Manual Tests

1. Start the IDE with the plugin: `./gradlew runIde`
2. Open a project with a Jujutsu repository (or create one with `jj git init`)
3. Work through each section below, checking off items as you verify them

## Test Categories

### Table Selection & Navigation

- [ ] Single row selection with mouse click
- [ ] Multi-row selection with Shift+click (range)
- [ ] Multi-row selection with Ctrl/Cmd+click (non-contiguous)
- [ ] Arrow key navigation (Up/Down)
- [ ] Page Up/Page Down navigation
- [ ] Home/End key navigation
- [ ] Selection persists after filtering (if entry still visible)
- [ ] Selection clears when filtered entry is hidden

### Column Management

- [ ] Column visibility toggle via column header context menu
- [ ] Column reordering via drag-and-drop
- [ ] Column resizing via drag on separator
- [ ] Double-click column separator to auto-fit width
- [ ] Column widths persist across IDE restarts
- [ ] Column visibility persists across IDE restarts

### Graph Rendering

- [ ] Graph lines render correctly for linear history
- [ ] Graph lines render correctly for merges
- [ ] Graph lines render correctly for branches
- [ ] Working copy (@) indicator visible
- [ ] Colors differentiate branches
- [ ] Graph column auto-sizes to content

### Details Panel

- [ ] Details panel shows on row selection
- [ ] Metadata displays correctly (author, date, change ID)
- [ ] Description renders HTML formatting
- [ ] File change tree shows correct files
- [ ] Double-click file opens it in editor
- [ ] Splitter position persists
- [ ] Toggle details panel position (right/bottom) works

### Context Menu Actions

- [ ] Right-click opens context menu
- [ ] **Copy Change ID** works and copies to clipboard
- [ ] **Copy Description** works and copies to clipboard
- [ ] **New Change From This** creates new change and refreshes
- [ ] **Edit** action changes working copy
- [ ] **Describe** action opens dialog and updates description
- [ ] **Abandon** action removes change after confirmation

### Toolbar & Filters

- [ ] Refresh button reloads data
- [ ] Text search filters entries in real-time
- [ ] Regex toggle enables regex matching
- [ ] Case sensitivity toggle works
- [ ] Author dropdown shows all authors
- [ ] Author filter restricts visible entries
- [ ] Bookmark/reference filter works
- [ ] Date filter restricts to recent commits
- [ ] Clear filters (X button) resets all filters
- [ ] Filters combine correctly (AND logic)

### Multi-Repository (if applicable)

- [ ] Root filter appears for multi-root projects
- [ ] Root filter hides for single-root projects
- [ ] Root gutter column shows repo names
- [ ] Filtering by root works correctly
- [ ] Entries from different roots sort by timestamp

### Auto-Refresh

- [ ] Log refreshes when files change in working copy
- [ ] Log refreshes after VCS operations (describe, new, edit)
- [ ] Working copy (@) selection maintained after refresh
- [ ] No flickering during refresh

### Visual Consistency

- [ ] Light theme renders correctly
- [ ] Dark theme renders correctly
- [ ] Icons display at correct size
- [ ] Row striping visible
- [ ] Hover highlighting works
- [ ] Selected row highlighting distinct
- [ ] Disabled actions appear grayed out

### Edge Cases

- [ ] Empty repository (no commits) shows appropriate message
- [ ] Very long descriptions truncate with ellipsis
- [ ] Non-ASCII characters in descriptions display correctly
- [ ] Large repository (100+ commits) loads without hanging
- [ ] Rapid filtering doesn't cause errors

## Keyboard Shortcuts

Verify these keyboard shortcuts work in the log view:

| Shortcut | Expected Action |
|----------|-----------------|
| Enter | Open selected file (if file selected in details) |
| F4 | Open selected file |
| Ctrl/Cmd+C | Copy selection (change ID or file path) |
| Delete | Abandon selected change (with confirmation) |
| F2 | Rename/describe selected change |

## Working Copy Panel

- [ ] Description text area shows current description
- [ ] "Describe" button updates description via `jj describe`
- [ ] "New Change" button creates new change via `jj new`
- [ ] Changed files tree shows correct status colors
- [ ] File type icons display correctly
- [ ] Double-click opens file in editor
- [ ] Right-click shows context menu with file actions

## Settings (Version Control > Jujutsu)

- [ ] JJ executable path can be configured
- [ ] File picker works for selecting executable
- [ ] Auto-refresh toggle works
- [ ] Change ID format preference (short/long) affects display
- [ ] Log change limit affects number of entries loaded
- [ ] Settings persist across IDE restarts

## Error Handling

- [ ] Invalid JJ path shows helpful error message
- [ ] Non-JJ repository shows appropriate message
- [ ] Network errors during operations show user-friendly errors
- [ ] Concurrent operations don't cause corruption
