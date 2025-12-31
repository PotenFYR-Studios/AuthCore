#!/bin/bash
set -e

# Usage: sh release.sh <version>

VERSION=$1
TAG="v$VERSION"

# 🔧 Update gradle.properties
echo "🔧 Updating mod_version to $VERSION..."
sed -i.bak "s/^mod_version=.*/mod_version=$VERSION/" gradle.properties
rm gradle.properties.bak

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
