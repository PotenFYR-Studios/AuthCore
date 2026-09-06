#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  REPO="$(cygpath -m "$REPO")"
elif (cd "$REPO" 2>/dev/null && pwd -W >/dev/null 2>&1); then
  REPO="$(cd "$REPO" && pwd -W | sed 's|\\|/|g')"
fi
JARS_DIR="$REPO/java-jars"
mkdir -p "$JARS_DIR/provided"

dl() {
  local url="$1"
  local out="$2"
  if [ ! -s "$out" ]; then
    echo "== download: $out"
    curl -L --fail --retry 3 --retry-delay 2 -o "$out" "$url"
  else
    echo "== exists: $out"
  fi
}

extract_archive() {
  local archive="$1"
  local dest_dir="$2"
  if [ -d "$dest_dir" ] && [ "$(ls -A "$dest_dir" 2>/dev/null)" ]; then
    echo "== ready: $dest_dir"
    return 0
  fi
  mkdir -p "$dest_dir"
  echo "== extracting $archive to $dest_dir"
  local tmp_dir="${dest_dir}.tmp"
  rm -rf "$tmp_dir"
  mkdir -p "$tmp_dir"
  if [[ "$archive" == *.zip ]]; then
    unzip -q -o "$archive" -d "$tmp_dir"
  elif [[ "$archive" == *.tar.gz ]]; then
    tar -xzf "$archive" -C "$tmp_dir"
  fi

  # Find the extracted root directory (adoptium archives contain a nested folder like jdk-21.0.7+6)
  local inner
  inner="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [ -n "$inner" ] && [ -d "$inner/bin" ]; then
    mv "$inner"/* "$dest_dir/"
    rm -rf "$tmp_dir"
  else
    mv "$tmp_dir"/* "$dest_dir/" 2>/dev/null || true
    rm -rf "$tmp_dir"
  fi
}

dl_jdk() {
  local label="$1"
  local url_win="$2"
  local url_linux="$3"
  local out_archive
  local url

  if [[ "$(uname -s 2>/dev/null || echo Windows)" =~ (MINGW|MSYS|CYGWIN|Windows) ]]; then
    out_archive="$JARS_DIR/${label}.zip"
    url="$url_win"
  else
    out_archive="$JARS_DIR/${label}.tar.gz"
    url="$url_linux"
  fi

  dl "$url" "$out_archive"
  extract_archive "$out_archive" "$JARS_DIR/${label}"
}

# JDK portable toolchains using Adoptium API
dl_jdk "jdk-17" \
  "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"

dl_jdk "jdk-21" \
  "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"

dl_jdk "jdk-25" \
  "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse" \
  "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"

# Compile-only proxy API jars (with fallback to local alias if upstream is unreachable)
if [ ! -s "$JARS_DIR/provided/luckperms-api-5.4.jar" ]; then
  if [ -s "$JARS_DIR/provided/net.luckperms-api-5.4.jar" ]; then
    cp "$JARS_DIR/provided/net.luckperms-api-5.4.jar" "$JARS_DIR/provided/luckperms-api-5.4.jar"
  elif [ -s "$JARS_DIR/provided/api-5.4.jar" ]; then
    cp "$JARS_DIR/provided/api-5.4.jar" "$JARS_DIR/provided/luckperms-api-5.4.jar"
  else
    dl "https://repo.luckperms.net/releases/me/luckperms/api/5.4/api-5.4.jar" "$JARS_DIR/provided/luckperms-api-5.4.jar" || touch "$JARS_DIR/provided/luckperms-api-5.4.jar"
  fi
fi

if [ ! -s "$JARS_DIR/provided/bungeecord-api-1.21-R0.3.jar" ]; then
  dl "https://hub.spigotmc.org/jenkins/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar" "$JARS_DIR/provided/bungeecord-api-1.21-R0.3.jar" || touch "$JARS_DIR/provided/bungeecord-api-1.21-R0.3.jar"
fi

if [ ! -s "$JARS_DIR/provided/velocity-api-3.1.1.jar" ]; then
  dl "https://repo.papermc.io/repository/maven-public/com/velocitypowered/velocity-api/3.1.1/velocity-api-3.1.1.jar" "$JARS_DIR/provided/velocity-api-3.1.1.jar" || touch "$JARS_DIR/provided/velocity-api-3.1.1.jar"
fi

FLOODGATE_JAR="$JARS_DIR/provided/floodgate-api-2.2.7.jar"
if [ ! -s "$FLOODGATE_JAR" ]; then
  if [ -s "$JARS_DIR/provided/org.geysermc.floodgate-api-2.2.7.jar" ]; then
    cp "$JARS_DIR/provided/org.geysermc.floodgate-api-2.2.7.jar" "$FLOODGATE_JAR"
  elif [ -s "$JARS_DIR/provided/api-2.2.7.jar" ]; then
    cp "$JARS_DIR/provided/api-2.2.7.jar" "$FLOODGATE_JAR"
  else
    echo "== floodgate api not downloadable - creating stub marker"
    touch "$FLOODGATE_JAR"
# Portable jq helper for Docker host tests
mkdir -p "$JARS_DIR/bin"
if [ ! -s "$JARS_DIR/bin/jq.exe" ] && [ ! -s "$JARS_DIR/bin/jq" ]; then
  if [[ "$(uname -s 2>/dev/null || echo Windows)" =~ (MINGW|MSYS|CYGWIN|Windows) ]]; then
    dl "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-windows-amd64.exe" "$JARS_DIR/bin/jq.exe"
    chmod +x "$JARS_DIR/bin/jq.exe" 2>/dev/null || true
  else
    dl "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-amd64" "$JARS_DIR/bin/jq"
    chmod +x "$JARS_DIR/bin/jq" 2>/dev/null || true
  fi
fi

echo "== Toolchain and provided jars ready in $JARS_DIR =="
ls -l "$JARS_DIR"
ls -l "$JARS_DIR/provided"
