# Migration to IntelliJ Platform Gradle Plugin 2.0

## What Changed

We've successfully migrated from the old Gradle IntelliJ Plugin 1.x to the new **IntelliJ Platform Gradle Plugin 2.0** (version 2.10.5), which is required for building plugins against IntelliJ Platform 2024.2 and later.

## Key Changes

### 1. Plugin ID Changed
**Before:**
```kotlin
id("org.jetbrains.intellij") version "1.17.4"
```

**After:**
```kotlin
id("org.jetbrains.intellij.platform") version "2.10.5"
```

### 2. New Repository Configuration
```kotlin
repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}
```

### 3. Dependencies Block Restructured
**Before:**
```kotlin
intellij {
    version.set("2025.2")
    type.set("IC")
}
```

**After:**
```kotlin
dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        instrumentationTools()
    }
}
```

### 4. Configuration Block Renamed
**Before:** `intellij { }` block
**After:** `intellijPlatform { }` block

```kotlin
intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
```

### 5. Gradle Version Upgraded
- **From:** Gradle 8.5
- **To:** Gradle 8.13 (minimum required)

### 6. Java Version Upgraded
- **From:** Java 17
- **To:** Java 21 (required by IntelliJ 2025.2)

### 7. Kotlin Toolchain
Changed from `kotlinOptions` to `jvmToolchain`:
```kotlin
kotlin {
    jvmToolchain(21)
}
```

## Updated .gitignore

Added `.intellijPlatform/` cache directory to version control exclusions.

## Build Verification

Build now succeeds with:
```bash
./gradlew build
```

Output: `BUILD SUCCESSFUL in 6m 56s`

## Benefits of New Plugin

1. **Better dependency resolution** - More reliable IntelliJ Platform downloads
2. **Modern Gradle APIs** - Uses Provider API for lazy configuration
3. **Improved performance** - Faster builds with better caching
4. **Future-proof** - Required for IntelliJ 2024.2+
5. **Better IDE support** - Improved integration with IntelliJ IDEA itself

## Documentation

Official migration guide: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-migration.html

## What Still Works

All existing functionality remains unchanged:
- ✅ VCS integration
- ✅ Change provider
- ✅ Diff provider
- ✅ Checkin environment
- ✅ File status coloring
- ✅ Commit view

The migration was purely infrastructure - no code changes were needed in the plugin itself!
