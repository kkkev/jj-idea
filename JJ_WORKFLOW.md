# Jujutsu Workflow in IntelliJ IDEA

## Overview

This plugin implements a **JJ-native workflow** that embraces Jujutsu's unique approach to version control, which differs fundamentally from Git.

## Key Differences from Git

### Git Workflow
1. Make changes in working directory (untracked)
2. Stage changes with `git add`
3. Commit with `git commit -m "message"`
4. Changes are now committed

### JJ Workflow
1. **Changes are ALWAYS in a commit** - The working copy (`@`) IS a commit
2. **Describe what you're working on** - Set the description upfront with `jj describe`
3. **Keep working** - All changes automatically amend the current commit (`@`)
4. **Start new work** - Use `jj new` to create a new commit on top

## The Tool Window

The Jujutsu tool window appears on the **left side** (like Git's Commit view) and provides a JJ-native interface.

### UI Components

```
┌─────────────────────────────────────┐
│ Working Copy (@)                    │
├─────────────────────────────────────┤
│ Description:                        │
│ ┌─────────────────────────────────┐ │
│ │ [Text area for description]     │ │
│ │                                 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ [Describe] [New Change]             │
├─────────────────────────────────────┤
│                                     │
│ Changes in working copy:            │
│ • modified file1.txt                │
│ • added file2.txt                   │
│                                     │
└─────────────────────────────────────┘
```

## Supported Workflows

Based on [JJ best practices](https://jj-vcs.github.io/jj/latest/tutorial/), the plugin supports two main workflows:

### 1. Describe-First Workflow (Recommended)

**Philosophy**: Know what you want to accomplish before you start.

```bash
# In the plugin:
1. Type your goal in Description field: "Add user authentication"
2. Click "Describe" → runs: jj describe -m "Add user authentication"
3. Make your changes in the editor
4. All changes automatically amend the @ commit
5. When done, click "New Change" → runs: jj new
6. Repeat for next task
```

**Benefits**:
- Clear intent from the start
- Changes are always described
- Easy to track what you're working on
- Supports continuous commits

### 2. Continuous Amend Workflow

**Philosophy**: Iteratively refine your work.

```bash
# In the plugin:
1. Make some changes
2. Write description after you see what you've done
3. Click "Describe"
4. Keep editing - changes continue to amend @
5. Click "New Change" when ready for next task
```

## Button Actions

### "Describe" Button
- **Executes**: `jj describe -m "your description"`
- **What it does**: Sets/updates the description of the current working copy commit (`@`)
- **When to use**:
  - At the start of a new task (describe-first)
  - After making changes to describe what you did
  - To update/refine the description

### "New Change" Button
- **Executes**: `jj new`
- **What it does**: Creates a new commit on top of the current one, making it the new `@`
- **When to use**:
  - When you've completed the current task
  - When you want to start working on something different
  - To "finalize" the current change and move forward

## No "Commit" Button?

**Correct!** In JJ:
- Changes are **always committed** (in the `@` commit)
- You don't "commit changes" - you **describe** what the changes are for
- When done, you **create a new change** on top (not commit)

## Comparison with Other JJ IDE Plugins

### Inspired By:

**[Selvejj](https://plugins.jetbrains.com/plugin/28081-selvejj)** - Another IntelliJ JJ plugin
- Our plugin focuses on the describe-first workflow
- Custom tool window designed for JJ's unique model

**[VisualJJ](https://www.visualjj.com/)** (VSCode) - Visual JJ integration
- Streamlined conflict resolution
- Branch management

**[Jujutsu Kaizen (jjk)](https://marketplace.visualstudio.com/items?itemName=jjk.jjk)** (VSCode)
- Source Control view integration
- Our plugin uses a dedicated left-side tool window instead

## Technical Details

### Commands Used

```kotlin
// Set description
jj describe -r @ -m "message"

// Create new change
jj new

// Get current description
jj log -r @ --no-graph -T description

// List changes
jj status
```

### Auto-Amend Behavior

Every `jj` command automatically amends the working copy commit:
- File changes update `@`
- `jj describe` updates the description of `@`
- `jj new` creates a new `@` on top of the old one

## Sources

- [Jujutsu Tutorial](https://jj-vcs.github.io/jj/latest/tutorial/)
- [The Squash Workflow](https://steveklabnik.github.io/jujutsu-tutorial/real-world-workflows/the-squash-workflow.html)
- [jj init — Sympolymathesy, by Chris Krycho](https://v5.chriskrycho.com/essays/jj-init)
- [JJ Best Practices](https://zerowidth.com/2025/jj-tips-and-tricks/)
- [JJ Workflows](https://kristofferbalintona.me/posts/202503270335/)

## Next Steps

1. **Test the workflow** - Try using describe-first in a real project
2. **Add file list** - Show changed files with click-to-diff
3. **Add history view** - Show `jj log` graphically
4. **Conflict resolution** - Handle merge conflicts in UI
5. **Bookmark management** - Create/manage bookmarks (JJ's version of branches)
