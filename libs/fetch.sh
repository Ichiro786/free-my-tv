#!/usr/bin/env bash
# Downloads the libraries the built-in IPTV player needs (Media3 ExoPlayer and its dependencies) into libs/.
# build.sh runs this when they are missing. Plain jars: Google Maven (AAR classes.jar) and Maven Central.
set -euo pipefail
cd "$(dirname "$0")"
G=https://dl.google.com/android/maven2
M=https://repo1.maven.org/maven2
V=1.5.1
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
for a in common exoplayer exoplayer-hls extractor datasource decoder container database; do
  [ -f "media3-$a.jar" ] && continue
  curl -sfL -o "$tmp/$a.aar" "$G/androidx/media3/media3-$a/$V/media3-$a-$V.aar"
  unzip -qo -d "$tmp/$a" "$tmp/$a.aar" classes.jar
  mv "$tmp/$a/classes.jar" "media3-$a.jar"
done
[ -f guava.jar ] || curl -sfL -o guava.jar "$M/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar"
[ -f failureaccess.jar ] || curl -sfL -o failureaccess.jar "$M/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar"
[ -f collection.jar ] || curl -sfL -o collection.jar "$G/androidx/collection/collection/1.2.0/collection-1.2.0.jar"
echo "libs ready"
