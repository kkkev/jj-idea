#!/bin/bash
set -euo pipefail

# Release script for jj-idea
# Usage: ./scripts/release.sh <version>
# Example: ./scripts/release.sh 0.2.0

VERSION="${1:-}"

if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 0.2.0"
    exit 1
fi

# Validate version format (semver without v prefix)
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: Version must be in semver format (e.g., 0.2.0)"
    exit 1
fi

TAG="v$VERSION"
DATE=$(date +%Y-%m-%d)

echo "Preparing release $VERSION..."

# Check for uncommitted changes
if ! jj status | grep -q "Working copy changes:" && jj status | grep -q "no description"; then
    : # Clean state, continue
else
    if jj status | grep -q "Working copy changes:"; then
        echo "Warning: You have uncommitted changes."
        read -p "Continue anyway? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
fi

# Check if tag already exists
if jj log -r "tags()" --no-graph -T 'tags ++ "\n"' 2>/dev/null | grep -q "^$TAG$"; then
    echo "Error: Tag $TAG already exists"
    exit 1
fi

# Check changelog has [Unreleased] content
UNRELEASED_CONTENT=$(awk '
    /^## \[Unreleased\]/ { found=1; next }
    found && /^## \[/ { exit }
    found && /^\[.+\]:/ { exit }
    found && NF { has_content=1 }
    END { print has_content ? "yes" : "no" }
' CHANGELOG.md)

if [ "$UNRELEASED_CONTENT" != "yes" ]; then
    echo "Error: No content in [Unreleased] section of CHANGELOG.md"
    echo "Please add changelog entries before releasing."
    exit 1
fi

echo "Step 1/5: Updating CHANGELOG.md..."

# Move [Unreleased] content to new version section
# This is a bit complex - we need to:
# 1. Keep the header and [Unreleased] section header
# 2. Add empty [Unreleased] section
# 3. Add new version section with content

awk -v version="$VERSION" -v date="$DATE" '
    BEGIN { in_unreleased = 0; printed_new_version = 0 }

    # Print everything before [Unreleased]
    /^## \[Unreleased\]/ {
        print $0
        print ""
        print "## [" version "] - " date
        in_unreleased = 1
        printed_new_version = 1
        next
    }

    # When we hit the next version section, stop copying unreleased content
    in_unreleased && /^## \[/ {
        in_unreleased = 0
        print $0
        next
    }

    # When we hit link references, update them
    /^\[Unreleased\]:/ {
        print "[Unreleased]: https://github.com/kkkev/jj-idea/compare/v" version "...HEAD"
        next
    }

    # Print everything else
    { print }
' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

# Add link for new version if it doesn't exist
if ! grep -q "^\[$VERSION\]:" CHANGELOG.md; then
    # Find the line with [Unreleased]: and add new version link after it
    awk -v version="$VERSION" '
        /^\[Unreleased\]:/ {
            print
            print "[" version "]: https://github.com/kkkev/jj-idea/releases/tag/v" version
            next
        }
        { print }
    ' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
fi

echo "Step 2/5: Updating version in build.gradle.kts..."
sed -i.bak "s/^version = .*/version = \"$VERSION\"/" build.gradle.kts
rm -f build.gradle.kts.bak

echo "Step 3/5: Verifying build..."
./gradlew build --quiet

echo "Step 4/5: Committing changes..."
jj describe -m "Release $VERSION"

echo "Step 5/5: Creating and pushing tag..."
echo ""
echo "Ready to create tag $TAG and push to remotes."
echo ""
read -p "Push to origin and github? [y/N] " -n 1 -r
echo

if [[ $REPLY =~ ^[Yy]$ ]]; then
    # Create new commit for the tag (jj bookmarks are on commits)
    jj new -m "Post-release: bump to next snapshot"
    jj bookmark create "$TAG" -r @-

    # Push tag to both remotes
    jj git push --remote origin --bookmark "$TAG"
    jj git push --remote github --bookmark "$TAG"

    echo ""
    echo "Release $VERSION complete!"
    echo ""
    echo "Next steps:"
    echo "  1. Update version to next snapshot in build.gradle.kts"
    echo "  2. GitHub Actions will create the release automatically"
    echo ""
else
    echo ""
    echo "Changes committed but not pushed."
    echo "To complete the release manually:"
    echo "  jj new -m 'Post-release: bump to next snapshot'"
    echo "  jj bookmark create $TAG -r @-"
    echo "  jj git push --remote origin --bookmark $TAG"
    echo "  jj git push --remote github --bookmark $TAG"
fi
