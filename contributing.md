## Terminology
- A **root** is an IDEA VCS root - a folder in the project that has its own VCS configuration, defined
in IDEA's VCS/Directory Mappings configuration. A Jujutsu root points to the same directory as a Jujutsu repository.
- A **repository** is a Jujutsu repository - the top-level folder of a Jujutsu-controlled directory tree.
- A repository has been **initialised** if it contains a `.jj` directory, and hence Jujutsu is tracking its content.

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

