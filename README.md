# Jujutsu VCS Plugin for IntelliJ IDEA

A plugin that integrates [Jujutsu](https://github.com/martinvonz/jj) version control system with IntelliJ IDEA.

## Features (MVP)

- **Working Copy Status**: View changed, added, and deleted files in your working copy
- **Diff Viewer**: View diffs for modified files
- **VCS Root Detection**: Automatically detects jujutsu repositories (`.jj` directory)
- **Read-only Operations**: Safe exploration of your repository state

## Architecture

The plugin is designed with a clean separation between the VCS operations and their implementation:

- **`JujutsuCommandExecutor`** interface: Abstraction for jj operations
- **`JujutsuCliExecutor`**: CLI-based implementation (current)
- Future-ready for native library integration

## Building

```bash
./gradlew build
```

## Running in Development

```bash
./gradlew runIde
```

This will launch a new IntelliJ IDEA instance with the plugin installed.

## Installing

### Option 1: From Custom Repository (Recommended - Auto Updates)

1. In IntelliJ IDEA: **Settings → Plugins → ⚙️ (gear icon) → Manage Plugin Repositories**
2. Click **+** and add: `https://raw.githubusercontent.com/kevinb9n/jj-idea/main/updatePlugins.xml`
3. Click **OK**
4. Search for "Jujutsu VCS Integration" in the Marketplace tab
5. Click **Install**

Future updates will be automatically detected and available through the normal plugin update mechanism.

### Option 2: From GitHub Releases

1. Go to [Releases](https://github.com/kevinb9n/jj-idea/releases)
2. Download the latest `.zip` file
3. In IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk**
4. Select the downloaded ZIP file

### Option 3: Build from Source

1. Build the plugin: `./gradlew buildPlugin`
2. In IntelliJ IDEA: **Settings → Plugins → Install Plugin from Disk**
3. Select the generated ZIP file from `build/distributions/`

## Using the Plugin

1. Open a project that contains a jujutsu repository (has a `.jj` directory)
2. The plugin will automatically detect the VCS root
3. Enable Jujutsu VCS for your project: Settings → Version Control → Directory Mappings
4. View changes in the "Commit" or "Version Control" tool window

## Requirements

- IntelliJ IDEA 2025.2 or later
- Jujutsu (`jj`) installed and available in PATH
- Java 21 or later
- Gradle 8.13 or later (included via wrapper)

## Documentation

- **[Development Roadmap](docs/ROADMAP.md)** - Prioritized feature roadmap with GitLab issue links
- **[Developer Guide](CLAUDE.md)** - Complete project documentation and architecture
- **[VCS API Reference](docs/VCS_API_REFERENCE.md)** - IntelliJ VCS integration reference

## Contributing

See [ROADMAP.md](docs/ROADMAP.md) for planned features and [CLAUDE.md](CLAUDE.md) for development guidelines.
