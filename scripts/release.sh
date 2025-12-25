#!/bin/bash
set -e

# Usage: ./scripts/release.sh <version> [--dry-run]

VERSION=$1
TAG="v$VERSION"
DRY_RUN=false

# Check for dry-run flag
if [[ "$2" == "--dry-run" ]]; then
  DRY_RUN=true
fi

# ✅ Validate version format: X.Y.Z
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "❌ Invalid version format. Use semantic versioning: X.Y.Z (e.g., 1.2.3)"
  exit 1
fi

# 🔧 Update gradle.properties
echo "🔧 Updating mod_version to $VERSION..."
sed -i.bak "s/^mod_version=.*/mod_version=$VERSION/" gradle.properties
rm gradle.properties.bak

# 📝 Generate changelog
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
if [ -n "$LAST_TAG" ]; then
  echo "📝 Generating changelog since $LAST_TAG..."
  git log "$LAST_TAG"..HEAD --pretty=format:"- %s" > CHANGELOG.md
else
  echo "📝 Generating changelog from initial commit..."
  git log --pretty=format:"- %s" > CHANGELOG.md
fi

# 🧪 Dry-run mode
if [ "$DRY_RUN" = true ]; then
  echo -e "\n🔍 Dry Run Preview:"
  echo "Version: $VERSION"
  echo "Tag: $TAG"
  echo "Changelog:"
  echo "-----------------------------"
  cat CHANGELOG.md
  echo "-----------------------------"
  echo "✅ Dry run complete. No changes pushed."
  exit 0
fi

# 🔨 Build mod to generate .jar
echo "🏗️ Building mod..."
chmod +x ./gradlew
./gradlew build

# 🔍 Auto-detect .jar and validate version
JAR_FILE=$(find build/libs -name "*.jar" | head -n 1)
if [[ ! "$JAR_FILE" =~ "$VERSION" ]]; then
  echo "❌ Detected jar '$JAR_FILE' does not contain version '$VERSION'."
  echo "Make sure your jar filename includes the version (e.g., modname-$VERSION.jar)."
  exit 1
fi

# 🔖 Commit version bump and changelog
git add gradle.properties CHANGELOG.md
git commit -m "🔖 Release $TAG"

# 🚀 Create and push tag
git tag "$TAG"
git push origin main --tags

echo "✅ Release $TAG pushed. GitHub Actions will now create GitHub + Modrinth releases."
