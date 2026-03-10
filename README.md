# Jujutsu VCS Plugin for IntelliJ IDEA

Native IntelliJ integration for [Jujutsu (jj)](https://jj-vcs.github.io/jj/), a Git-compatible version control system built around a fundamentally different workflow: your working copy is always a commit.

![Custom log view with commit graph and tooltip](docs/images/log-light-with-tooltip.png)

## Features

- **Describe-First Workflow** — The Working Copy tool window lets you describe your current work and create new changes with one click. No staging area, no "WIP" commits.
- **Custom Log View** — Visual commit graph with inline change IDs, descriptions, bookmarks, and author info. Filter by text, branch, author, or date range.
- **Change Operations** — Edit, abandon, describe, squash, and rebase changes directly from the log context menu.
- **Rebase** — Full rebase dialog with source mode selection (-r/-s/-b), visual destination picker with commit graph, and live preview.
- **Git Remotes** — Fetch and push to Git remotes without leaving the IDE.
- **File History & Annotations** — Full file history with diff viewer. Line-by-line blame annotations.
- **Multi-Repository Support** — Work with multiple JJ repositories in a single project with a unified log view.
- **Real-Time Status** — Auto-refresh keeps the UI in sync as you edit files.

### Working Copy

The Working Copy panel sits on the left side of the IDE, showing changed files grouped by directory with status coloring. Describe your work and create new changes without leaving your editor.

![Working copy panel](docs/images/working-copy-light.png)

### Log View

The custom log replaces the standard VCS log with a JJ-native view. Hover over any commit for details, or right-click for operations.

![Log with context menu](docs/images/log-light-with-menu.png)

Works in both light and dark themes:

![Log view in dark theme](docs/images/log-dark-with-tooltip.png)

### Rebase Dialog

Visual rebase with source mode selection, searchable destination picker, and a live preview showing the result before you commit to it.

![Rebase dialog with live preview](docs/images/rebase-light.png)

## Installing

### From Custom Repository (Recommended)

1. In IntelliJ IDEA: **Settings → Plugins → ⚙️ → Manage Plugin Repositories**
2. Add: `https://raw.githubusercontent.com/kkkev/jj-idea/master/updatePlugins.xml`
3. Search for "Jujutsu VCS Integration" in the Marketplace tab and install

Future updates will be detected automatically.

### From GitHub Releases

1. Go to [Releases](https://github.com/kkkev/jj-idea/releases)
2. Download the latest `.zip` file
3. In IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk**

### Build from Source

```bash
./gradlew buildPlugin
```

Then install from `build/distributions/` via **Settings → Plugins → Install Plugin from Disk**.

## Requirements

- IntelliJ IDEA 2025.2 or later
- [Jujutsu](https://jj-vcs.github.io/jj/latest/install/) (`jj`) installed and available in PATH

## Getting Started

1. Open a project with a `.jj` directory, or create one with **VCS → Create JJ Repository**
2. The Working Copy panel appears on the left; the Jujutsu log appears in the Version Control tool window
3. Configure settings at **Settings → Version Control → Jujutsu**

## Documentation

- **[Contributing](contributing.md)** — Development guidelines
- **[Developer Guide](CLAUDE.md)** — Architecture and implementation details

## License

[Apache License 2.0](LICENSE)
