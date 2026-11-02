#!/bin/bash
# Thin wrapper so the team can run the same build without installing Gradle.
export JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.8-hotspot"
GRADLE_BIN=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle 2>/dev/null | head -1)
if [ -z "$GRADLE_BIN" ]; then
  echo "gradle 9.3.1 not found in ~/.gradle/wrapper/dists" >&2
  exit 127
fi
cd /d/linkup-rebuild || exit 1
exec "$GRADLE_BIN" "$@"
